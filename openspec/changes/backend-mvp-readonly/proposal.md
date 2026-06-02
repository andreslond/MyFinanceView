## Why

El MVP frontend ya está desplegado en Vercel (`feat/mvp-frontend-scaffold` + `feat/mvp-frontend-screens`) y habla directo a Supabase PostgREST con la decisión D1 del change `mvp-frontend-readonly` ("Camino A"). Ese camino fue elegido para destrabar el lanzamiento esa misma noche, pero acarrea deuda explícita:

1. **No hay feedback loop a `myfinance.merchants`** — el cambio de categoría desde la UI no refuerza el aprendizaje del sistema. Diferido a TASK-BE-06 del backend Java (ver `mvp-frontend-readonly/design.md` D2).
2. **No hay agregaciones de plata** — billing summary, breakdown por categoría, totales de ciclo, proyección de cierre quedan fuera del MVP frontend porque `docs/base-standards.md §2` prohíbe agregaciones en frontend, y crearlas como vistas SQL en Supabase introduce lógica de negocio en la DB que el backend Java va a tener que reescribir.
3. **Lógica de negocio en RLS** — la separación usuario↔usuario depende de `auth.uid() = user_id` en V002. Cualquier filtro o regla más rica (e.g. compartir cuentas entre usuarios) requeriría más policies SQL — peor mantenibilidad que código Java versionado.
4. **Schema exposure rechazada retroactivamente por arquitectura.** Para que el MVP frontend pudiera leer datos directamente tendría que haberse expuesto `myfinance` en PostgREST + GRANT explícito a roles `anon`/`authenticated`. **El operador rechaza esa decisión.** La única ruta sostenible a las tablas de `myfinance` es vía backend con `service_role` y filtrado por `userId` en código jOOQ (defensa equivalente a RLS, controlada en Java, auditable por test). En consecuencia: el 403 que devuelve hoy el frontend desplegado en Vercel **NO** es un bug a arreglar con un toggle del dashboard — es el gating intencional que destrabará este backend MVP al aterrizar. El change `mvp-frontend-readonly` se cierra con ese 403 como estado terminal documentado.

Este change entrega el corte mínimo del backend Java REST que permite **swap-out** del camino directo a Supabase desde el frontend ya desplegado, sin tocar componentes ni hooks: cambia solo el body de `src/services/*.ts` de `supabase.from(...)` a `fetch('/api/v1/...')` con el mismo JWT. Cubre los 4 endpoints que el MVP frontend ya usa y el feedback loop a `merchants` que el MVP frontend explícitamente difirió. Hasta que este change + el subsiguiente `frontend-swap-to-backend` aterricen y el backend tenga deploy productivo, **el frontend en Vercel permanece con 403 a propósito** — el operador lo acepta como costo de no introducir un toggle de schema-exposure que va contra el modelo arquitectónico final.

## What Changes

- **Capability nueva `backend-rest-api`** sobre el `backend-runtime` scaffolded en `2026-05-13-backend-scaffolding`:
  - **Auth:** Spring Security filter que valida JWT emitidos por Supabase Auth (mismo `JWT_SECRET`, `iss=https://akkoqdjmmozyqdfjkabg.supabase.co/auth/v1`, `aud=authenticated`). Extrae `sub` como `userId: UUID` disponible vía `@AuthenticationPrincipal`. Sin sesión propia del backend.
  - **GET /api/v1/transactions** — paginado cursor-less con `hasMore: boolean`, filtros `accountId`, `categoryIds` (CSV), `page`, `pageSize` (default 25, max 100). Orden `occurred_at DESC, id DESC` para estabilidad. Sin `count=exact`.
  - **GET /api/v1/accounts** — orden `name ASC`.
  - **GET /api/v1/categories** — system (`user_id IS NULL`) + own merged, orden `display_name ASC` con fallback a `name`.
  - **PATCH /api/v1/transactions/{id}/category** body `{categoryId}` — emite el `UPDATE` Y el **feedback loop a `myfinance.merchants`** (incrementa `confidence`, `match_count`, `last_confirmed_at`; UPSERT del merchant si la transacción no tenía `merchant_id` resuelto). Idempotente: si `categoryId === current.categoryId` devuelve 200 sin tocar DB. Transaccional: el UPDATE de `transactions` y el UPSERT de `merchants` viven en una sola transacción.
  - **GET /actuator/health** — ya existe (no se modifica).
