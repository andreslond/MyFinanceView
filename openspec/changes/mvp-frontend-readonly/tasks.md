## 1. Rama `chore/rls-myfinance-feedback-trigger` — capability `myfinance-data-policies`

- [ ] 1.1 Crear branch `chore/rls-myfinance-feedback-trigger` desde `main` en un worktree dedicado y abrir issue/Notion vinculado
- [ ] 1.2 Identificar el número de migración siguiente (revisar `backend/database/migrations/`) y reservar los `V{n}` y `V{n+1}` para esta rama
- [ ] 1.3 Crear migración `V{n}__rls_myfinance_policies.sql` que habilita RLS y define políticas para `transactions`, `accounts`, `merchants`, `user_settings`, `budgets`, `budget_categories` (user-owned, `auth.uid() = user_id` en USING y WITH CHECK para SELECT/INSERT/UPDATE/DELETE)
- [ ] 1.4 Añadir políticas read-only a `categories`, `banks`, `exchange_rates` (SELECT para `authenticated`; escritura bloqueada para `anon`/`authenticated`, permitida sólo para `service_role`)
- [ ] 1.5 Documentar en comentarios SQL la decisión por tabla y vincular a `myfinance-data-policies` requirements
- [ ] 1.6 Capturar el algoritmo de normalización `raw_pattern` / `normalized_name` desde el workflow n8n y documentarlo en un comentario que vivirá en la siguiente migración
- [ ] 1.7 Crear migración `V{n+1}__tg_transaction_category_feedback.sql` con la función `myfinance.fn_update_merchant_from_transaction()` (SECURITY INVOKER) y el trigger `tg_transaction_category_feedback AFTER UPDATE OF category_id, category_confirmed ON myfinance.transactions WHEN ...`
- [ ] 1.8 Verificar idempotencia de ambas migraciones (`CREATE OR REPLACE`, `DROP TRIGGER IF EXISTS ... ; CREATE TRIGGER ...`, `ALTER TABLE ... ENABLE ROW LEVEL SECURITY` no rompe si ya está habilitada)
- [ ] 1.9 Test de integración (backend Testcontainers o script PostgREST local) — Usuario A no ve filas de Usuario B en `transactions`/`accounts`/`merchants`
- [ ] 1.10 Test de integración — query anónima a `transactions` retorna 401/array vacío
- [ ] 1.11 Test del trigger — confirmar categoría de transacción sin merchant existente crea merchant con `confidence = 0.6`, `match_count = 1`, `last_confirmed_at = now()`
- [ ] 1.12 Test del trigger — confirmar categoría con merchant existente incrementa `confidence` (≤ 0.95), `match_count`, actualiza `last_confirmed_at` y `category_id`
- [ ] 1.13 Test del trigger — re-confirmar la misma categoría (idempotencia) NO incrementa contadores
- [ ] 1.14 Test del trigger — cambio de `category_id` con `category_confirmed = true` también dispara la actualización del merchant
- [ ] 1.15 Test del trigger — `raw_pattern`/`normalized_name` producidos coinciden con la salida del nodo n8n para un set de fixtures conocidos
- [ ] 1.16 Aplicar las migraciones al proyecto Supabase productivo (`akkoqdjmmozyqdfjkabg`) vía MCP `apply_migration`
- [ ] 1.17 Smoke-test productivo — con un usuario de prueba (o el usuario principal), confirmar una transacción y verificar que `merchants` se actualizó como esperado
- [ ] 1.18 Actualizar `progress.md` (último estado, próximo paso)
- [ ] 1.19 Adversarial review (`adversarial-reviewer` agent o skill) — revisar políticas RLS, lógica del trigger, completitud de tests
- [ ] 1.20 Commit con `/commit`, PR a `main`, merge tras review

## 2. Rama `feat/frontend-mvp-scaffold` — capability `frontend-mvp` (estructura)

