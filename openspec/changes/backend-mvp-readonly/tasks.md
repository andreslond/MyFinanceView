## 1. Migration V004 — `myfinance.categories.display_name` (ES, NOT NULL post-backfill)

- [x] 1.1 Crear `backend/database/migrations/V004__categories_display_name.sql` con `ALTER TABLE myfinance.categories ADD COLUMN display_name TEXT` (D8)
- [x] 1.2 Agregar el backfill de las 19 categorías system (`user_id IS NULL`) con su `display_name` en español (mapeo de [`SPEC.md §4`](../../../SPEC.md))
- [x] 1.3 Agregar fallback defensivo `UPDATE myfinance.categories SET display_name = name WHERE display_name IS NULL` antes del `SET NOT NULL` (cubre filas user-owned hipotéticas)
- [x] 1.4 Cerrar con `ALTER TABLE myfinance.categories ALTER COLUMN display_name SET NOT NULL`
- [x] 1.5 Probar V004 contra Postgres 17 local via Testcontainers (`./mvnw test -Dtest=V004CategoriesDisplayNameTest` → 3 tests verdes, 13.15s); cubre V001..V004 sin errores y `display_name IS NULL` = 0
- [x] 1.6 Test Testcontainers `db/V004CategoriesDisplayNameTest.java`: arranca Postgres con V001..V004, asserta columna existe NOT NULL, asserta que las 19 categorías system tienen `display_name` en español

## 2. Migration V005 — `myfinance.merchants` + `transactions.merchant_id`