- **DTOs** `TransactionDTO`, `AccountDTO`, `CategoryDTO`, `Page<T>` como Java records inmutables. Campos en camelCase, fechas como `OffsetDateTime` serializadas como ISO 8601 strings, montos `BigDecimal` (Jackson + `WRITE_BIGDECIMAL_AS_PLAIN` + serializer a string), UUIDs como string. Shape alineado al inventado por el MVP frontend (ver `frontend/AGENTS.md`) — el mapper privado del frontend desaparece tras la migración.
- **`docs/api-spec.yml`** pasa de stub-only-health a contener los 4 schemas y los 4 endpoints; CI gate ya queda preparado para diff-arlo contra los handlers (siguiente change).
- **Migración V004** crea `myfinance.merchants` (tabla más `display_name`, `raw_pattern`, `category_id`, `confidence numeric(3,2)`, `match_count`, `last_confirmed_at`) y agrega `myfinance.transactions.merchant_id` (FK nullable). Sin trigger, sin SECURITY INVOKER — el feedback loop vive 100% en código Java.
- **Errores RFC 7807 `ProblemDetail`** con tipos `https://myfinanceview.local/errors/{unauthorized|forbidden|not-found|validation-error|conflict}`. `@RestControllerAdvice` único.
- **CORS configurado** solo para los dominios Vercel productivo + previews del proyecto y `http://localhost:5173`.
- **Frontend NO se toca en este change** — el swap-out se hace en el change `frontend-swap-to-backend` que aterriza después (separado para que el adversarial review de cada uno sea acotado).

## Capabilities

### New Capabilities

- `backend-rest-api`: contrato REST + auth JWT + ProblemDetail + CORS sobre `backend-runtime`. Soporta 4 endpoints de lectura/cambio mínimos que el MVP frontend ya consume, más el feedback loop a `merchants` que el MVP frontend difirió.

### Modified Capabilities

(ninguna — `backend-runtime` se extiende pero su delta solo agrega componentes; no rompe lo existente. Si el adversarial review concluye que el cambio toca behavior de `backend-runtime`, se mueve a `MODIFIED Requirements`.)

## Threat model

**Adversary considerado:**
- Atacante con un JWT robado al navegador del usuario (XSS, dispositivo compartido, malware del endpoint) que intenta leer transacciones de otros usuarios o mutar `category_id` masivamente.
- Atacante anónimo que descubre los endpoints y dispara CSRF/POST sin sesión.
- Atacante con acceso a la DB Supabase (insider/compromise) que intenta usar el backend como vector adicional.
- Cliente buggy (frontend en estado raro, doble click, race) que dispara mutaciones inconsistentes.

**Defensas que esta propuesta sostiene:**
- Spring Security filter rechaza requests sin `Authorization: Bearer <jwt>` válido. JWT validation: firma `HS256` con `JWT_SECRET` de Supabase (mismo que emite la sesión), claims `exp`, `iss`, `aud`, `sub` parseados; rechazo de 401 si cualquiera falla.
- **Filtrado por `userId` en TODAS las queries jOOQ** — defensa equivalente a RLS, controlada en código. Backend usa `service_role` para conectar a Supabase (RLS-bypassed), pero cada query agrega `WHERE user_id = ?` con el `userId` del JWT. Auditable por test.
- CORS configurado con allowlist explícita; rechazo de Origin no listado.
- Errores `ProblemDetail` no exponen stack traces ni nombres de tablas/columnas.
- PATCH idempotente — si el `categoryId` del body coincide con el current, no se emite query (defensa contra double-click).
- Transacción única para PATCH (`UPDATE transactions` + `UPSERT merchants`) — no hay window de split entre las dos escrituras.
- Conexiones DB con Hikari `maximum-pool-size=5` (defaults del scaffolding); previene saturación trivial.

**Fuera de alcance (auto-rechazado por triage):**
- Compromiso del laptop del operador (endpoint malware).
- Compromiso del proveedor Supabase / Vercel (insider, plataforma).
- Side-channels criptográficos sobre el JWT (HMAC, timing).
- Rotación del `JWT_SECRET` de Supabase — operación coordinada manual, no automatizada por este change.
- Rate limiting / WAF — primera línea de Vercel + Cloudflare en cuanto el frontend pague. Backend en este MVP no rate-limita (queda como mejora cuando aterrice deploy productivo del backend).
- Replay del PATCH con `categoryId` distinto al actual — es la operación legítima del modal; no hay nada que prevenir aquí.

**Trade-off aceptado:** sin sesión propia del backend (no se emiten cookies httpOnly), el JWT vive en `localStorage` del navegador (decisión de Supabase Auth + el MVP frontend). La mitigación adicional (httpOnly cookies emitidas por backend Java) queda como mejora futura cuando el backend tenga deploy productivo y se justifique mantener dos session stores en sync. Aceptable porque el MVP es para un solo usuario en un solo navegador y el TTL del JWT es 1h.

## Impact

