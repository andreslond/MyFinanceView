## Why

Hay 362+ transacciones cargándose diariamente vía n8n en `myfinance.*`, pero el usuario no tiene forma de consultarlas, filtrarlas ni corregir categorías sin tocar la base de datos directamente. El backend Java de Épica 3 está en scaffolding y TASK-BE-03+ (Spring Security JWT, endpoints) no se entregarán en semanas, así que esperar bloquearía el lanzamiento. Necesitamos un MVP desplegable esta misma noche que demuestre el sistema con datos reales y que no introduzca trabajo desechable: la capa de datos debe ser reemplazable por el backend Java sin tocar componentes.

## What Changes

- Frontend Vite + React 19 + TypeScript + Tailwind desplegado en Vercel, hablando directo a Supabase Postgres vía `supabase-js`.
- 3 pantallas: login Supabase, lista de transacciones del ciclo actual con filtros (cuenta, categoría, estado de confirmación), modal de cambio de categoría.
- Auth completamente delegada a Supabase Auth (email/password + magic link). JWT en cada query a PostgREST; mismo JWT reusable cuando el backend Java entre.
- Políticas Row Level Security (`SELECT/UPDATE/INSERT/DELETE` por `auth.uid() = user_id`) en `myfinance.transactions`, `accounts`, `categories`, `merchants`. Catálogos compartidos (`banks`) sólo `SELECT` autenticado.
- **Trigger Postgres `myfinance.tg_transaction_category_feedback`** que dispara el feedback loop a `myfinance.merchants` cuando `category_confirmed` transiciona a `true`: incrementa `confidence`, `match_count`, actualiza `last_confirmed_at`, hace UPSERT del merchant si no existe. Lógica de negocio en la DB para que sobreviva o sea reemplazada por el backend Java sin afectar al frontend.
- Capa `services/` en el frontend que abstrae el fetcher. Componentes nunca importan `supabase-js` directo. DTOs alineados con `docs/api-spec.yml` (camelCase, ISO strings, BigDecimal-as-string). Mapper `supabaseRowToDTO()` desechable.
- Cero agregaciones de plata en frontend. Cualquier total agregado va como vista SQL en Supabase, no como `.reduce()` en JS.
- Pipeline de deploy en Vercel con env vars `VITE_SUPABASE_URL` y `VITE_SUPABASE_ANON_KEY`.

## Capabilities

### New Capabilities

- `frontend-mvp`: pantallas read-only-más-categoría del MVP, capa `services/` que abstrae el fetcher para forward compatibility con el backend Java, estructura de carpetas y reglas de aislamiento de `supabase-js`, contrato de DTOs frontend alineado con `api-spec.yml`, integración con Vercel.
- `myfinance-data-policies`: políticas RLS por tabla en el esquema `myfinance`, trigger `tg_transaction_category_feedback` que mantiene el invariante de aprendizaje de `merchants` cuando el usuario confirma una categoría, migraciones SQL versionadas.

### Modified Capabilities

(ninguna — `backend-runtime` y `harness-progress-tracking` no cambian comportamiento spec-level)

## Impact

- **Código nuevo:** `frontend/` deja de ser placeholder; nace estructura Vite + servicios + hooks + pantallas.
- **Migraciones SQL:** nuevos archivos `V{n}__rls_myfinance.sql` y `V{n+1}__tg_category_feedback.sql` en `backend/database/migrations/`.
- **Supabase project (`akkoqdjmmozyqdfjkabg`):** aplicación de RLS y del trigger en el ambiente productivo vía Supabase MCP — sin afectar al backend Java porque toca solo políticas y trigger, no schema de columnas.
- **Vercel:** primer proyecto del usuario en su cuenta, vinculado al monorepo con root `frontend/`. Variables de entorno configuradas.
- **CLAUDE.md y SPEC.md:** referencias futuras al frontend MVP (no requieren cambio hasta archivado del change).
- **`docs/frontend-standards.md`:** este change activa parcialmente las decisiones pendientes de §3 (TypeScript strict, React Router, React Query, Vitest); se completarán en `/update-docs` al archivar.
- **n8n:** no se ve afectado; ya escribe en Supabase y el trigger no rompe su flujo (el trigger se activa por updates, no por inserts de n8n).
- **Deuda diferida explícitamente:** billing summary, breakdown por categoría, cuotas, gestión de merchants, pending-review queue, dashboards y notificaciones quedan para Épica 3 / Épica 5 — no entran en este MVP.
- **Sin breaking changes** porque no hay nada productivo aún del lado UI.