- [ ] 2.1 Crear branch `feat/frontend-mvp-scaffold` desde `main` en worktree dedicado (depende de #1 mergeada)
- [ ] 2.2 En `frontend/`, ejecutar `npm create vite@latest . -- --template react-ts` (reemplazando el placeholder README); verificar que `npm run dev` arranca
- [ ] 2.3 Configurar TypeScript strict (`strict: true`, `noUncheckedIndexedAccess: true`, `noImplicitOverride: true`) en `tsconfig.json`
- [ ] 2.4 Instalar Tailwind CSS, generar `tailwind.config.cjs` y `postcss.config.cjs`, alinear tokens con `docs/design/design-system.md`
- [ ] 2.5 Instalar dependencias runtime: `@supabase/supabase-js`, `@supabase/auth-ui-react`, `@supabase/auth-ui-shared`, `@tanstack/react-query`, `react-router`, `react-router-dom`
- [ ] 2.6 Instalar dependencias dev: `vitest`, `@testing-library/react`, `@testing-library/jest-dom`, `jsdom`, `eslint`, `@typescript-eslint/*`, plugin Tailwind para ESLint si aplica
- [ ] 2.7 Crear `src/lib/supabaseClient.ts` que exporta una única instancia de `createClient(VITE_SUPABASE_URL, VITE_SUPABASE_ANON_KEY)`
- [ ] 2.8 Crear `src/auth/AuthContext.tsx` con `useAuth()` que expone `session`, `user`, `signOut`; suscripción a `supabase.auth.onAuthStateChange`
- [ ] 2.9 Crear `src/auth/RequireAuth.tsx` (route guard) que redirige a `/login` cuando no hay sesión
- [ ] 2.10 Crear `src/services/types.ts` con DTOs camelCase (TransactionDTO, AccountDTO, CategoryDTO, MerchantDTO) alineados con `docs/api-spec.yml`
- [ ] 2.11 Crear `src/services/transactionsService.ts` con `list(filters): Promise<Page<TransactionDTO>>` y `updateCategory(id, categoryId): Promise<TransactionDTO>`; mapper privado `supabaseRowToDTO`
- [ ] 2.12 Crear `src/services/accountsService.ts` con `list(): Promise<AccountDTO[]>`
- [ ] 2.13 Crear `src/services/categoriesService.ts` con `list(): Promise<CategoryDTO[]>`
- [ ] 2.14 Configurar `QueryClient` de React Query en `src/main.tsx` con defaults sensatos (staleTime, retry policy)
- [ ] 2.15 Crear hooks `src/hooks/useTransactions.ts`, `useAccounts.ts`, `useCategories.ts`, `useUpdateTransactionCategory.ts` (mutation con invalidation)
- [ ] 2.16 Configurar ESLint `no-restricted-imports` rule que prohíbe `@supabase/supabase-js` fuera de `src/lib/supabaseClient.ts` y `src/services/**`
- [ ] 2.17 Crear test estático `src/__tests__/isolation.spec.ts` (Vitest) que globea `src/` buscando imports de `@supabase/supabase-js`; falla si encuentra fuera del allowlist
- [ ] 2.18 Crear `frontend/AGENTS.md` documentando reglas (aislamiento de supabase-js, prohibición de agregaciones, estructura de carpetas, mapeo a `api-spec.yml`)
- [ ] 2.19 Configurar scripts `npm run typecheck`, `npm run lint`, `npm run test`, `npm run build` y verificar que todos pasan
- [ ] 2.20 Actualizar `progress.md`
- [ ] 2.21 Adversarial review — revisar estructura, aislamiento, DTOs, AGENTS.md
- [ ] 2.22 Commit con `/commit`, PR a `main`, merge

## 3. Rama `feat/frontend-mvp-screens-and-deploy` — capability `frontend-mvp` (pantallas + deploy)

- [ ] 3.1 Crear branch `feat/frontend-mvp-screens-and-deploy` desde `main` en worktree dedicado (depende de #2 mergeada)
- [ ] 3.2 Implementar `src/pages/LoginPage.tsx` usando `@supabase/auth-ui-react` con tema oscuro mínimo alineado a `docs/design/design-system.md`; soporta email/password y magic link
- [ ] 3.3 Test unitario LoginPage — render OK, link/form visibles
- [ ] 3.4 Implementar `src/pages/TransactionsPage.tsx` con tabla, filtros (cuenta dropdown, categorías multi-select, estado de confirmación), paginación, estado de carga, estado vacío
- [ ] 3.5 Hookear filtros a query params de URL (uso de `useSearchParams` de react-router) — agregar/quitar filtros actualiza la URL y viceversa
- [ ] 3.6 Test unitario TransactionsPage — render con datos mock, filtros aplican, paginación, estado vacío
- [ ] 3.7 Test de integración TransactionsPage — link compartible (URL con filtros) reproduce mismo resultado tras recarga
- [ ] 3.8 Implementar `src/components/CategoryChangeModal.tsx` — modal con dropdown de categorías ordenado por `displayName`, botón Confirmar/Cancelar, feedback visual de éxito/error
- [ ] 3.9 Cablear el modal a `useUpdateTransactionCategory` — confirmar dispara mutation, invalida queries de transacciones, cierra modal con feedback
- [ ] 3.10 Test unitario CategoryChangeModal — apertura, selección, confirmación happy path, error de red preserva selección
- [ ] 3.11 Test de integración end-to-end (mockeando Supabase) — abrir modal, confirmar, verificar que el service fue llamado con el `categoryId` correcto y `categoryConfirmed: true`
- [ ] 3.12 Verificar fallback de `displayName` null → muestra `name` y warning en consola
- [ ] 3.13 Configurar `vercel.json` o equivalente con root directory `frontend/`, build command, output directory
- [ ] 3.14 Verificar cuenta Vercel del usuario (`aftorresl01@gmail.com`) vía MCP `list_teams` y `list_projects`
- [ ] 3.15 Crear proyecto Vercel vinculado al repo; configurar variables de entorno `VITE_SUPABASE_URL` y `VITE_SUPABASE_ANON_KEY`
- [ ] 3.16 Trigger primer deploy desde el branch via Vercel MCP `deploy_to_vercel`
- [ ] 3.17 Smoke-test post-deploy — `curl` a la URL productiva responde HTTP 200; login con el usuario real funciona; lista de transacciones muestra datos reales
- [ ] 3.18 End-to-end manual — cambiar categoría a una transacción real, verificar en Supabase que `merchants` se actualizó (cierra AC-07 cross-capability)
- [ ] 3.19 Documentar la URL productiva, las credenciales y las variables de entorno en `docs/development-guide.md` (sección "MVP deploy")
- [ ] 3.20 Actualizar `progress.md`
- [ ] 3.21 Adversarial review — revisar pantallas, integración real con Supabase, deploy
- [ ] 3.22 Commit con `/commit`, PR a `main`, merge

## 4. Archive y sincronización de specs

- [ ] 4.1 Una vez las 3 ramas estén en `main`, ejecutar `/opsx:archive` para el change `mvp-frontend-readonly`
- [ ] 4.2 Ejecutar `/openspec-sync-specs` para mergear los deltas a `openspec/specs/frontend-mvp/` y `openspec/specs/myfinance-data-policies/`
- [ ] 4.3 Ejecutar `/update-docs` para actualizar `SPEC.md`, `docs/frontend-standards.md` (decisiones pendientes resueltas), `docs/data-model.md` (RLS + trigger documentados), `CLAUDE.md` si hace falta, y reflejar el estado en la página Notion del proyecto