- **Código nuevo (backend Java):**
  - `src/main/java/com/myfinanceview/api/controller/TransactionController.java`, `AccountController.java`, `CategoryController.java`.
  - `src/main/java/com/myfinanceview/api/dto/TransactionDTO.java`, `AccountDTO.java`, `CategoryDTO.java`, `PageDTO.java`, `UpdateCategoryRequest.java`.
  - `src/main/java/com/myfinanceview/api/exception/ProblemDetailAdvice.java` + tipos de error.
  - `src/main/java/com/myfinanceview/config/SecurityConfig.java` + `SupabaseJwtFilter.java` + `JwtClaimsExtractor.java`.
  - `src/main/java/com/myfinanceview/config/CorsConfig.java`.
  - `src/main/java/com/myfinanceview/domain/transaction/TransactionRepository.java` + `TransactionService.java` (paginación, feedback loop).
  - `src/main/java/com/myfinanceview/domain/account/AccountRepository.java`.
  - `src/main/java/com/myfinanceview/domain/category/CategoryRepository.java`.
  - `src/main/java/com/myfinanceview/domain/merchant/MerchantRepository.java` + `MerchantUpserter.java`.
  - Tests: contract tests por endpoint (REST-assured), integration tests Testcontainers (auth happy/sad path, paginación, feedback loop transaccional, idempotencia, aislamiento por `userId`).
- **Migración nueva:** `backend/database/migrations/V004__merchants.sql` — tabla `merchants`, FK `transactions.merchant_id`, RLS habilitada con policies análogas a las de V002. Aplicada a Supabase remoto vía Supabase MCP `apply_migration` cuando el operador apruebe el change.
- **`docs/api-spec.yml`:** pasa de stub-only-health a contener `/api/v1/transactions{,/{id}/category}`, `/api/v1/accounts`, `/api/v1/categories` con sus 5 schemas. Source of truth para frontend.
- **`docs/data-model.md`:** sección "Pending migrations" elimina `merchants` y la mueve a "Applied".
- **CI:** `.github/workflows/ci.yml` ya existe; este change agrega los tests Testcontainers a la matriz. Sin cambios de runner.
- **Supabase project (`akkoqdjmmozyqdfjkabg`):** aplica V004 a remoto. Sin tocar V001-V003. Mantenimiento del schema exposure de PostgREST se vuelve opcional (frontend ya no usaría `.schema('myfinance')` después del swap-out — pasa por `/api/v1/*`).
- **Vercel:** sin cambios. Frontend sigue desplegado, solo cambia el body de `services/` en el change posterior `frontend-swap-to-backend`.
- **Deploy target del backend:** **PENDIENTE — Open Question OQ1 en design.md.** Opciones evaluadas: Railway, Fly.io, VPS propio. Decisión necesaria antes de cerrar TASK-BE-08, no antes de implementar.
- **Sin breaking changes a backend existente.** El MVP frontend desplegado en Vercel quedará con 403 hasta que `frontend-swap-to-backend` aterrice — costo aceptado por el operador en lugar de exponer `myfinance` en PostgREST. n8n no se ve afectado porque usa `service_role`.

## Architectural pivot record

Este change supersede retroactivamente la **D1 de `mvp-frontend-readonly/design.md`** ("Frontend habla directo a Supabase"). La D1 fue tomada como puente para lanzar esa misma noche; el operador valida ahora que la arquitectura final es:

```
frontend (Vercel)  →  backend Java REST  →  Supabase (service_role + WHERE user_id = ?)
                                              ↑
                                  RLS de V002 sigue activa como defensa en profundidad,
                                  pero el backend la bypasea con service_role; aislamiento
                                  por usuario se garantiza en código (D2 del design.md
                                  de este change).
```

Implicación operativa: `myfinance` NO se va a exponer nunca en PostgREST. Cualquier change futuro que proponga exponer schemas a roles `anon`/`authenticated` SHALL ser rechazado por defecto y requerir amendment explícito a `SPEC.md`.

## Forward-compat note

Este change resuelve la deuda explícita del MVP frontend (D2, D4 de `mvp-frontend-readonly/design.md`) y crea el contrato HTTP que el frontend ya estaba codificando contra. Cuando el siguiente change `frontend-swap-to-backend` aterrice:

- `frontend/src/services/types.ts` se realinea al `TransactionDTO`/`AccountDTO`/`CategoryDTO` emitidos por este backend (ver `docs/api-spec.yml`). Si quedaron mismatches con el shape inventado del MVP, se documentan en el adversarial review de ese change.
- El mapper privado `rowToDTO` de cada service del frontend se borra; el endpoint Java ya devuelve el shape final.
- Las tres pantallas (`LoginPage`, `TransactionsPage`, `CategoryChangeModal`) no se tocan.
- ESLint `no-restricted-imports` para `@supabase/supabase-js` se mantiene (auth sigue siendo Supabase). Solo `services/*.ts` deja de importar `supabase-js`.
