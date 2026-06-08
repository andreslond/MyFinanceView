# Closing note for `mvp-frontend-readonly` (paste into that change's `notes/`)

> Esta nota es un duplicado intencional. Vive aquí porque el operador la firmó al mismo
> tiempo que se abrió `backend-mvp-readonly`. Cuando se haga el `/opsx:archive` de
> `mvp-frontend-readonly`, esta nota debe quedar bajo
> `openspec/changes/archive/<fecha>-mvp-frontend-readonly/notes/closing.md`.

## Estado al cierre

- **Código entregado:** `feat/mvp-frontend-scaffold` (que incluye `feat/mvp-frontend-screens` al mismo SHA) — Vite + React 19 + TS strict + Tailwind + React Query + Supabase Auth + Vercel deploy.
- **Tareas cubiertas:** secciones 1.x (scaffold) y 2.x (pantallas) de `tasks.md`.
- **Tarea NO cubierta:** sección 3.x (archive/sync/update-docs) — el operador la difiere a las acciones del backend MVP que vienen a continuación.

## Decisión D1 superseded por `backend-mvp-readonly`

`design.md` D1 dijo: "Frontend habla directo a Supabase via `supabase-js` y RLS de V002".

Esa decisión fue un puente para lanzar el MVP esa misma noche, con la deuda explícita de:
- Sin feedback loop a `myfinance.merchants` (D2 difirió a TASK-BE-06).
- DTO shape inventado (D4 caveat).
- Imposibilidad de agregar billing summary / breakdown sin agregaciones en frontend (Non-Goal).

Para que la D1 funcionara en runtime, el operador tendría que haber:
1. Expuesto `myfinance` en PostgREST (Dashboard → Project Settings → API → Exposed schemas).
2. GRANT-eado USAGE/SELECT/UPDATE a roles `anon`/`authenticated` sobre las tablas de `myfinance`.

**El operador rechaza ambos pasos retroactivamente.** Razón: la única ruta sostenible a las tablas de `myfinance` es vía backend Java con `service_role` y `WHERE user_id = ?` en código jOOQ. Cualquier exposición directa a `anon`/`authenticated` rompe el modelo de aislamiento que la Épica 3 va a sostener.

## El 403 actual es estado terminal documentado

El frontend desplegado en Vercel responde 403 a `GET /rest/v1/{transactions,accounts,categories}` porque el schema `myfinance` no está expuesto en PostgREST. **NO se va a arreglar con un toggle.** El fix oficial es el change `backend-mvp-readonly`:

```
frontend (Vercel)  →  backend Java REST  →  Supabase (service_role + WHERE user_id = ?)
```

Hasta que `backend-mvp-readonly` + `frontend-swap-to-backend` aterricen y el backend tenga deploy productivo, **el frontend desplegado permanece con 403 a propósito**. Costo aceptado.

## Acciones pendientes (operativas, no spec)

- [ ] Mergear `feat/mvp-frontend-scaffold` a `main` (PR pendiente; `gh pr create --base main --head feat/mvp-frontend-scaffold`).
- [ ] `/opsx:archive mvp-frontend-readonly` para mover el change a `openspec/changes/archive/2026-06-01-mvp-frontend-readonly/`.
- [ ] `/openspec-sync-specs frontend-mvp` para mergear el delta de `specs/frontend-mvp/` a la canonical `openspec/specs/frontend-mvp/`.
- [ ] `/update-docs` — refleja en `SPEC.md`, `docs/frontend-standards.md`, `CLAUDE.md` y Notion que el MVP frontend está cerrado y que la arquitectura final es backend-mediada.

## Worktrees afectados

- `mvp-frontend-scaffold` (branch `feat/mvp-frontend-scaffold`): contiene el código + docs + Vercel config. **Es lo que se mergea a `main`.**
- `mvp-frontend-screens` (branch `feat/mvp-frontend-screens`): mismo SHA que scaffold; superseded; eliminable post-merge.
- `mvp-frontend-readonly-propose` (branch `feat/mvp-frontend-readonly-propose`): solo docs OpenSpec, ya incluidos en scaffold; eliminable post-merge.

## Memoria asociada

Ver memoria `project_backend_only_path_to_myfinance_schema` — la decisión arquitectónica de que `myfinance` solo se accede vía backend con `service_role`.
