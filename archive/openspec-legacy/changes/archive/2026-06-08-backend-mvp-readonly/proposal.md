## Why

El MVP frontend ya está desplegado en Vercel (`feat/mvp-frontend-scaffold` + `feat/mvp-frontend-screens`) y habla directo a Supabase PostgREST con la decisión D1 del change `mvp-frontend-readonly` ("Camino A"). Ese camino fue elegido para destrabar el lanzamiento esa misma noche, pero acarrea deuda explícita:

1. **No hay feedback loop a `myfinance.merchants`** — el cambio de categoría desde la UI no refuerza el aprendizaje del sistema. Diferido a TASK-BE-06 del backend Java (ver `mvp-frontend-readonly/design.md` D2).
2. **No hay agregaciones de plata** — billing summary, breakdown por categoría, totales de ciclo, proyección de cierre quedan fuera del MVP frontend porque `docs/base-standards.md §2` prohíbe agregaciones en frontend, y crearlas como vistas SQL en Supabase introduce lógica de negocio en la DB que el backend Java va a tener que reescribir.
3. **Lógica de negocio en RLS** — la separación usuario↔usuario depende de `auth.uid() = user_id` en V002. Cualquier filtro o regla más rica (e.g. compartir cuentas entre usuarios) requeriría más policies SQL — peor mantenibilidad que código Java versionado.
4. **Schema exposure rechazada retroactivamente por arquitectura.** Para que el MVP frontend pudiera leer datos directamente tendría que haberse expuesto `myfinance` en PostgREST + GRANT explícito a roles `anon`/`authenticated`. **El operador rechaza esa decisión.** La única ruta sostenible a las tablas de `myfinance` es vía backend con `service_role` y filtrado por `userId` en código jOOQ (defensa equivalente a RLS, controlada en Java, auditable por test). En consecuencia: el 403 que devuelve hoy el frontend desplegado en Vercel **NO** es un bug a arreglar con un toggle del dashboard — es el gating intencional que destrabará este backend MVP al aterrizar. El change `mvp-frontend-readonly` se cierra con ese 403 como estado terminal documentado.

Este change entrega el corte mínimo del backend Java REST que permite **swap-out** del camino directo a Supabase desde el frontend ya desplegado, sin tocar componentes ni hooks: cambia solo el body de `src/services/*.ts` de `supabase.from(...)` a `fetch('/api/v1/...')` con el mismo JWT. Cubre los 4 endpoints que el MVP frontend ya usa y el feedback loop a `merchants` que el MVP frontend explícitamente difirió. Hasta que este change + el subsiguiente `frontend-swap-to-backend` aterricen y el backend tenga deploy productivo, **el frontend en Vercel permanece con 403 a propósito** — el operador lo acepta como costo de no introducir un toggle de schema-exposure que va contra el modelo arquitectónico final.

## What Changes

- **Capability nueva `backend-rest-api`** sobre el `backend-runtime` scaffolded en `2026-05-13-backend-scaffolding`:
  - **Auth:** Spring Security **OAuth2 Resource Server** con `NimbusJwtDecoder.withJwkSetUri(...)` apuntando al endpoint público JWKS de Supabase. Algoritmo verificado **ES256** (asimétrico EC P-256) — confirmado por probe directo al JWKS el 2026-06-02 (ver `notes/adversarial-review-2026-06-02.md` B7). Claims validados: `exp`, `iss=https://akkoqdjmmozyqdfjkabg.supabase.co/auth/v1`, `aud=authenticated`. Extrae `sub` como `userId: UUID` disponible vía `@AuthenticationPrincipal`. Rotación de claves automática via cache de 5min del decoder. Sin sesión propia del backend.
  - **GET /api/v1/transactions** — paginado cursor-less con `hasMore: boolean`, filtros `accountId`, `categoryIds` (CSV), `page`, `pageSize` (default 25, max 100). Orden `occurred_at DESC, id DESC` para estabilidad. Sin `count=exact`.
  - **GET /api/v1/accounts** — orden por `name ASC` (donde `AccountDTO.name` se sirve desde `accounts.nickname` — ver Mapping en design.md D6).
  - **GET /api/v1/categories** — system (`user_id IS NULL`) + own merged, orden `display_name ASC`. `CategoryDTO.name` se sirve desde `categories.display_name` (NOT NULL post-V004). El campo `parentId` que el MVP frontend inventó **NO se incluye** en el DTO.
  - **PATCH /api/v1/transactions/{id}/category** body `{categoryId}` — emite el `UPDATE` Y el **feedback loop a `myfinance.merchants`** con drift detection (D5):
    - **Visibility guard del `categoryId`** antes del UPDATE: si no es visible para el `userId` → 404 sin diferenciar (anti-IDOR, D10).
    - **Re-confirmación** (merchant existente, misma categoría): `confidence += 0.10` cap `1.00`, `match_count += 1`.
    - **Drift** (merchant existente, categoría distinta): reset a `category_id = nuevo`, `confidence = 0.50`, `match_count = 1` + log INFO estructurado.
    - **Primer aprendizaje** (sin merchant): UPSERT idempotente con `confidence = 0.50`, `match_count = 1`.
    - Idempotencia exacta: si `categoryId === current.categoryId` devuelve 200 sin tocar DB. Transaccional: todos los UPDATEs viven en una sola transacción Postgres.
  - **GET /actuator/health** — ya existe; **endurecido** (D11) a `show-details: never` para no filtrar state de DB connection. **Resto del actuator** (`/info`, `/env`, `/configprops`, `/heapdump`, etc.) deshabilitado → 404.
