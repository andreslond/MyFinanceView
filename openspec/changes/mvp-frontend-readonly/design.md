## Context

`myfinance.*` recibe transacciones diariamente vía n8n y hoy el usuario sólo puede consultarlas con SQL directo. El backend Java de Épica 3 está scaffoldeado pero TASK-BE-03+ no aterrizan en semanas. El usuario decidió lanzar un MVP esta misma noche en Vercel, con datos reales, sin esperar al backend, y sin crear código desechable: cuando el backend Java exponga endpoints REST, la capa de datos del frontend debe reemplazarse sin tocar componentes ni hooks.

Restricciones del proyecto que esta nota debe respetar:
- [`docs/base-standards.md §2`](../../../docs/base-standards.md) — frontend nunca computa agregaciones de plata ni valida invariantes de negocio.
- [`docs/frontend-standards.md §2`](../../../docs/frontend-standards.md) — la UI consume valores que el backend ya formateó.
- [`SPEC.md §7`](../../../SPEC.md) — el sistema de categorización tiene feedback loop a `myfinance.merchants` que NO puede perderse cuando el usuario confirma.
- [`CLAUDE.md`](../../../CLAUDE.md) — monolito modular en backend, multi-agente, branches aisladas, `progress.md` por change.

## Goals / Non-Goals

**Goals:**
- Vercel deploy funcional esta noche con datos reales de Supabase productivo (`akkoqdjmmozyqdfjkabg`).
- Auth Supabase end-to-end con RLS estricta por `auth.uid()`.
- Cero pérdida del invariante de aprendizaje de `merchants` cuando se cambia categoría desde la UI.
- Capa de datos reemplazable por endpoints Java REST sin tocar componentes ni hooks (forward compatibility verificable).
- Migraciones SQL versionadas en `backend/database/migrations/` para que el ambiente local con Testcontainers refleje producción.

**Non-Goals:**
- Reemplazar al backend Java. Esto es un puente, no la solución final.
- Billing summary, breakdown por categoría, proyecciones de cierre, manejo de cuotas, dashboards — todo eso espera al backend.
- CRUD/merge de `merchants` desde UI.
- Totales agregados computados en frontend (si hace falta uno, va por vista SQL).
- Notificaciones, webhooks, integración con Telegram desde frontend.
- Refactor o cambio de comportamiento del backend Java.

## Decisions

### D1 — Frontend habla directo a Supabase (Camino A)

Decisión: el frontend usa `supabase-js` y consulta PostgREST con el JWT del usuario; RLS hace el filtrado por `auth.uid()`. No hay backend intermedio.

Alternativas descartadas:
- **Backend Java + frontend Vercel.** TASK-BE-03 (Spring Security JWT) y TASK-BE-04+ no están listos. ETA realista 2-4 semanas. Mata el lanzamiento de esta noche.
- **API routes Node serverless en Vercel.** Crea un "shadow backend" en Deno/Node que duplica el trabajo del backend Java cuando entre. Peor relación esfuerzo/durabilidad.

Razón: PostgREST + RLS de Supabase es una API REST tipada por schema y autorizada por política de DB. Cubre exactamente lo que necesitamos en el MVP sin escribir backend.

### D2 — Feedback loop a `merchants` como trigger Postgres, no en TypeScript

Decisión: implementar `myfinance.tg_transaction_category_feedback` como `AFTER UPDATE` en `myfinance.transactions` que actualiza/UPSERT `myfinance.merchants` cuando `category_confirmed` transiciona `false → true` (o cuando se confirma con un `category_id` distinto al previo).

Alternativas descartadas:
- **Update desde el cliente.** Pone lógica de negocio en el frontend. Riesgo: cliente puede saltarse el update de `merchants` (bug, falla de red entre las dos llamadas). Rompe el invariante.
- **Supabase Edge Function.** Crea código Deno/JS adicional que vive en otro runtime. Más superficie de mantenimiento; eventualmente se reemplaza con backend Java igual.
- **Diferir el feedback hasta que llegue el backend.** Mata el valor demostrable del MVP: el sistema dejaría de aprender.

Razón: el trigger garantiza atomicidad (mismo `UPDATE` que cambia la categoría), es idempotente bajo retries, y sobrevive a la migración a backend Java porque el backend puede mantenerlo, reemplazarlo por servicio, o seguir delegando al trigger — decisión separada.

Esquema del trigger (resumen — detalle en migración):
```
AFTER UPDATE OF category_id, category_confirmed ON myfinance.transactions
WHEN (NEW.category_confirmed = TRUE AND (OLD.category_confirmed IS DISTINCT FROM TRUE
       OR OLD.category_id IS DISTINCT FROM NEW.category_id))
EXECUTE FUNCTION myfinance.fn_update_merchant_from_transaction()
```
La función deriva `raw_pattern` desde `NEW.description` con la misma normalización que usa n8n (a documentar en la migración), busca por `(user_id, raw_pattern)`, hace UPSERT, sube `confidence` en `LEAST(0.95, confidence + 0.1)`, incrementa `match_count`, setea `last_confirmed_at = now()`.

