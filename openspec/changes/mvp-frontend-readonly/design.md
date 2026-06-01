## Context

`myfinance.*` recibe transacciones diariamente vía n8n y hoy el usuario sólo puede consultarlas con SQL directo. El backend Java de Épica 3 está scaffoldeado pero TASK-BE-03+ no aterrizan en semanas. El usuario decidió lanzar un MVP esta misma noche en Vercel, con datos reales, sin esperar al backend, y sin crear código desechable: cuando el backend Java exponga endpoints REST, la capa de datos del frontend debe reemplazarse sin tocar componentes ni hooks.

La adversarial review del 2026-06-01 (`adversarial-review-2026-05-31.md`, veredicto FAIL con 7 Blockers) hizo evidente que la versión original del proposal asumía artefactos de DB que aún no existen (`merchants`, `category_confirmed`) y reintroducía RLS que ya está activa via `backend/database/migrations/V002__rls_policies.sql`. Esta revisión aplica el "Path 2" del veredicto (línea 146): colapsar la capability `myfinance-data-policies`, dropear el trigger del feedback loop, y aceptar que el MVP no incluye actualización de `merchants` hasta que aterrice el backend Java.

Restricciones del proyecto que esta nota debe respetar:
- [`docs/base-standards.md §2`](../../../docs/base-standards.md) — frontend nunca computa agregaciones de plata ni valida invariantes de negocio.
- [`docs/frontend-standards.md §2`](../../../docs/frontend-standards.md) — la UI consume valores que el backend ya formateó.
- [`SPEC.md §7`](../../../SPEC.md) — el sistema de categorización tiene feedback loop a `myfinance.merchants` que el backend Java SHALL implementar en TASK-BE-06; el MVP frontend lo difiere conscientemente.
- [`CLAUDE.md`](../../../CLAUDE.md) — monolito modular en backend, multi-agente, branches aisladas, `progress.md` por change.
- [`docs/workflow.md §2 Gate C`](../../../docs/workflow.md) — proposal SHALL declarar `## Threat model` (cumplido en `proposal.md`).

## Goals / Non-Goals

**Goals:**
- Vercel deploy funcional esta noche con datos reales de Supabase productivo (`akkoqdjmmozyqdfjkabg`).
- Auth Supabase end-to-end apoyada en la RLS de V002 (`auth.uid() = user_id`).
- Capa de datos reemplazable por endpoints Java REST sin tocar componentes ni hooks (forward compatibility verificable por test estático + ESLint).
- Cero código de DB nuevo en este change. Cualquier cambio de schema/política/trigger queda fuera de scope.

**Non-Goals:**
- Reemplazar al backend Java. Esto es un puente, no la solución final.
- Feedback loop de `merchants` desde la UI. El modal sólo cambia `category_id`. El refuerzo de aprendizaje aterriza con backend Java (TASK-BE-06).
- Crear nuevas migraciones SQL (RLS, columnas, triggers). V002 ya cubre el aislamiento.
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

### D2 — Feedback loop de `merchants` diferido a backend Java

Decisión: el MVP **no** implementa actualización de `myfinance.merchants` cuando el usuario cambia categoría. El modal sólo emite `UPDATE myfinance.transactions SET category_id = $1 WHERE id = $2`. El feedback loop (incrementar `confidence`, `match_count`, `last_confirmed_at`, UPSERT del merchant) queda como **TASK-BE-06** del backend Java.

Alternativas descartadas:
- **Trigger Postgres `tg_transaction_category_feedback`.** Versión original del proposal. Rechazada en adversarial review por: (a) requería tabla `merchants` que no existe en V001/V002/V003, (b) requería columna `category_confirmed` también pendiente, (c) `SECURITY INVOKER` bloqueaba el path n8n / service_role, (d) los semánticos del UPSERT y el cap `LEAST(0.95, …)` introducen drift contra `SPEC.md §7`, (e) crearía split-brain con el futuro backend Java que también va a tocar `merchants` (M8). Pasar la lógica de aprendizaje al trigger ahora requeriría amendments a SPEC.md §7 y un Gate-A decision sobre "ownership" del feedback loop. No vale ese costo para destrabar el MVP esta noche.
- **Update desde el cliente.** Pone lógica de negocio en el frontend. Riesgo: cliente puede saltarse el update de `merchants` (bug, falla de red entre las dos llamadas). Rompe el invariante. Además, no podemos ejecutarlo porque `merchants` no existe todavía.
- **Supabase Edge Function.** Misma deuda que el trigger pero peor — duplicaría código Deno que el backend Java va a reemplazar igual.