- **DTOs** `TransactionDTO`, `AccountDTO`, `CategoryDTO`, `Page<T>` como Java records inmutables. Campos en camelCase, fechas como `OffsetDateTime` serializadas como ISO 8601 strings, montos `BigDecimal` (Jackson serializer custom → string vía `toPlainString()`), UUIDs como string. Shape alineado al inventado por el MVP frontend con dos correcciones documentadas: **AccountDTO.name ← accounts.nickname** (rename en mapper, no en schema) y **CategoryDTO drops parentId** (no existe en el schema y no se introduce).
- **`docs/api-spec.yml`** pasa de stub-only-health a contener los 4 schemas (con los renombrados arriba) y los 4 endpoints; CI gate queda como change separado.
- **Dos migraciones:**
  - **V004** — `ALTER TABLE myfinance.categories ADD COLUMN display_name TEXT` + backfill de las 19 categorías system con sus nombres en español + `SET NOT NULL`. **Respeta el plan documentado en `docs/data-model.md §3`** (la versión inicial del proposal hijackeaba V004 para merchants — refutada por adv-review B1+B2).
  - **V005** — Crea `myfinance.merchants` (tabla con `display_name`, `raw_pattern`, `category_id`, `confidence numeric(3,2)`, `match_count`, `last_confirmed_at`, UNIQUE `(user_id, raw_pattern)`) y agrega `myfinance.transactions.merchant_id` (FK nullable). Sin trigger — el feedback loop vive 100% en código Java.
- **Errores RFC 7807 `ProblemDetail`** con tipos `https://myfinanceview.local/errors/{unauthorized|forbidden|not-found|bad-request|internal}`. `@RestControllerAdvice` único. **Zero-echo** garantizado (D11 / M7): el body nunca contiene el token, claims, UUID rechazado, descripción de transacción, ni valores de query params.
- **CORS configurado** solo para los dominios Vercel productivo + previews del proyecto y `http://localhost:5173` (este último solo en perfiles `local`/`test`). Rechazo de preflight → **403** explícito (D9 / M4).
- **Frontend NO se toca en este change** — el swap-out se hace en el change `frontend-swap-to-backend` que aterriza después (separado para que el adversarial review de cada uno sea acotado). El swap-out incluirá drop de `Category.parentId` del tipo TS.

## Capabilities

### New Capabilities

- `backend-rest-api`: contrato REST + auth JWT + ProblemDetail + CORS sobre `backend-runtime`. Soporta 4 endpoints de lectura/cambio mínimos que el MVP frontend ya consume, más el feedback loop a `merchants` que el MVP frontend difirió.

### Modified Capabilities

(ninguna — `backend-runtime` se extiende pero su delta solo agrega componentes; no rompe lo existente. Si el adversarial review concluye que el cambio toca behavior de `backend-runtime`, se mueve a `MODIFIED Requirements`.)

## Threat model

**Adversary considerado:**
- Atacante con un JWT robado al navegador del usuario (XSS, dispositivo compartido, malware del endpoint) que intenta leer transacciones de otros usuarios o mutar `category_id` masivamente.
- Atacante con un JWT válido propio que intenta **IDOR** sobre IDs ajenos (categorías de otro usuario, transacciones de otro usuario) inyectados en body/query params para inferir su existencia.
- Atacante anónimo que descubre los endpoints y dispara CSRF/POST sin sesión, o que sondea `/actuator/*` buscando env vars / heapdumps / mappings.
- Atacante con acceso a la DB Supabase (insider/compromise) que intenta usar el backend como vector adicional.
- Atacante que intenta forjar JWTs con algoritmo `none`, `HS256`, o firma con clave EC propia, esperando que el decoder los acepte por mis-config.
- Cliente buggy (frontend en estado raro, doble click, race) o n8n ingestion concurrente que dispara mutaciones inconsistentes sobre el mismo merchant.

