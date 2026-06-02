## 1. Rama `feat/mvp-frontend-scaffold` — capability `frontend-mvp` (estructura)

- [ ] 1.1 Crear branch `feat/mvp-frontend-scaffold` desde `feat/mvp-frontend-readonly-propose` (que ya tiene los specs corregidos) en un worktree dedicado
- [ ] 1.2 En `frontend/`, ejecutar `npm create vite@latest . -- --template react-ts` (reemplazando el placeholder README); verificar que `npm run dev` arranca
- [ ] 1.3 Configurar TypeScript strict (`strict: true`, `noUncheckedIndexedAccess: true`, `noImplicitOverride: true`) en `tsconfig.json`
- [ ] 1.4 Instalar Tailwind CSS, generar `tailwind.config.cjs` y `postcss.config.cjs`, alinear tokens con `docs/design/design-system.md` (mapeo Tailwind ↔ tokens documentado en `frontend/AGENTS.md`)
- [ ] 1.5 Instalar dependencias runtime: `@supabase/supabase-js`, `@supabase/auth-ui-react`, `@supabase/auth-ui-shared`, `@tanstack/react-query`, `react-router-dom`
- [ ] 1.6 Instalar dependencias dev: `vitest`, `@testing-library/react`, `@testing-library/jest-dom`, `jsdom`, `eslint`, `@typescript-eslint/*`
- [ ] 1.7 Crear `src/lib/supabaseClient.ts` que exporta una única instancia de `createClient(VITE_SUPABASE_URL, VITE_SUPABASE_ANON_KEY)`
- [ ] 1.8 Crear `src/auth/AuthContext.tsx` con `useAuth()` que expone `session`, `user`, `signOut`; suscripción a `supabase.auth.onAuthStateChange`
- [ ] 1.9 Crear `src/auth/RequireAuth.tsx` (route guard) que redirige a `/login` cuando no hay sesión
- [ ] 1.10 Crear `src/services/types.ts` con DTOs camelCase (`TransactionDTO`, `AccountDTO`, `CategoryDTO`) — montos como string, fechas ISO. Documentar el shape inventado en `frontend/AGENTS.md` para diff futuro contra `docs/api-spec.yml`
- [ ] 1.11 Crear `src/services/transactionsService.ts` con `list(filters): Promise<Page<TransactionDTO>>` y `updateCategory(id, categoryId): Promise<TransactionDTO>` (sólo `category_id`, sin `category_confirmed`); mapper privado `supabaseRowToDTO`
- [ ] 1.12 Crear `src/services/accountsService.ts` con `list(): Promise<AccountDTO[]>`
- [ ] 1.13 Crear `src/services/categoriesService.ts` con `list(): Promise<CategoryDTO[]>` ordenado por `display_name` ASC (con fallback a `name` cuando `display_name` es null)
- [ ] 1.14 Configurar `QueryClient` de React Query en `src/main.tsx` con defaults sensatos (`staleTime: 30s`, `retry: 1`)
- [ ] 1.15 Crear hooks `src/hooks/useTransactions.ts`, `useAccounts.ts`, `useCategories.ts`, `useUpdateTransactionCategory.ts` (mutation con invalidation de la query de transacciones)
- [ ] 1.16 Configurar ESLint `no-restricted-imports` rule que prohíbe `@supabase/supabase-js` fuera de `src/lib/supabaseClient.ts` y `src/services/**`
- [ ] 1.17 Crear test estático `src/__tests__/isolation.spec.ts` (Vitest) que globea `src/` buscando imports de `@supabase/supabase-js`; falla si encuentra fuera del allowlist
- [ ] 1.18 Crear `frontend/AGENTS.md` documentando reglas:
  - Aislamiento de `supabase-js` (lib/ + services/ únicos puntos de import)
  - Prohibición de agregaciones monetarias en frontend
  - Estructura de carpetas (`lib/`, `services/`, `hooks/`, `pages/`, `auth/`, `components/`)
  - Shape inventado de DTOs (link al `services/types.ts`)
  - **No actualizar `merchants` desde la UI — eso es TASK-BE-06 del backend Java**
  - Mapeo Tailwind ↔ tokens de `docs/design/design-system.md`
- [ ] 1.19 Configurar scripts `npm run typecheck`, `npm run lint`, `npm run test`, `npm run build` y verificar que todos pasan en verde
- [ ] 1.20 Actualizar `progress.md`
- [ ] 1.21 Adversarial review — revisar estructura, aislamiento, DTOs, AGENTS.md, threat model declarado en proposal
- [ ] 1.22 Commit con `/commit`, PR a `main`, merge

## 2. Rama `feat/mvp-frontend-screens` — capability `frontend-mvp` (pantallas + deploy)