Razón: el costo de incluir el feedback loop en el MVP es alto (crear `merchants`, `category_confirmed`, reconciliar SPEC.md §7, manejar split-brain con backend Java). El beneficio es bajo: el sistema ya categoriza vía n8n + LLM; el feedback acelera el aprendizaje pero su ausencia temporal no rompe la operación diaria del usuario. Se gana semanas de tiempo a cambio de un período acotado sin aprendizaje incremental desde la UI.

Documentar en `frontend/AGENTS.md` que cualquier agente posterior que toque el modal NO debe agregar la actualización a `merchants` — eso es responsabilidad de TASK-BE-06.

### D3 — Capa `services/` como única superficie de datos

Decisión: el frontend tiene `src/services/<entity>Service.ts` con métodos públicos (`list`, `get`, `updateCategory`, …). Cada service usa `lib/supabaseClient.ts` internamente. Componentes y pantallas sólo consumen hooks de React Query encima de los services.

Regla complementaria: lint estático (ESLint `no-restricted-imports`) prohíbe importar `@supabase/supabase-js` fuera de `lib/supabaseClient.ts` y de los archivos en `services/`. Adicionalmente, un test estático Vitest globea `src/` y falla si encuentra el import fuera del allowlist.

Alternativas descartadas:
- **Usar `supabase-js` directo en componentes.** Hace imposible la migración futura sin tocar las pantallas. Tentación clásica del "rápido para el MVP" que cuesta caro después.
- **Generar tipos con `supabase gen types` y usarlos crudos.** Acopla la UI al schema de DB (snake_case, sin enriquecimiento). Cuando el backend Java responda DTOs ricos, hay que mapear igual.

Razón: la capa fina es el truco completo para forward compatibility. Migración futura: cambiar el body de los services de `supabase.from(...).select(...)` a `fetch('/api/v1/...')` con el mismo JWT del `useAuth()`. Componentes, hooks, routing, estado — nada cambia.

### D4 — DTOs alineados con futuro `api-spec.yml`

Decisión: los services exponen tipos TS en camelCase, fechas como ISO strings (`occurredAt: string`), montos como string para preservar precisión (`amount: string`, parsed con `Decimal.js` o similar al renderizar). Cuando llegan filas de Supabase en snake_case con `numeric` y `timestamptz`, un mapper privado convierte. El mapper vive dentro del service y se borra cuando llega el backend.

Caveat reconocido por la adversarial review (B6): `docs/api-spec.yml` está vacío hoy salvo `/actuator/health`. El shape DTO de este change es **inventado** contra el cual codificar, no "alineado con un contrato existente". Cuando TASK-BE-04+ puebla `api-spec.yml` con `TransactionDTO`, `AccountDTO`, `CategoryDTO` reales, esos schemas SHALL ser la fuente de verdad y `services/types.ts` SHALL realinearse. Documentar el shape inventado en `frontend/AGENTS.md` para facilitar el diff futuro.

Alternativas descartadas:
- **Usar tipos del schema de Supabase directo.** Acopla la UI al naming de DB; obliga a refactor masivo cuando el backend devuelve otro shape.
- **Tipos genéricos `Record<string, unknown>`.** Mata el valor de TS strict.

Razón: codificar la UI contra un shape DTO desde el día 1 elimina deuda incluso si el shape final cambiará en details. El mapper privado absorbe esos cambios sin tocar consumidores.

### D5 — RLS apoyada en V002 existente, no en migraciones nuevas

Decisión: el MVP consume PostgREST con las políticas RLS de `backend/database/migrations/V002__rls_policies.sql` (ya aplicadas en producción). No se crea ninguna política nueva en este change. Si una tabla requiere ajuste de política, eso es un change separado.