**Defensas que esta propuesta sostiene:**
- **Auth ES256 vía JWKS público** (D1, post-adv-review B7): `NimbusJwtDecoder.withJwkSetUri(...)` valida la firma asimétrica contra las claves publicadas por Supabase. Algoritmo restringido a `ES256` (otros rechazados por el decoder); `iss`, `aud`, `exp`, `sub`-as-UUID validados. Rotación de claves automática via cache 5min — sin downtime ante rotación de Supabase. **No** se usa secret simétrico (`SUPABASE_JWT_SECRET`) — refutado por probe el 2026-06-02.
- **Filtrado por `userId` en TODAS las queries jOOQ** — defensa equivalente a RLS, controlada en código. Backend usa `service_role` para conectar a Supabase (RLS-bypassed), pero cada query agrega `WHERE user_id = ?` con el `userId` del JWT. Cada repository tiene test Testcontainers de aislamiento (cross-user reads/writes = 0 rows affected).
- **Visibility guard anti-IDOR** (D10, post-adv-review B6): antes de aplicar `PATCH /transactions/{id}/category`, el servicio verifica que `body.categoryId` exista y sea visible para el `userId` (sistema o propia). Categoría no visible → 404 sin diferenciar de "no existe" — previene enumeration de categorías ajenas via status code.
- **`ProblemDetail` zero-echo** (D11, post-adv-review M7): el body de errores 4xx/5xx nunca contiene el token, claims, UUIDs rechazados, descripción de transacción, ni valores de query params. `detail` genérico por tipo. Verificado por test asertando que las primeras 16 chars del token no aparecen en el body 401.
- **Actuator surface mínimo** (D11, post-adv-review M8): solo `/actuator/health` expuesto (con `show-details: never`); todos los demás endpoints del actuator deshabilitados — `/env`, `/configprops`, `/heapdump`, etc. devuelven 404 sin exponer la existencia. Cierra el vector de leak de env vars (incluyendo `SUPABASE_SERVICE_ROLE_KEY` si se misconfigura).
- **CORS allowlist explícita** (D9, post-adv-review M4): preflight de Origin no listado → **403** explícito sin headers `Access-Control-Allow-*`. `localhost:5173` solo en perfiles `local`/`test`.
- **PATCH idempotente exacto** (D5): si el `categoryId` del body coincide con el current, no se emite query — defensa contra double-click y contra inflación métrica del `match_count`.
- **PATCH transaccional con drift detection** (D5, post-adv-review M2): `UPDATE transactions` + lógica de feedback loop (re-confirm | drift reset | UPSERT primer aprendizaje) viven en una sola transacción Postgres. Race con n8n ingestion en el UPSERT del merchant: `INSERT ... ON CONFLICT (user_id, raw_pattern) DO UPDATE` es atómico. **Drift** (usuario cambia categoría a una distinta de la aprendida): reset a `confidence=0.50, match_count=1` — el merchant no acumula confirmaciones contradictorias.
- **`MerchantNormalizer.normalize` congelada** (D12): regla determinista única para `raw_pattern`; cualquier cambio post-merge requiere change con plan de re-mapeo — previene duplicados merchant por inconsistencia entre versiones.
- **Logging sin PII** ([`backend-standards.md §6`](../../../docs/backend-standards.md)): no se logean JWTs, keys, passwords, `raw_payload`, ni `external_id`. Drift event INFO incluye `merchant_id`, `old_category_id`, `new_category_id`, `user_id` — sin plata ni descripción.
- **Conexiones DB con Hikari `maximum-pool-size=5`** (defaults del scaffolding); previene saturación trivial.

**Fuera de alcance (auto-rechazado por triage):**
- Compromiso del laptop del operador (endpoint malware).
- Compromiso del proveedor Supabase / Vercel (insider, plataforma).
- Side-channels criptográficos sobre el JWT (ECDSA, timing).
- Rotación manual del JWKS de Supabase (automática via cache del decoder; queda solo el runbook si Supabase fuerza invalidación inmediata).
- Rate limiting / WAF — primera línea de Vercel + Cloudflare en cuanto el frontend pague. Backend en este MVP no rate-limita (queda como mejora cuando aterrice deploy productivo del backend).
- Replay del PATCH con `categoryId` distinto al actual — es la operación legítima del modal; drift detection ya lo trata correctamente.
- Backfill de los 362 registros históricos de `transactions.merchant_id` — los usuarios los siembran naturalmente al re-categorize.

**Trade-off aceptado:** sin sesión propia del backend (no se emiten cookies httpOnly), el JWT vive en `localStorage` del navegador (decisión de Supabase Auth + el MVP frontend). La mitigación adicional (httpOnly cookies emitidas por backend Java) queda como mejora futura cuando el backend tenga deploy productivo y se justifique mantener dos session stores en sync. Aceptable porque el MVP es para un solo usuario en un solo navegador y el TTL del JWT es 1h.

## Impact