- [x] 2.1 Crear `backend/database/migrations/V005__merchants.sql` con la tabla `merchants` (D8) — columnas, CHECKs, UNIQUE `(user_id, raw_pattern)`, trigger `set_merchants_updated_at`
- [x] 2.2 Agregar policies RLS `merchants_select_own`, `merchants_insert_own`, `merchants_update_own`, `merchants_delete_own` análogas a `accounts_*` de V002
- [x] 2.3 Agregar `ALTER TABLE myfinance.transactions ADD COLUMN merchant_id UUID REFERENCES myfinance.merchants(id) ON DELETE SET NULL` + índice partial `idx_transactions_merchant_id WHERE merchant_id IS NOT NULL`
- [x] 2.4 Probar V005 contra Postgres 17 local via Testcontainers: V001..V005 aplican; tabla `merchants` creada vacía; columna `transactions.merchant_id` agregada como nullable
- [x] 2.5 Test Testcontainers `db/V005MerchantsTest.java`: schema validado (tabla, FK, policies, índice, UNIQUE), CHECK confidence (0.00..1.00), CHECK match_count ≥ 0, 4 policies RLS; UNIQUE `(user_id, raw_pattern)` rechaza duplicados (7 tests verdes, 4.5s)
- [x] 2.6 Actualizar `docs/data-model.md` (Task #6) para reflejar V004=display_name y V005=merchants y correr la cola posterior (V006 cut/payment, V007 installments, V008 categorization, V009 savings)

## 3. Auth: Spring Security OAuth2 Resource Server con JWKS asimétrico (ES256)

- [x] 3.1 `config/SecurityConfig.java` — `@EnableWebSecurity`, STATELESS, CSRF disabled, `/actuator/health` permitAll, `/api/v1/**` authenticated, `oauth2ResourceServer.jwt(...)` + custom ProblemDetail `AuthenticationEntryPoint` / `AccessDeniedHandler` (zero-echo, generic `detail`)
- [x] 3.2 `@Bean JwtDecoder` con `NimbusJwtDecoder.withJwkSetUri(jwksUri).jwsAlgorithm(SignatureAlgorithm.ES256).build()` + `DelegatingOAuth2TokenValidator(JwtTimestampValidator, JwtIssuerValidator(issuer), audienceValidator("authenticated"))`. Custom audience predicate handles both scalar and list `aud` claim shapes (Supabase varies). Blank `jwks-uri` returns a "rejects everything" decoder so the context still loads for health-only smoke tests
- [x] 3.3 `auth/UserIdJwtAuthenticationConverter.java` — parses `sub` as `UUID`, throws `InvalidBearerTokenException` (mapped to 401 by the resource-server filter chain) with generic message (no echo of the offending value); principal = `UUID`
- [x] 3.4 `TestJwtFactory.java` — ephemeral EC P-256 keypair via `ECKeyGenerator(Curve.P_256)`; helpers `valid`, `expired`, `wrongAudience`, `wrongIssuer`, `signedWithDifferentKey`, `validSignedWithRotatedKey`, `subNotUuid`, `hs256Signed`, `malformedTwoSegments` — Nimbus `SignedJWT` + `ECDSASigner` (+ `MACSigner` for the HS256 case)
- [x] 3.5 `JwksWireMockExtension.java` — JUnit5 BeforeAll/AfterAll extension; dynamic port; `stubKeys(JWK...)` replaces the served JWKS to support rotation tests; `wireMock()` exposes the server for `verify(...)` assertions
- [x] 3.6 `SecurityIntegrationTest.java` — 13 tests including all 10 scenarios from spec.md + parameterised malformed header coverage. Rotation test: after Phase 1 (K1 → 200), resets WireMock request counter, swaps JWKS to K2, asserts Phase 2 (K2 → 200) and `verify(moreThanOrExactly(1), getRequestedFor(/jwks.json))`. Counter reset between phases makes the assertion robust to cache state across reused Spring contexts (the K1 fetch may or may not happen). **13/13 green in ~5.8s**
- [x] 3.7 `ActuatorEndpointsTest.shouldHealthBodyBeStatusUpOnly` + `should200OnHealthWithoutAuth` — `/actuator/health` accessible unauth, body is exactly `{"status":"UP"}` with no `components`/`details`/`db`. Companion 404 assertions for `/info`, `/env`, `/configprops`, `/heapdump`. **6/6 green**
- [x] 3.8 `ProblemDetailZeroEchoTest.should401AndNotEchoTokenInProblemDetailWhenJwtInvalid` — sends a token signed with a foreign key (so it survives the parser but fails verification), asserts 401 and that the 16-char prefix of the rejected token is absent from the body. **1/1 green**

## 4. DTOs + Jackson config + Mappers (con renombrado backend ↔ schema)

- [x] 4.1 Crear `api/dto/TransactionDTO.java` record con campos camelCase per D6: `id, accountId, categoryId, merchantId, type, amount, currency, description, occurredAt, createdAt, updatedAt`. **No** expone `userId`, `notes`, `external_id`, `raw_payload`, `source`, `amount_base_currency` — pin compile-time vía record component check en `TransactionMapperTest`
- [x] 4.2 Crear `api/dto/AccountDTO.java` record: `id, name, last4, type, currency, active, createdAt, updatedAt`. **No** expone `userId` ni `bankId` (no se usan en el shape MVP frontend)
- [x] 4.3 Crear `api/dto/CategoryDTO.java` record: `id, name, type, color, icon`. **No** incluye `parentId` (drop confirmado por adv-review B3 / D6); **no** expone `userId`. **Divergencia tasks.md §4.3 vs api-spec.yml:** la tarea originalmente listaba `createdAt, updatedAt`; `docs/api-spec.yml` (componente `CategoryDTO`) los omite — api-spec.yml gana per §4 brief, así que el record final NO incluye timestamps
- [x] 4.4 Crear `api/dto/PageDTO.java<T>` record (`rows, page, pageSize, hasMore`)
- [x] 4.5 Crear `api/dto/UpdateCategoryRequest.java` record (`categoryId: @NotNull UUID`) con validación Bean Validation
- [x] 4.6 Crear `config/JacksonConfig.java` — registra `JavaTimeModule`, disable `WRITE_DATES_AS_TIMESTAMPS`, contribuye via `Jackson2ObjectMapperBuilderCustomizer` un `JsonSerializer<BigDecimal>` que escribe `value.toPlainString()` como **quoted string** (no JSON number). Verificado por test: `new BigDecimal("0.10")` → `"0.10"`, no `"0.1"`
- [x] 4.7 Crear `domain/account/AccountMapper.java` static `fromRow(...)`: mapea `nickname → AccountDTO.name` (resuelve B4). **Codegen jOOQ aún no corre** → firma toma parámetros primitivos en lugar de `AccountsRecord`; el overload `fromRecord(AccountsRecord)` se agregará en §5.1 como pass-through one-liner que delega al `fromRow` actual. La invariante del rename queda anclada en una única locación
- [x] 4.8 Crear `domain/category/CategoryMapper.java` static `fromRow(...)`: mapea `displayName → CategoryDTO.name` (post-V004). Misma nota de codegen que §4.7
- [x] 4.9 Crear `domain/transaction/TransactionMapper.java` static `fromRow(...)` con todos los campos visibles del DTO. Misma nota de codegen que §4.7
- [x] 4.10 Test `JacksonSerializationTest.java` (unit, sin Spring) — 9 tests verdes: amount como `"12345.67"` (quoted string), scale `"0.10"` preserved, `occurredAt` ISO-8601 UTC, AccountDTO JSON sin `"nickname"`, CategoryDTO JSON sin `"parentId"`, ningún DTO contiene `"userId"`, TransactionDTO sin `"rawPayload"/"externalId"/"notes"/"source"/"amountBaseCurrency"`, round-trip BigDecimal desde quoted string, PageDTO shape
- [x] 4.11 Test `AccountMapperTest.java` (unit) — 5 tests verdes: rename `nickname → name`, mapeo full-field, `last4` null preservado, enum `credit_card` lowercase literal, `active=false` preservado
- [x] 4.12 Test `CategoryMapperTest.java` (unit) — 5 tests verdes: rename `display_name → name`, enum `expense`/`income` literal, color/icon null preservados, **reflective check** de record components asegura que `CategoryDTO` NO tiene componente `parentId` (compile-time + reflective doble cinturón)
- [x] 4.13 Test `TransactionMapperTest.java` (unit) — 4 tests verdes: full-field mapping, categoryId/merchantId/description null preservados, BigDecimal scale pass-through, reflective check pin de no-exposure (`userId`, `rawPayload`, `notes`, `externalId`, `amountBaseCurrency`, `source` ausentes del record)

## 5. Repositorios jOOQ + isolation tests

- [x] 5.1 Codegen jOOQ ejecutado contra Postgres local (docker compose) con V001..V005 aplicadas. `./mvnw -P codegen generate-sources` produjo 27 archivos bajo `target/generated-sources/jooq/com/myfinanceview/jooq/generated/` — paquete raíz `Myfinance.MYFINANCE` con tables (`ACCOUNTS, CATEGORIES, MERCHANTS, TRANSACTIONS, BANKS, BUDGETS, BUDGET_CATEGORIES, EXCHANGE_RATES, USER_SETTINGS`), records (`*Record`), y enums (`AccountType, CategoryType, TransactionSource, TransactionType` con constantes lowercase). Profile `codegen` en pom.xml (jdbc en :5433/myfinance_local, schema `myfinance`, package `com.myfinanceview.jooq.generated`). `.gitignore` ya incluía `target/generated-sources/jooq/`.
- [x] 5.2 `domain/account/AccountRepository.java` ctor-injected `DSLContext`, `findAllByUserId(UUID): List<AccountsRecord>` con `WHERE user_id = ?` `ORDER BY nickname ASC`
- [x] 5.3 `domain/category/CategoryRepository.java`:
  - `findAllVisibleToUser(UUID): List<CategoriesRecord>` con `WHERE user_id IS NULL OR user_id = ?` `ORDER BY display_name ASC`
  - `findByIdVisibleToUser(UUID, UUID): Optional<CategoriesRecord>` — **visibility guard D10**, `Optional.empty()` colapsa "no existe" / "de otro user" / "es system" en un solo signal
- [x] 5.4 `domain/transaction/TransactionRepository.findPage(...)` con filtros opcionales `accountId`+`categoryIds`, orden `occurred_at DESC, id DESC`, `LIMIT pageSize+1` y offset `(page-1)*pageSize`
- [x] 5.5 `TransactionRepository.findById(UUID, UUID): Optional<TransactionsRecord>` con `WHERE id = ? AND user_id = ?`
- [x] 5.6 `TransactionRepository.updateCategory(UUID, UUID, UUID, OffsetDateTime): int` — un solo UPDATE; retorna rows affected (0 = no encontrada o del otro user)
- [x] 5.7 `TransactionRepository.assignMerchantId(UUID, UUID, UUID): int` — mismo patrón
- [x] 5.8 `domain/merchant/MerchantRepository.java`:
  - `findById(UUID, UUID): Optional<MerchantsRecord>` con `WHERE id = ? AND user_id = ?`
  - `upsertByRawPattern(...)` vía `INSERT ... ON CONFLICT (user_id, raw_pattern) DO UPDATE ... RETURNING id` (idempotente, cierra race con n8n)
  - `confirmCategory(UUID, UUID): int` — `confidence = LEAST(1.00, confidence + 0.10)` con `val(..., NUMERIC)` para resolver el tipo de `least(...)`, bump `match_count`, refresh `last_confirmed_at` y `updated_at`
  - `resetForDrift(UUID, UUID, UUID): int` — `category_id` swap, `confidence=0.50`, `match_count=1`, `last_confirmed_at=NOW()`; **no toca `display_name`** (identidad persiste, clasificación resetea — D5)
- [x] 5.9 `domain/merchant/MerchantNormalizer.java` con regla congelada D12 (`trim().toLowerCase(ROOT)` → strip `\s*\*?\s*\d{2,}\s*$` → collapse `\s+`); anotado inline como **FROZEN**
- [x] 5.10 `MerchantNormalizerTest` — **12/12 verdes** en 0.09s. Cubre `null/empty/whitespace → ""`, `"NETFLIX.COM *1234" → "netflix.com"`, `"RAPPI 42" → "rappi"`, `"DIDI 7" → "didi 7"` (1 dígito NO strip), `"JUAN VALDEZ *9876" → "juan valdez"`, `"APPLE.COM" → "apple.com"`, `"FOO 123 BAR" → "foo 123 bar"` (dígitos internos NO strip), `"AMAZON.COM*4ABCD9" → "amazon.com*4abcd9"` (trailing letras NO strip), `"PAYU * COL  *55" → "payu * col"` (strip funciona aunque haya un `*` interno previo), `"STARBUCKS  9" → "starbucks 9"` (1 dígito final + collapse de doble espacio)
- [x] 5.11 `TransactionRepositoryIsolationTest` — **7/7 verdes** en ~6s vía Testcontainers Postgres 17 con `@BeforeAll static applyMigrationsOnce()` (patrón aprendido de V005MerchantsTest). Cubre: `findPage(userA)` solo devuelve filas de A; `findPage(userA, accountB)` → empty; `findById(txB, userA)` → empty; `updateCategory(txB, userA)` → 0 rows; `assignMerchantId(txB, userA)` → 0 rows; ordering `occurred_at DESC, id DESC` con tie-break por id; `LIMIT pageSize+1 = 26` cuando hay 30 filas
- [x] 5.12 Tests de isolación en merchants integrados en `MerchantRepositoryTest` — **8/8 verdes** en ~3.6s. Cubre upsert idempotente, isolación user-A vs user-B (mismo raw_pattern → ids distintos, `findById(B, A)` → empty), confidence cap a 1.00, drift reset (display_name preservado), `confirmCategory(B, A)` → 0 rows, `resetForDrift(B, A, ...)` → 0 rows, `findById(B, A)` → empty
- [x] 5.13 `CategoryRepositoryVisibilityGuardTest` — **5/5 verdes** en ~7s. Cubre: system category visible para cualquier user; userA ve su custom; userB-private NO visible para A (anti-IDOR D10); UUID inexistente → empty; `findAllVisibleToUser(A)` devuelve 19 system + 1 custom = 20 sin filas de userB

## 6. Services + transactional boundaries + drift logic

- [x] 6.1 `domain/account/AccountService.java` ctor-injects `AccountRepository`; `listForUser(userId)` mapea via `AccountMapper::fromRecord`. Smoke `AccountServiceTest` 1/1 verde
- [x] 6.2 `domain/category/CategoryService.java` análogo — delega + `CategoryMapper::fromRecord` (camino de mapping cubierto por `CategoryRepositoryVisibilityGuardTest` + `CategoryMapperTest`, sin smoke dedicado)
- [x] 6.3 `domain/merchant/MerchantUpserter.java` `@Component`, ctor-injects `MerchantRepository`. `applyFeedback(userId, txCurrentMerchantId, txDescription, newCategoryId): UUID`:
  - Branch A: `confirmCategory` si misma categoría; `resetForDrift` + log INFO si difiere
  - Branch B: `MerchantNormalizer.normalize(description)` → `upsertByRawPattern` (idempotente, race-safe contra n8n)
  - Log INFO `event=merchant_drift_reset merchant_id=… old_category_id=… new_category_id=… user_id=…` — IDs only, no amount/description
- [x] 6.4 `domain/transaction/TransactionService.java`:
  - `listForUser(...)` — query `LIMIT pageSize+1`, calcula `hasMore`, truncea con `subList`, mapea via `TransactionMapper::fromRecord`
  - `updateCategory(...)` con `@Transactional`: visibility guard → load tx → idempotency short-circuit (mismo `categoryId` → no DB write) → UPDATE → `MerchantUpserter.applyFeedback` → assign si nuevo merchantId → re-fetch
- [x] 6.5 `spring.transaction.default-timeout: 5` en `application.yml` (line 18)
- [x] 6.6 `TransactionServiceIdempotencyTest` 1/1 verde — `updated_at` y merchant snapshot no cambian cuando `body.categoryId == tx.categoryId`
- [x] 6.7 `TransactionServiceReconfirmTest` 3/3 verdes — `+0.10` con cap `1.00` (0.60→0.70, 0.95→1.00, 1.00→1.00); `match_count += 1`; `last_confirmed_at` actualizado
- [x] 6.8 `TransactionServiceDriftTest` 1/1 verde — reset a `confidence=0.50`, `match_count=1`, `category_id=catY`; log INFO `event=merchant_drift_reset` capturado y verificado SIN substrings de amount/description
- [x] 6.9 `TransactionServiceFirstLearningTest` 1/1 verde — merchant creado con `raw_pattern="netflix.com"`, `display_name="NETFLIX.COM *1234"`, `confidence=0.50`, `match_count=1`; `tx.merchant_id` asignado
- [x] 6.10 `TransactionServiceIdorGuardTest` 3/3 verdes — userA con categoryId de userB → `NotFoundException` (mapeada a 404); tx desconocida → 404; categoría inexistente → 404; rows de userA/userB sin modificar
- [x] 6.11 `TransactionServiceAtomicityTest` 1/1 verde — receta (b) elegida (documentada en el header del test): `@MockBean MerchantUpserter` lanza `RuntimeException` desde `applyFeedback`. Receta (a) descartada — insertar un `DELETE FROM categories` entre el guard y el UPDATE requeriría un test hook que la implementación no expone. Asserción: `tx.{category_id,updated_at}` y `merchant.{category_id,confidence,match_count,last_confirmed_at}` snapshot preservado tras el rollback de `@Transactional`
- [x] 6.12 `TransactionServicePaginationTest` 1/1 verde — 100 filas seedeadas; `page=1,pageSize=25 → 25 rows + hasMore=true`; `page=4 → 25 rows + hasMore=false`; `page=5 → 0 rows + hasMore=false`

## 7. Controllers + ProblemDetail (zero-echo) + CORS (403)

- [x] 7.1 Crear `api/controller/TransactionController.java` con `GET /api/v1/transactions` (query params `accountId`, `categoryIds`, `page`, `pageSize` con `@Min`/`@Max`) y `PATCH /api/v1/transactions/{id}/category` (`@Valid UpdateCategoryRequest`); usa `@AuthenticationPrincipal UUID userId`
- [x] 7.2 Crear `api/controller/AccountController.java` con `GET /api/v1/accounts`
- [x] 7.3 Crear `api/controller/CategoryController.java` con `GET /api/v1/categories`
- [x] 7.4 Crear `api/exception/ProblemDetailAdvice.java` `@RestControllerAdvice` mapeando: `NotFoundException → 404`, `ForbiddenException → 403`, `MethodArgumentNotValidException → 400`, `ConstraintViolationException → 400`, `AuthenticationException → 401`, default exception → 500. **Todo `detail` es genérico** (D11): `"Resource not found"`, `"Bad request"`, `"Unauthorized"`, `"Forbidden"`, `"An unexpected error occurred"`. **Nunca** ecoa: token, claims, UUID rechazado, descripción de la transacción, valores de query params (resuelve M7)
- [x] 7.5 Crear `api/exception/NotFoundException.java`, `ForbiddenException.java` (runtime exceptions con mensaje interno que se loguea pero NO se devuelve)
- [x] 7.6 Crear `config/CorsConfig.java` (D9): `@Bean CorsConfigurationSource` con allowlist desde `app.cors.allowed-origins` (CSV de `APP_CORS_ALLOWED_ORIGINS`); `addAllowedOriginPattern("https://*.vercel.app")`; `localhost:5173` solo en perfiles `local`/`test`; métodos `GET, PATCH, OPTIONS`; headers `Authorization, Content-Type`; `allowCredentials(false)`; configurar `CorsFilter` para que rechazos devuelvan **403** (resuelve M4)
- [x] 7.7 Test REST-assured `TransactionControllerContractTest.java` — JWT válido + filtros → 200 + shape exacto; `accountId` malformado → 400; tx inexistente en PATCH → 404; tx de otro `userId` en PATCH → 404 (no 403); `categoryId` no visible → 404 sin echo del UUID
- [x] 7.8 Test REST-assured `AccountControllerContractTest.java` — 200 con cuentas del user mapeadas con `name` desde `nickname`; sin auth → 401
- [x] 7.9 Test REST-assured `CategoryControllerContractTest.java` — 200 con system + own ordenadas por `display_name`; verificar que ningún elemento contiene `parentId`; sin auth → 401
- [x] 7.10 Test REST-assured `ProblemDetailContractTest.java` — verifica que el body de 4xx matches RFC 7807 (`type, title, status, detail, instance`); asserta zero-echo: `pageSize=500` → 400 sin `"500"` en body; `categoryId=<uuid-de-B>` → 404 sin el UUID en body; JWT inválido → 401 sin substring del JWT
- [x] 7.11 Test REST-assured `CorsConfigContractTest.java` — preflight con `Origin: http://localhost:5173` → 200 con `Access-Control-Allow-Origin` matching; preflight con `Origin: https://app-abc123.vercel.app` → 200 con header matching; preflight con `Origin: https://evil.com` → **403** con body vacío y SIN `Access-Control-Allow-*`
- [x] 7.12 Test REST-assured `MalformedAuthorizationTest.java` (resuelve M5) — `Authorization: NotBearer xyz` → 401; `Authorization: Bearer` (sin token) → 401; `Authorization: solo-token-sin-prefijo` → 401; `Authorization: Bearer abc.def` (JWT con 2 segmentos) → 401; body NO contiene el header recibido en ninguno

## 8. Configuración + env vars + actuator hardening

- [x] 8.1 Actualizar `application.yml`:
  - `app.auth.supabase.jwks-uri: ${SUPABASE_JWT_JWKS_URI:https://akkoqdjmmozyqdfjkabg.supabase.co/auth/v1/.well-known/jwks.json}`
  - `app.auth.supabase.issuer: ${SUPABASE_JWT_ISSUER:https://akkoqdjmmozyqdfjkabg.supabase.co/auth/v1}`
  - `app.auth.supabase.audience: authenticated`
  - `app.cors.allowed-origins: ${APP_CORS_ALLOWED_ORIGINS:}` (CSV)
  - **Actuator hardening** (D11):
    ```yaml
    management:
      endpoints:
        web:
          exposure:
            include: health
      endpoint:
        health:
          show-details: never
          show-components: never
        info:
          enabled: false
        env:
          enabled: false
        configprops:
          enabled: false
        heapdump:
          enabled: false
        threaddump:
          enabled: false
        beans:
          enabled: false
        mappings:
          enabled: false
        metrics:
          enabled: false
    ```
  - `spring.transaction.default-timeout: 5`
- [x] 8.2 Actualizar `application-local.yml` con `app.cors.allowed-origins: http://localhost:5173` para dev local
- [x] 8.3 Actualizar `application-test.yml` para que tests inyecten `app.auth.supabase.jwks-uri` via `@DynamicPropertySource` (WireMock URL)
- [x] 8.4 Actualizar `.env.example` con `SUPABASE_JWT_JWKS_URI=`, `SUPABASE_JWT_ISSUER=`, `SUPABASE_DB_URL=`, `SUPABASE_SERVICE_ROLE_KEY=`, `APP_CORS_ALLOWED_ORIGINS=`. **Eliminar** `JWT_SECRET=` y `SUPABASE_JWT_SECRET=` si están (ya no aplican post D1)
- [x] 8.5 Documentar en `docs/development-guide.md` cómo correr el backend local apuntando al JWKS público de Supabase + service_role para DB
- [x] 8.6 Test `ActuatorSurfaceTest.java` (resuelve M8) — `/actuator/health` → 200 con body exacto `{"status":"UP"}` (sin `components`, `db`, etc.); `/actuator/info` → 404; `/actuator/env` → 404; `/actuator/configprops` → 404; `/actuator/heapdump` → 404; `/actuator/threaddump` → 404; `/actuator/beans` → 404; `/actuator/mappings` → 404; `/actuator/metrics` → 404

## 9. OpenAPI spec sync (`docs/api-spec.yml`)

- [x] 9.1 Reescribir `docs/api-spec.yml`: añadir paths `/api/v1/transactions`, `/api/v1/transactions/{id}/category`, `/api/v1/accounts`, `/api/v1/categories` con parámetros, security, responses
- [x] 9.2 Agregar schemas `TransactionDTO`, `AccountDTO` (con `name` no `nickname`), `CategoryDTO` (sin `parentId`), `PageDTO_TransactionDTO`, `UpdateCategoryRequest`, `ProblemDetail` (referencia RFC 7807)
- [x] 9.3 Agregar `securitySchemes: bearerAuth (http, bearer, jwt — ES256)` y aplicar a todos los endpoints excepto `/actuator/health`
- [x] 9.4 Documentar en el YAML las responses `401`, `403` (CORS reject), `404`, `400`, `500` con `application/problem+json` y schema `ProblemDetail`
- [ ] 9.5 Verificar que el YAML es válido OpenAPI 3.1 (e.g. `openapi-cli validate` o equivalente) — smoke test estructural OK (5 paths, 7 schemas, 5 responses, 5 operations); validación formal con CLI pendiente (no instalado en el host actual)

## 10. STOP — Operator gate antes de aplicar a Supabase remoto

- [ ] 10.1 **STOP — operator gate manual.** Agente publica resumen para el operador: tests verdes (sección 1-9), checklist de pre-V004, backup status del último `daily/` de R2 (referenciar `supabase-backup-policy-replant`)
- [ ] 10.2 Operator confirma: ejecutar snapshot manual ahora si el último `daily/` tiene >24h o si el state cambió desde el último backup
- [ ] 10.3 Agente captura state snapshot pre-op vía Supabase MCP `execute_sql`:
  - `SELECT COUNT(*) FROM myfinance.categories` (esperado: 19+, todos system)
  - `SELECT COUNT(*) FROM myfinance.categories WHERE user_id IS NULL` (esperado: 19)
  - `SELECT COUNT(*) FROM myfinance.transactions` (esperado: 362 — o el valor vigente)
  - `SELECT COUNT(*) FROM myfinance.accounts` (esperado: 3)
  - Guardar el snapshot en `progress.md`
- [ ] 10.4 Agente ejecuta `mcp__claude_ai_Supabase__list_migrations` para confirmar últimas migraciones aplicadas (V001..V003)
- [ ] 10.5 **OPERATOR INPUT REQUIRED — barrera dura.** Agente publica resumen final del estado (tests verdes, snapshot capturado, migraciones listas) y **ESPERA** una de las siguientes respuestas literales del operador antes de pasar a §11:
  - `proceed` — agente continúa con §11.1 (apply V004 a remoto)
  - `pause` o cualquier otra cosa — agente NO ejecuta §11; registra blocker en `progress.md` con timestamp + mensaje del operador y termina la sesión
  - Sin respuesta en 24h — agente NO ejecuta §11; registra blocker `operator_gate_timeout` en `progress.md` y termina la sesión. La continuación es operador-iniciada explícitamente en una nueva sesión `/opsx:apply --resume`.

  Esta barrera no se salta. El agente no infiere autorización del silencio ni de instrucciones previas. La autorización delegada del 2026-06-02 ("agente puede aplicar V004 a remoto") está **condicional** a este gate; sin el `proceed` literal, la autorización queda inactiva.

## 11. Aplicar V004 + V005 a Supabase remoto (agente autorizado, condicional)

- [ ] 11.1 Agente ejecuta `mcp__claude_ai_Supabase__apply_migration` con `name="v004_categories_display_name"`, `query=<contenido literal de V004__categories_display_name.sql>`
- [ ] 11.2 Agente verifica post-V004 vía `execute_sql`:
  - `SELECT COUNT(*) FROM myfinance.categories WHERE display_name IS NULL` → debe ser 0
  - `SELECT COUNT(*) FROM myfinance.categories` → mismo valor que pre-V004 (no se borra ni inserta)
  - `SELECT display_name FROM myfinance.categories WHERE name = 'Dining Out' AND user_id IS NULL` → "Restaurantes y Cafés"
  - Si cualquiera falla → STOP, alertar operator, considerar restore
- [ ] 11.3 Agente ejecuta `mcp__claude_ai_Supabase__apply_migration` con `name="v005_merchants"`, `query=<contenido literal de V005__merchants.sql>`
- [ ] 11.4 Agente verifica post-V005 vía `execute_sql`:
  - `SELECT COUNT(*) FROM myfinance.merchants` → 0
  - `SELECT COUNT(*) FROM myfinance.transactions WHERE merchant_id IS NOT NULL` → 0
  - Tabla `merchants` con RLS enabled vía `list_tables`
  - Si falla → STOP
- [ ] 11.5 Agente ejecuta `mcp__claude_ai_Supabase__get_advisors type="security"` y verifica que no aparezcan nuevos issues atribuibles a V004/V005 (RLS missing, etc.)
- [ ] 11.6 Agente actualiza `progress.md` con el state snapshot post-op + timestamps

## 12. Cierre

- [ ] 12.1 Actualizar `progress.md` final (last_completed, next_step="ready for PR", blockers=[])
- [ ] 12.2 Segunda pasada de adversarial review sobre proposal+design+spec+tasks reescritos (Task #7 del meta-plan)
- [ ] 12.3 Resolver Blockers/Majors residuales si los hay
- [ ] 12.4 Commit con `/commit`, PR a `main` (push del worktree branch `feat/backend-mvp-readonly`)
- [ ] 12.5 Merge a `main` (operator-approved)
- [ ] 12.6 `/opsx:archive` el change `backend-mvp-readonly`
- [ ] 12.7 `/openspec-sync-specs` para mergear deltas de `backend-rest-api` (nueva capability) en `openspec/specs/`
- [ ] 12.8 Notion: marcar TASK-BE-03, TASK-BE-04, TASK-BE-05, TASK-BE-06, TASK-DB-01 (display_name), TASK-DB-04 (merchants) como done

## 13. Out of scope de este change (next changes)

- [ ] 13.1 (CHANGE `frontend-swap-to-backend`) — Reescribir body de `frontend/src/services/{transactions,accounts,categories}Service.ts` para `fetch('/api/v1/...')` con `Authorization: Bearer <session.access_token>`; drop `Category.parentId` del tipo TS
- [ ] 13.2 (CHANGE `backend-deploy-{target}`) — Resolver OQ1, configurar deploy del backend Java; tareas dependen del target elegido
- [ ] 13.3 (CHANGE `api-spec-ci-gate`) — Gate CI que verifica `docs/api-spec.yml` matches handlers Spring (TASK-BE-07)
- [ ] 13.4 (CHANGE `flyway-runtime`) — Flyway como runner de migraciones a Supabase remoto (reemplaza el paso manual del operador) (TASK-DB-06)
- [ ] 13.5 (CHANGE `merchant-management-ui`) — Endpoints `/api/v1/merchants` + UI para merge/delete/rename — Épica 4
- [ ] 13.6 (CHANGE `backend-mvp-merchant-backfill`) — Job opcional que asigna `merchant_id` a las 362 filas históricas (TASK-DB-07)