Verificación: tests de integración del frontend (mockeando Supabase con tokens de usuario y verificando el comportamiento del service ante 401/empty rows) cubren el path de aislamiento. Un test E2E manual confirma que un usuario autenticado sólo ve sus propias transacciones (verificable con un segundo usuario seedeado en Supabase Auth — opcional para el MVP si sólo hay un usuario).

Caveat reconocido por la adversarial review (Q4): `categories_select_system_and_own` permite que usuarios autenticados creen/actualicen/borren sus propias categorías custom. El MVP **no** expone UI para esa operación; el frontend sólo hace SELECT sobre `categories`. Si en el futuro la UI permitiera categorías personalizadas, las políticas de V002 ya lo soportan.

Razón: V002 ya cubre el aislamiento que el MVP necesita. Crear una migración duplicada es trabajo cero que falla con "policy already exists" al aplicar. Cualquier ajuste fino (p.ej. forzar `categories` a read-only para el MVP) se hace en un change con su propio scope.

### D6 — Auth via Supabase Auth UI prebuilt

Decisión: usar `@supabase/auth-ui-react` con `@supabase/auth-ui-shared` para el formulario de login (email/password + magic link). Estilizar con el theme alineado a `docs/design/`. Sesión gestionada por `supabase.auth.onAuthStateChange` + Context `useAuth()`. Configurar el componente con `view="sign_in"` (no `sign_up`) para forzar el modo solo-login (decisión D9).

Alternativa descartada:
- **Formulario custom.** No agrega valor en el MVP, alarga el día. Se puede reemplazar después si hace falta UX más fina.

Razón: tiempo. Es la única decisión donde aceptamos atajo de UI; no compromete arquitectura ni forward compat.

### D7 — Vercel deploy con root `frontend/`

Decisión: vincular el monorepo a Vercel con root directory `frontend/`. Build command: `npm run typecheck && npm run lint && npm run test && npm run build` (encadenado para que CI sea un único gate — atiende m9 de la adversarial review). Output: `dist/`. Variables: `VITE_SUPABASE_URL`, `VITE_SUPABASE_ANON_KEY`.

Razón: separar el frontend en proyecto Vercel propio evita acoplar deploys del backend Java cuando llegue. Encadenar typecheck+lint+test en el build command de Vercel evita que un PR rompa main sin que CI lo cache.

### D8 — Modal idempotente sobre `category_id` puro

Decisión: el modal emite `UPDATE myfinance.transactions SET category_id = $1 WHERE id = $2`. No hay flag de confirmación (`category_confirmed` no existe en el schema actual; introducirla requiere V008 que es fuera de scope). Si el usuario abre el modal y pulsa "Confirmar" sin cambiar la categoría seleccionada, el service detecta `selected === current.categoryId` y NO emite la query (corta de raíz). Cierra el modal sin feedback de éxito (silencioso) o muestra "Sin cambios". Reconciliación con la antigua scenario "Confirmación sin cambio" en `frontend-mvp/spec.md`: queda eliminada.

Razón: sin `category_confirmed`, no hay nada que "confirmar". La operación es un cambio de categoría, no una confirmación. La UI debe reflejar esa semántica honestamente. Cuando llegue `category_confirmed` (TASK-BE-06 o posterior), la semántica del modal se actualiza en su propio change.

### D9 — Sólo login en el MVP, no sign-up

Decisión: el MVP es para un solo usuario. El componente `Auth` de Supabase se configura con `view="sign_in"` y `showLinks={false}` para evitar que el tab de Sign Up se renderice. Si en el futuro el operador invita a otro usuario, lo crea manualmente en Supabase Auth (dashboard); la RLS de V002 ya garantiza aislamiento entre usuarios.

Razón: SPEC.md describe "para un usuario". Mostrar Sign Up sería UI desperdiciada y abriría la puerta a registros accidentales.

## Risks / Trade-offs