- **Código nuevo (backend Java):**
  - `src/main/java/com/myfinanceview/api/controller/TransactionController.java`, `AccountController.java`, `CategoryController.java`.
  - `src/main/java/com/myfinanceview/api/dto/TransactionDTO.java`, `AccountDTO.java` (`name` ← `nickname`), `CategoryDTO.java` (sin `parentId`), `PageDTO.java`, `UpdateCategoryRequest.java`.
  - `src/main/java/com/myfinanceview/api/exception/ProblemDetailAdvice.java` + tipos de error (`NotFoundException`, `ForbiddenException`) — zero-echo.
  - `src/main/java/com/myfinanceview/config/SecurityConfig.java` (OAuth2 Resource Server + JWKS ES256) + JwtAuthenticationConverter custom para `sub → UUID`.
  - `src/main/java/com/myfinanceview/config/CorsConfig.java` (allowlist + 403 reject).
  - `src/main/java/com/myfinanceview/domain/transaction/TransactionRepository.java` + `TransactionService.java` (paginación, feedback loop con drift detection, visibility guard).
  - `src/main/java/com/myfinanceview/domain/account/AccountRepository.java` + `AccountMapper.java` (`nickname → name`).
  - `src/main/java/com/myfinanceview/domain/category/CategoryRepository.java` + `CategoryMapper.java` (`display_name → name`, drop `parentId`).
  - `src/main/java/com/myfinanceview/domain/merchant/MerchantRepository.java` + `MerchantUpserter.java` (sin parámetro `DSLContext`) + `MerchantNormalizer.java` (congelada — D12).
  - `src/test/java/.../auth/TestJwtFactory.java` + `JwksWireMockExtension.java` para tests de auth (par EC P-256 efímero, WireMock JWKS).
  - Tests: contract tests por endpoint (REST-assured), integration tests Testcontainers (auth happy/sad path incluyendo JWKS rotation, paginación, feedback loop transaccional con re-confirm/drift/primer-aprendizaje, idempotencia, aislamiento por `userId`, IDOR guard, zero-echo, actuator hardening).
- **Migraciones nuevas (dos, secuenciales, additive-only):**
  - `backend/database/migrations/V004__categories_display_name.sql` — `ALTER TABLE myfinance.categories ADD COLUMN display_name TEXT` + backfill ES + `SET NOT NULL`. Respeta el plan original de `docs/data-model.md §3`.
  - `backend/database/migrations/V005__merchants.sql` — tabla `merchants`, FK `transactions.merchant_id` (nullable), RLS habilitada con policies análogas a `accounts_*` de V002.
  - Ambas aplicadas a Supabase remoto vía Supabase MCP `apply_migration` por el agente (autorización delegada 2026-06-02), **condicional a tests verdes locales + state snapshot + operator gate manual**.
- **`docs/api-spec.yml`:** pasa de stub-only-health a contener `/api/v1/transactions{,/{id}/category}`, `/api/v1/accounts`, `/api/v1/categories` con sus 5 schemas (con renombrados D6) + responses `ProblemDetail` (401/403/404/400/500). Source of truth para frontend.
- **`docs/data-model.md`:** actualizar §3 — V004 = `categories.display_name` aplicada; V005 = `merchants` + `transactions.merchant_id` aplicada; correr la cola posterior (V006 = cut/payment day, V007 = installments, V008 = categorization fields, V009 = savings_goals).
- **CI:** `.github/workflows/ci.yml` ya existe; este change agrega los tests Testcontainers a la matriz. Sin cambios de runner.
- **Supabase project (`akkoqdjmmozyqdfjkabg`):** aplica V004 + V005 a remoto. Sin tocar V001-V003. Mantenimiento del schema exposure de PostgREST se vuelve estrictamente vacío para `myfinance` (regla durable [[project-backend-only-path-to-myfinance-schema]]).
- **Vercel:** sin cambios. Frontend sigue desplegado, solo cambia el body de `services/` en el change posterior `frontend-swap-to-backend`.
- **Env vars (nuevas / renombradas):**
  - `SUPABASE_JWT_JWKS_URI` (nueva, default al endpoint público) — reemplaza el inicialmente planeado `SUPABASE_JWT_SECRET`/`JWT_SECRET` (eliminados post-D1).
  - `SUPABASE_JWT_ISSUER` (mantiene).
  - `APP_CORS_ALLOWED_ORIGINS` (renombrada desde `CORS_ALLOWED_ORIGINS` para namespace `app.*`).
  - `SUPABASE_DB_URL`, `SUPABASE_SERVICE_ROLE_KEY` (mantienen, ya en el scaffolding).
- **Deploy target del backend:** **PENDIENTE — Open Question OQ1 cerrada con default Fly.io.** Operator queda libre de cambiar antes de TASK-BE-08.
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