### D3 — Capa `services/` como única superficie de datos

Decisión: el frontend tiene `src/services/<entity>Service.ts` con métodos públicos (`list`, `get`, `updateCategory`, …). Cada service usa `lib/supabaseClient.ts` internamente. Componentes y pantallas sólo consumen hooks de React Query encima de los services.

Regla complementaria: lint estático (ESLint custom rule o `no-restricted-imports`) prohíbe importar `@supabase/supabase-js` fuera de `lib/supabaseClient.ts` y de los archivos en `services/`.

Alternativas descartadas:
- **Usar `supabase-js` directo en componentes.** Hace imposible la migración futura sin tocar las pantallas. Tentación clásica del "rápido para el MVP" que cuesta caro después.
- **Generar tipos con `supabase gen types` y usarlos crudos.** Acopla la UI al schema de DB (snake_case, sin enriquecimiento). Cuando el backend Java responda DTOs ricos, hay que mapear igual.

Razón: la capa fina es el truco completo para forward compatibility. Migración futura: cambiar el body de los services de `supabase.from(...).select(...)` a `fetch('/api/v1/...')` con el mismo JWT del `useAuth()`. Componentes, hooks, routing, estado — nada cambia.

### D4 — DTOs alineados con `api-spec.yml` futuro

Decisión: los services exponen tipos TS en camelCase, fechas como ISO strings (`occurredAt: string`), montos como string para preservar precisión (`amount: string`, parsed con `Decimal.js` o similar al renderizar). Cuando llegan filas de Supabase en snake_case con `numeric` y `timestamptz`, un mapper privado convierte. El mapper vive dentro del service y se borra cuando llega el backend.

Alternativas descartadas:
- **Usar tipos del schema de Supabase directo.** Acopla la UI al naming de DB; obliga a refactor masivo cuando el backend devuelve otro shape.
- **Tipos genéricos `Record<string, unknown>`.** Mata el valor de TS strict.

Razón: el `docs/api-spec.yml` es el contrato canónico futuro. Codificar la UI contra ese contrato hoy es deuda cero.

### D5 — RLS estricta y catálogos compartidos

Decisión:
- `transactions`, `accounts`, `merchants`: `SELECT/INSERT/UPDATE/DELETE` con `USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id)`.
- `categories`: `SELECT` para `authenticated`. No UPDATE/INSERT/DELETE desde la UI del MVP.
- `banks`: `SELECT` para `authenticated`. Cierra TASK-DT-01.
- `user_settings`: `SELECT/UPDATE` con `auth.uid() = user_id`. No tocada por la UI, pero la política se aplica por consistencia.
- `budgets`, `budget_categories`, `exchange_rates`: vacías hoy; aplicar RLS por completitud (cierre TASK-DT-01 ampliado).

Verificación: tests en `backend/src/test` con Testcontainers o con cliente PostgREST contra ambiente local que reproduzcan RLS — un usuario no ve filas de otro, queries anónimas devuelven 401.

Alternativa descartada:
- **RLS sólo donde el MVP lee.** Deja huecos en tablas vacías hoy pero peligrosas mañana. Auditoría debe pasar limpia desde ya.

Razón: RLS es defensa en profundidad. Aplicar a todo el schema cierra la deuda TASK-DT-01 y elimina la ansiedad de "¿qué pasa si una tabla queda expuesta?".

### D6 — Auth via Supabase Auth UI prebuilt

Decisión: usar `@supabase/auth-ui-react` con `@supabase/auth-ui-shared` para el formulario de login (email/password + magic link). Estilizar con el theme alineado a `docs/design/`. Sesión gestionada por `supabase.auth.onAuthStateChange` + Context `useAuth()`.

Alternativa descartada:
- **Formulario custom.** No agrega valor en el MVP, alarga el día. Se puede reemplazar después si hace falta UX más fina.

Razón: tiempo. Es la única decisión donde aceptamos atajo de UI; no compromete arquitectura ni forward compat.

### D7 — Vercel deploy con root `frontend/`

Decisión: vincular el monorepo a Vercel con root directory `frontend/`. Build: `npm run build`. Output: `dist/`. Variables: `VITE_SUPABASE_URL`, `VITE_SUPABASE_ANON_KEY`.

Razón: separar el frontend en proyecto Vercel propio evita acoplar deploys del backend Java cuando llegue. Es el patrón estándar para monorepos en Vercel.

## Risks / Trade-offs