- [ ] 2.1 Crear branch `feat/mvp-frontend-screens` desde `feat/mvp-frontend-scaffold` en worktree dedicado (depende de #1 mergeada — o branched desde su HEAD si el merge a main aún no aterrizó)
- [ ] 2.2 Implementar `src/pages/LoginPage.tsx` usando `@supabase/auth-ui-react` configurado con `view="sign_in"` y `showLinks={false}` (D9 del design.md), tema oscuro mínimo alineado a `docs/design/design-system.md`; soporta email/password y magic link
- [ ] 2.3 Test unitario LoginPage — render OK, form visible, NO se renderiza tab de Sign Up, magic link button visible. (Test de "llega el email" queda como smoke manual, no en CI — M5 del adversarial review.)
- [ ] 2.4 Implementar `src/pages/TransactionsPage.tsx` con tabla, filtros (cuenta dropdown, categorías multi-select), paginación servidor-side, estado de carga, estado vacío. Sin filtro de "confirmación" (no existe `category_confirmed`).
- [ ] 2.5 Hookear filtros a query params de URL (uso de `useSearchParams` de react-router) — agregar/quitar filtros actualiza la URL y viceversa
- [ ] 2.6 Test unitario TransactionsPage — render con datos mock vía service, filtros aplican, paginación, estado vacío
- [ ] 2.7 Test de integración TransactionsPage — link compartible (URL con filtros) reproduce mismo resultado tras recarga
- [ ] 2.8 Implementar `src/components/CategoryChangeModal.tsx` — modal con dropdown de categorías ordenado por `displayName` ascendente (fallback a `name` si null), botón Confirmar/Cancelar, feedback visual de éxito/error
- [ ] 2.9 Cablear el modal a `useUpdateTransactionCategory` — confirmar dispara mutation que emite `UPDATE myfinance.transactions SET category_id = $1 WHERE id = $2` (sin tocar `category_confirmed`, ver D8 del design.md), invalida queries de transacciones, cierra modal con feedback
- [ ] 2.10 Modal idempotente — si el usuario selecciona la misma categoría actual y pulsa Confirmar, el service NO emite query y el modal se cierra silenciosamente o muestra "Sin cambios"
- [ ] 2.11 Test unitario CategoryChangeModal — apertura, selección, confirmación happy path, error de red preserva selección, idempotencia (mismo `categoryId` no llama service)
- [ ] 2.12 Test de integración end-to-end (mockeando Supabase) — abrir modal, confirmar, verificar que el service fue llamado con el `categoryId` correcto y SIN `categoryConfirmed`
- [ ] 2.13 Verificar fallback de `displayName` null → muestra `name` técnico (sin warning en consola — Q5 del adversarial review)
- [ ] 2.14 Configurar `vercel.json` (en `frontend/`) con `rewrites` para SPA (`{ "source": "/(.*)", "destination": "/" }`) y build command que encadene `npm run typecheck && npm run lint && npm run test && npm run build` (D7 + m9 del adversarial review)
- [ ] 2.15 Verificar cuenta Vercel del usuario vía MCP `list_teams` y `list_projects`
- [ ] 2.16 Crear proyecto Vercel vinculado al repo; configurar variables de entorno `VITE_SUPABASE_URL` y `VITE_SUPABASE_ANON_KEY` (valores recuperados vía Supabase MCP `get_project_url` + `get_publishable_keys`); precondición: MCP authenticated, verificar con `list_projects` que retorna el proyecto esperado (m7)
- [ ] 2.17 Trigger primer deploy desde el branch via Vercel MCP `deploy_to_vercel`
- [ ] 2.18 Smoke-test post-deploy — `curl` a la URL productiva responde HTTP 200; login con el usuario real funciona; lista de transacciones retorna ≥ 25 filas con `occurredAt` reciente (en los últimos 7 días, atendiendo m4 del adversarial review)
- [ ] 2.19 End-to-end manual — cambiar categoría a una transacción real, verificar en Supabase Studio (SQL Editor) que `transactions.category_id` cambió; NO se valida `merchants` porque está fuera de scope (D2)
- [ ] 2.20 Documentar en `docs/development-guide.md` (sección "MVP frontend deploy") la URL productiva, los **nombres** de las variables de entorno requeridas (NUNCA los valores — m2 del adversarial review), y el procedimiento de redeploy
- [ ] 2.21 Actualizar `progress.md`
- [ ] 2.22 Adversarial review — revisar pantallas, integración real con Supabase, deploy
- [ ] 2.23 Commit con `/commit`, PR a `main`, merge

## 3. Archive y sincronización de specs

- [ ] 3.1 Una vez las 2 ramas estén en `main`, ejecutar `/opsx:archive` para el change `mvp-frontend-readonly`
- [ ] 3.2 Ejecutar `/openspec-sync-specs` para mergear los deltas a `openspec/specs/frontend-mvp/`
- [ ] 3.3 Ejecutar `/update-docs` para actualizar `SPEC.md`, `docs/frontend-standards.md` (decisiones pendientes resueltas), `CLAUDE.md` (frontend ya no es placeholder), y reflejar el estado en la página Notion del proyecto. NO documentar `merchants` aquí — eso se cierra con TASK-BE-06.