- **[Riesgo aceptado] Sin feedback loop a `merchants` en el MVP.** Mitigación: documentado en `proposal.md ## Threat model`, en este `design.md D2`, y en `frontend/AGENTS.md`. Cierre en TASK-BE-06.
- **[Riesgo] Drift entre shape DTO inventado y el `api-spec.yml` futuro.** Mitigación: shape documentado en `frontend/AGENTS.md` para diff. Coste de realinear es localizado al mapper privado de cada service.
- **[Riesgo] ESLint rule de aislamiento ignorada por agentes posteriores.** Mitigación: además del lint, escribir un test estático (Vitest + glob) que falle si encuentra `import .* from '@supabase/supabase-js'` fuera del allowlist. El build command de Vercel (D7) corre el test, así que CI bloquea.
- **[Riesgo] Sesión Supabase desincronizada con backend Java cuando entre.** Mitigación: Spring Security debe validar el `iss` y `aud` del JWT exactamente como hoy lo emite Supabase. El JWT secret en backend Java es el mismo que el de Supabase. Documentado en TASK-BE-03.
- **[Riesgo] Vercel anon key expuesta.** Mitigación: la `anon key` es pública por diseño; RLS es la defensa. El JWT secret nunca sale del backend Java (no aplica al MVP — no hay backend Java en producción). Si la key se filtra, no hay daño porque sin sesión válida no se puede leer nada.
- **[Riesgo] JWT robado del navegador del usuario.** Mitigación: el TTL de Supabase Auth es 1h por defecto. Documentar en `docs/development-guide.md` que no se modifique sin justificación. Aceptado en el threat model como costo de habilitar SPA tipo localStorage; mitigación adicional (httpOnly cookies) queda como mejora futura cuando el backend Java emita sus propias cookies.
- **[Trade-off] Sin billing summary en MVP.** El usuario verá una lista de transacciones, no un resumen de ciclo. Aceptamos porque la regla de "frontend no agrega plata" es dura, y los totales relevantes los entregará el backend Java.

## Migration Plan

Dos ramas, en orden de dependencia. (La rama #1 original del proposal — RLS + trigger — quedó eliminada; ver decisión D5.)

1. **`feat/mvp-frontend-scaffold`** (independiente — no depende de cambios de DB)
   - Vite + React 19 + TS strict + Tailwind en `frontend/`.
   - Dependencias: `@supabase/supabase-js`, `@supabase/auth-ui-react`, `@tanstack/react-query`, `react-router`, `vitest`, `@testing-library/react`.
   - Estructura `lib/`, `services/`, `hooks/`, `pages/`, `auth/`.
   - ESLint rule + test estático de aislamiento de `supabase-js`.
   - `frontend/AGENTS.md` documentando reglas (incluye: NO actualizar `merchants` desde la UI; eso es TASK-BE-06).
   - Scripts npm: `typecheck`, `lint`, `test`, `build` — todos verdes.
   - PR a `main`, adversarial review, merge.

2. **`feat/mvp-frontend-screens`** (depende de #1)
   - Implementar las 3 pantallas (login, lista, modal cambio categoría).
   - Tests unitarios y de integración (mockeando supabase-js a nivel de service).
   - Vincular proyecto a Vercel con build command que encadene typecheck+lint+test+build (D7).
   - Deploy productivo y verificación end-to-end con datos reales.
   - PR a `main`, adversarial review, merge.

Rollback:
- Si el deploy en Vercel rompe, revertir el commit en `main` y redeploy. No hay cambios productivos de DB que rollback.
- El backend Java no se ve afectado por estas ramas, así que no tiene rollback que coordinar.

## Open Questions

- ¿El theme del Supabase Auth UI debe matchear el design-system del proyecto desde día 1? Decisión por defecto: theme oscuro mínimo alineado a tokens de `docs/design/design-system.md`. Si queda feo, se itera; no bloquea el deploy.
- ¿Las pantallas del MVP deben mostrar paginación con "total de páginas" o sólo "siguiente/anterior"? Decisión por defecto: sólo siguiente/anterior con `page=N` en URL; el total exige `count=exact` (header extra en PostgREST) y es trivial agregar después si hace falta UX.
- ¿La advertencia en consola por `displayName` null vale la pena en el MVP? Decisión por defecto: drop — la UI muestra el `name` y ya. Cierre de TASK-DB-01 (`display_name` backfill) se hace por separado.