- **[Riesgo] Lógica de negocio fragmentada entre trigger y futuro backend Java.** Mitigación: el trigger es atómico y autocontenido; el backend Java puede mantenerlo (preferido al inicio), o reemplazarlo si necesita lógica más rica, sin que el frontend lo note. Documentar el trigger en `data-model.md` al archivar.
- **[Riesgo] `raw_pattern` normalización divergente entre n8n y el trigger.** Mitigación: documentar el algoritmo de normalización en la migración y replicarlo de n8n. Test de integración compara: dado un `description` del banco, el trigger produce el mismo `raw_pattern` que n8n.
- **[Riesgo] ESLint rule de aislamiento ignorada por agentes posteriores.** Mitigación: además del lint, escribir un test estático (Vitest + glob) que falle si encuentra `import .* from '@supabase/supabase-js'` fuera del allowlist. CI bloquea.
- **[Riesgo] Confidence inflada sin tope.** Mitigación: `LEAST(0.95, confidence + 0.1)` deja headroom para que el algoritmo del backend Java refine la curva sin tocar valores acumulados.
- **[Riesgo] Sesión Supabase desincronizada con backend Java cuando entre.** Mitigación: Spring Security debe validar el `iss` y `aud` del JWT exactamente como hoy lo emite Supabase. El JWT secret en backend Java es el mismo que el de Supabase. Documentado en TASK-BE-03.
- **[Riesgo] Vercel anon key expuesta.** Mitigación: la `anon key` es pública por diseño; RLS es la defensa. El JWT secret nunca sale del backend Java (ni del trigger, que vive en Postgres). Si la key se filtra, no hay daño porque sin sesión válida no se puede leer nada.
- **[Trade-off] Sin billing summary en MVP.** El usuario verá una lista de transacciones, no un resumen de ciclo. Aceptamos porque la regla de "frontend no agrega plata" es dura, y los totales relevantes los entregará el backend Java.

## Migration Plan

Tres ramas, en orden de dependencia:

1. **`chore/rls-myfinance-feedback-trigger`** (bloqueante)
   - Aplicar políticas RLS en todas las tablas de `myfinance` (ver D5).
   - Crear `myfinance.fn_update_merchant_from_transaction()` + trigger.
   - Migraciones SQL versionadas en `backend/database/migrations/`.
   - Aplicación a producción vía Supabase MCP (`apply_migration`).
   - Tests de RLS (un usuario no ve filas de otro; anon devuelve 401).
   - Test de trigger (confirmar categoría sube confidence y match_count; crea merchant si no existía).
   - PR a `main`, adversarial review, merge.

2. **`feat/frontend-mvp-scaffold`** (depende de #1)
   - Vite + React 19 + TS strict + Tailwind en `frontend/`.
   - Dependencias: `@supabase/supabase-js`, `@supabase/auth-ui-react`, `@tanstack/react-query`, `react-router`, `vitest`, `@testing-library/react`.
   - Estructura `lib/`, `services/`, `hooks/`, `pages/`, `auth/`.
   - ESLint rule + test estático de aislamiento de `supabase-js`.
   - `frontend/AGENTS.md` documentando reglas para futuros agentes.
   - PR a `main`, adversarial review, merge.

3. **`feat/frontend-mvp-screens-and-deploy`** (depende de #2)
   - Implementar las 3 pantallas (login, lista, modal cambio categoría).
   - Tests unitarios y de integración.
   - Vincular proyecto a Vercel.
   - Deploy productivo y verificación end-to-end con datos reales.
   - PR a `main`, adversarial review, merge.

Rollback:
- Si una migración SQL causa problemas, revertir vía migración `V{n+1}__rollback_<...>.sql` (Postgres no permite "down" automático sin Flyway, que aún no está adoptado — TASK-DB-06 lo cierra).
- Si Vercel deploy rompe, mantener el proyecto Vercel anterior (no aplica — es el primer deploy) o revertir el commit en `main` y redeploy.
- El backend Java no se ve afectado por ninguna de estas ramas, así que no tiene rollback que coordinar.

## Open Questions

- ¿La normalización de `raw_pattern` que hace n8n está documentada en algún archivo del repo? Si no, hay que extraerla del workflow n8n y replicarla en la `fn_update_merchant_from_transaction()`. Esto se resuelve durante la implementación de la rama #1; si la normalización es trivial (primer token + asterisco), se documenta en la migración.
- ¿Se quiere "Sign up" abierto en el MVP o sólo login para el único usuario? Decisión por defecto: sólo login (el SPEC dice "para un usuario"). Si en algún momento se invita a otro usuario, basta con crearlo en Supabase Auth manualmente; la RLS ya garantiza aislamiento.
- ¿El theme del Supabase Auth UI debe matchear el design-system del proyecto desde día 1? Decisión por defecto: theme oscuro mínimo alineado a tokens de `docs/design/design-system.md`. Si queda feo, se itera; no bloquea el deploy.
