## Why

Hay 362+ transacciones cargándose diariamente vía n8n en `myfinance.*`, pero el usuario no tiene forma de consultarlas, filtrarlas ni corregir categorías sin tocar la base de datos directamente. El backend Java de Épica 3 está en scaffolding y TASK-BE-03+ (Spring Security JWT, endpoints) no se entregarán en semanas, así que esperar bloquearía el lanzamiento. Necesitamos un MVP desplegable esta misma noche que demuestre el sistema con datos reales y que no introduzca trabajo desechable: la capa de datos debe ser reemplazable por el backend Java sin tocar componentes.

## What Changes

- Frontend Vite + React 19 + TypeScript + Tailwind desplegado en Vercel, hablando directo a Supabase Postgres vía `supabase-js`.
- 3 pantallas: login Supabase, lista de transacciones más recientes con filtros (cuenta, categoría), modal de cambio de categoría.
- Auth completamente delegada a Supabase Auth (email/password + magic link). JWT en cada query a PostgREST; mismo JWT reusable cuando el backend Java entre.
- Capa `services/` en el frontend que abstrae el fetcher. Componentes nunca importan `supabase-js` directo. DTOs en camelCase, ISO strings, monto como string para preservar precisión; cuando aterrice `docs/api-spec.yml` con los schemas reales el mapper se alinea (ver `## Forward-compat note`).
- Mapper `supabaseRowToDTO()` desechable. Cuando el backend Java entre, el cuerpo del service cambia de `supabase.from(...)` a `fetch('/api/v1/...')` sin tocar componentes.
- Cero agregaciones de plata en frontend. Cualquier total agregado va como vista SQL en Supabase, no como `.reduce()` en JS.
- Pipeline de deploy en Vercel con env vars `VITE_SUPABASE_URL` y `VITE_SUPABASE_ANON_KEY`.
- **El feedback loop de `merchants` queda diferido a backend Java (Épica 3, TASK-BE-06).** El modal del MVP sólo cambia `category_id` en `transactions`; no incrementa confianza ni hace UPSERT de merchants. Es un trade-off explícito para destrabar el lanzamiento — ver `## Threat model` y `design.md` D2.

## Capabilities

### New Capabilities

- `frontend-mvp`: pantallas read-only-más-categoría del MVP, capa `services/` que abstrae el fetcher para forward compatibility con el backend Java, estructura de carpetas y reglas de aislamiento de `supabase-js`, contrato de DTOs frontend, integración con Vercel.

### Modified Capabilities

(ninguna — `backend-runtime` y `harness-progress-tracking` no cambian comportamiento spec-level)

## Threat model

**Adversary considerado en este MVP:**
- Atacante web oportunista que obtiene la `VITE_SUPABASE_ANON_KEY` del bundle (es pública por diseño en Supabase) e intenta leer/mutar datos sin sesión válida.
- Atacante con un JWT robado (vía XSS, dispositivo compartido, almacenamiento del navegador comprometido temporalmente) que intenta leer transacciones de otros usuarios o mutar `category_id` masivamente del usuario víctima.
- Cliente confundido (UI bug, doble click, race) que pudiera generar UPDATE inconsistentes contra `transactions`.

**Defensas que esta propuesta sostiene:**
- RLS de `myfinance` ya activa via `backend/database/migrations/V002__rls_policies.sql` (políticas `*_select_own`, `*_update_own`, etc. por `auth.uid() = user_id` en todas las tablas user-owned). Anon key sin JWT válido devuelve 0 filas.
- JWT TTL de Supabase Auth (1h por defecto). Documentar la configuración en `docs/development-guide.md` al cierre del change.
- Aislamiento de `@supabase/supabase-js` en `lib/` + `services/` reduce la superficie de XSS-exfil del JWT.
- ESLint rule + test estático (Vitest glob) impiden que un agente futuro pegue una llamada cruda en un componente.

**Fuera de alcance (auto-rechazado por triage):**
- Compromiso del laptop del operador (endpoint malware, keylogger).
- Compromiso del proveedor Supabase / Vercel (insider, plataforma).
- Side-channels criptográficos sobre el JWT.
- Adversario de red sobre HTTPS (TLS lo cubre).
- Ataques sobre el flujo de magic link de Supabase Auth (responsabilidad del proveedor de email + Supabase).
- Vulnerabilidades del backend Java (no existe aún en producción).

**Trade-off aceptado por threat model:** sin trigger Postgres en el MVP, un cambio de categoría desde la UI **no** actualiza `myfinance.merchants`. El sistema deja de aprender hasta que llegue el backend Java (TASK-BE-06). Aceptable porque el feedback loop existente vía n8n + LLM sigue ingiriendo y categorizando transacciones nuevas con la lógica acumulada hasta hoy; sólo se difiere el refuerzo "el usuario corrigió X → la próxima sea Y".

## Impact

- **Código nuevo:** `frontend/` deja de ser placeholder; nace estructura Vite + servicios + hooks + pantallas. (`backend/database/migrations/` no recibe nada nuevo en este change — V002 ya cubre RLS y `merchants`/`category_confirmed` los traerá el change de backend Java.)
- **Supabase project (`akkoqdjmmozyqdfjkabg`):** sin cambios de schema, sin nuevas migraciones, sin nuevas políticas — sólo se consume PostgREST con la RLS de V002.
- **Vercel:** primer proyecto del usuario en su cuenta, vinculado al monorepo con root `frontend/`. Variables de entorno configuradas.
- **CLAUDE.md y SPEC.md:** referencias futuras al frontend MVP (no requieren cambio hasta archivado del change).
- **`docs/frontend-standards.md`:** este change activa parcialmente las decisiones pendientes de §3 (TypeScript strict, React Router, React Query, Vitest); se completarán en `/update-docs` al archivar.
- **n8n:** no se ve afectado; sigue ingiriendo y categorizando como hoy.
- **`supabase-backup-policy` change:** si ese change está en `main` al momento del deploy del MVP, esta propuesta no toca producción de DB (sólo lee), por lo que no requiere snapshot pre-op. Si quedara pendiente, igual queda fuera de la ruta crítica.
- **Deuda diferida explícitamente:** feedback loop a `merchants`, columna `category_confirmed` en `transactions`, trigger del invariante, billing summary, breakdown por categoría, cuotas, gestión de merchants, pending-review queue, dashboards y notificaciones — quedan para Épica 3 / Épica 5. La feedback-loop deuda específica se cierra con TASK-BE-06.
- **Sin breaking changes** porque no hay nada productivo aún del lado UI.

## Forward-compat note

`docs/api-spec.yml` hoy sólo declara `/actuator/health`. El frontend del MVP inventa el shape DTO contra el cual codificar (camelCase, ISO strings, monto string). Cuando el backend Java aterrice TASK-BE-04+ y pueble `api-spec.yml` con los schemas reales de `TransactionDTO`, `AccountDTO`, `CategoryDTO`, esos schemas SHALL ser la fuente de verdad y el mapper privado de los services SHALL reescribirse para producir exactamente ese shape. Mientras tanto, el shape inventado por este change debe quedar documentado en `frontend/AGENTS.md` para que un futuro agente pueda diff-earlo contra el `api-spec.yml` ya aterrizado.
