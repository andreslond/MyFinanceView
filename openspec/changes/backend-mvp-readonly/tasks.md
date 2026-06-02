## 1. Migration V004 — `myfinance.merchants` + `transactions.merchant_id`

- [ ] 1.1 Crear `backend/database/migrations/V004__merchants.sql` con tabla `merchants` (D8) y FK `transactions.merchant_id`
- [ ] 1.2 Agregar policies RLS `merchants_select_own`, `merchants_insert_own`, `merchants_update_own`, `merchants_delete_own` análogas a `accounts_*` de V002
- [ ] 1.3 Probar V004 contra el Docker Compose Postgres 17 local: `docker compose down -v && docker compose up -d` y verificar que V001..V004 aplican sin errores
- [ ] 1.4 Test Testcontainers en `db/V004MigrationTest.java`: arranca Postgres con V001..V004, asserta schema (`merchants` existe, FK `transactions.merchant_id` existe, policies aplicadas)
- [ ] 1.5 Actualizar `docs/data-model.md`: mover `merchants` de "Pending migrations" a "Applied", documentar columnas

## 2. Auth: Spring Security + Supabase JWT filter

- [ ] 2.1 Crear `config/SecurityConfig.java` — `@EnableWebSecurity`, `SecurityFilterChain` que protege `/api/v1/**`, permite `/actuator/health`, deshabilita CSRF (REST), session=STATELESS
- [ ] 2.2 Crear `config/SupabaseJwtFilter.java` extends `OncePerRequestFilter` — extrae `Authorization: Bearer`, valida HS256 con `JWT_SECRET`, valida `exp/iss/aud`, parsea `sub` como UUID, setea `SecurityContextHolder` con un `Authentication` custom
- [ ] 2.3 Crear `config/JwtClaimsExtractor.java` — parser de claims usando `nimbus-jose-jwt` o `jjwt`; validar `iss` y `aud` exactos contra config
- [ ] 2.4 Agregar dependency `io.jsonwebtoken:jjwt-api:0.12.x` + `jjwt-impl` + `jjwt-jackson` al `pom.xml`
- [ ] 2.5 Crear `config/CurrentUserArgumentResolver.java` que expone `@AuthenticationPrincipal UUID userId` a controllers (o usar el de Spring si compatible)
- [ ] 2.6 Test `SupabaseJwtFilterTest.java` (unit): JWT válido → 200; expirado → 401; iss equivocado → 401; aud equivocado → 401; firma equivocada → 401; sin header → 401; header malformado → 401
- [ ] 2.7 Test integración `SecurityIntegrationTest.java` con Testcontainers: hit a `/api/v1/transactions` sin auth → 401; con JWT válido seedeado → 200; con JWT de otro `userId` → ve solo sus filas

## 3. DTOs + Jackson config

- [ ] 3.1 Crear `api/dto/TransactionDTO.java` record con campos camelCase per D6
- [ ] 3.2 Crear `api/dto/AccountDTO.java` record (`id, userId, name, bankId, accountType, currency, createdAt, updatedAt`)
- [ ] 3.3 Crear `api/dto/CategoryDTO.java` record (`id, userId, name, displayName, parentId, createdAt, updatedAt`); `userId` nullable para system categories
- [ ] 3.4 Crear `api/dto/PageDTO.java<T>` record (`rows, page, pageSize, hasMore`)
- [ ] 3.5 Crear `api/dto/UpdateCategoryRequest.java` record (`categoryId: UUID`)
- [ ] 3.6 Crear `config/JacksonConfig.java` — registra `JavaTimeModule`, configura `BigDecimal` serializer custom que llama `toPlainString()` (D6)
- [ ] 3.7 Test `JacksonSerializationTest.java`: `TransactionDTO` se serializa con `amount: "12345.67"` (string), `occurredAt: "2026-06-01T15:30:00Z"` (ISO string), UUIDs como string

## 4. Repositorios jOOQ

- [ ] 4.1 Ejecutar codegen jOOQ contra el schema con V004 aplicado (TASK-BE-02 ya scaffolded; ejecutar `mvn -Pcodegen jooq-codegen:generate` o equivalente)
- [ ] 4.2 Crear `domain/account/AccountRepository.java` con `findAllByUserId(UUID userId): List<Account>` ordenado por `name ASC`
- [ ] 4.3 Crear `domain/category/CategoryRepository.java` con `findAllForUser(UUID userId): List<Category>` retornando `WHERE user_id IS NULL OR user_id = ?` ordenado por `display_name NULLS LAST, name ASC`
- [ ] 4.4 Crear `domain/transaction/TransactionRepository.java` con `findPage(userId, filters, page, pageSize): List<Transaction>` aplicando `WHERE user_id = ?` + filtros opcionales `accountId`, `categoryIds`, orden `occurred_at DESC, id DESC`, query con `LIMIT pageSize+1`
- [ ] 4.5 `TransactionRepository.findById(id, userId): Optional<Transaction>` con `WHERE id = ? AND user_id = ?`
- [ ] 4.6 `TransactionRepository.updateCategory(id, userId, categoryId): int` retornando rows affected
- [ ] 4.7 Crear `domain/merchant/MerchantRepository.java` con `findById(id, userId)`, `findByRawPattern(userId, rawPattern)`, `insert(...)`, `incrementConfidence(id, userId)`
- [ ] 4.8 Crear `domain/merchant/MerchantUpserter.java` que encapsula la lógica de feedback loop (D5) — método `applyFeedback(userId, txId, currentMerchantId, txDescription, newCategoryId, dsl: DSLContext)` que opera dentro de la transacción del caller
- [ ] 4.9 Crear `domain/merchant/MerchantNormalizer.java` con `normalize(description)` (D5, OQ4): lowercase, trim, strip `*\d{4}` final, collapse whitespace
- [ ] 4.10 Test `MerchantNormalizerTest.java` — casos: `"NETFLIX*1234"` → `"netflix"`; `"  RAPPI BOGOTA  "` → `"rappi bogota"`; emojis preservados
- [ ] 4.11 Test Testcontainers `TransactionRepositoryIsolationTest.java` — dos userIds; queries de userA no devuelven filas de userB ni siquiera con `WHERE` malformado (asertando defense in depth)
- [ ] 4.12 Test Testcontainers `MerchantUpserterTest.java` — escenarios: tx sin merchant + categoría nueva → merchant creado con confidence 0.50, match_count 1; tx con merchant + misma categoría → confidence +0.05 capped a 0.95; tx con merchant + cambio de categoría → ¿qué semántica? **OQ: documentar en design** — decisión por defecto: incrementa confidence del merchant existente (D5 nota "el frontend no envía nombre"); si la lógica de "merchant cambia de categoría" requiere split, queda como TASK-BE-09

## 5. Service layer + transacciones

- [ ] 5.1 Crear `domain/account/AccountService.java` con `listForUser(userId)` que delega al repo y mapea a DTO
- [ ] 5.2 Crear `domain/category/CategoryService.java` con `listForUser(userId)` que delega + mapea
- [ ] 5.3 Crear `domain/transaction/TransactionService.java`:
  - `listForUser(userId, filters, page, pageSize): PageDTO<TransactionDTO>` — query con `LIMIT pageSize+1`, calcula `hasMore`, truncea, mapea a DTO
  - `updateCategory(userId, txId, newCategoryId): TransactionDTO` — read-before-write para idempotencia (D5), si distinto abre `@Transactional` con `UPDATE transactions` + `MerchantUpserter.applyFeedback`, commit, retorna DTO actualizado
- [ ] 5.4 Configurar `spring.transaction.default-timeout` (sensato, e.g. 5s) en `application.yml`
- [ ] 5.5 Test `TransactionServiceIdempotencyTest.java` (Testcontainers) — mismo `categoryId` que current → NO se emite UPDATE (verificable por `updated_at` no cambia y `match_count` del merchant no incrementa)
- [ ] 5.6 Test `TransactionServiceFeedbackLoopTest.java` (Testcontainers) — cambio de categoría con `merchantId != null` → `merchants.confidence` incrementa hasta cap 0.95; con `merchantId == null` → merchant creado con confidence 0.50; transaccionalidad: provocar fallo en el segundo UPDATE asegura rollback del primero
- [ ] 5.7 Test `TransactionServicePaginationTest.java` (Testcontainers) — 100 filas seedeadas, `page=1, pageSize=25` → 25 filas + `hasMore=true`; `page=4, pageSize=25` → 25 filas + `hasMore=false`; `page=5` → 0 filas + `hasMore=false`

## 6. Controllers + ProblemDetail + CORS

- [ ] 6.1 Crear `api/controller/TransactionController.java` con `GET /api/v1/transactions` + `PATCH /api/v1/transactions/{id}/category`; usa `@AuthenticationPrincipal UUID userId`
- [ ] 6.2 Crear `api/controller/AccountController.java` con `GET /api/v1/accounts`
- [ ] 6.3 Crear `api/controller/CategoryController.java` con `GET /api/v1/categories`
- [ ] 6.4 Crear `api/exception/ProblemDetailAdvice.java` `@RestControllerAdvice` mapeando: `NotFoundException → 404`, `ForbiddenException → 403`, `MethodArgumentNotValidException → 400`, default exception → 500 sin stack trace en body
- [ ] 6.5 Crear `api/exception/NotFoundException.java`, `ForbiddenException.java` (runtime exceptions con mensaje)
- [ ] 6.6 Crear `config/CorsConfig.java` (D9) con `@Bean CorsConfigurationSource` registrando allowlist de Origins, métodos `GET, PATCH, OPTIONS`, headers `Authorization, Content-Type`, credentials false
- [ ] 6.7 Test REST-assured `TransactionControllerContractTest.java` — JWT válido + filtros → 200 + shape exacto; `accountId` inválido formato UUID → 400; tx inexistente en PATCH → 404; otro `userId` PATCH → 404 (no 403, evitar enumeration)
- [ ] 6.8 Test REST-assured `AccountControllerContractTest.java` — 200 con cuentas del user; sin auth → 401
- [ ] 6.9 Test REST-assured `CategoryControllerContractTest.java` — 200 con system + own; verifica que system categories están presentes para cualquier user
- [ ] 6.10 Test REST-assured `ProblemDetailContractTest.java` — verifica que el body de 4xx matches RFC 7807 (`type, title, status, detail, instance`)
- [ ] 6.11 Test `CorsConfigTest.java` — Origin `http://localhost:5173` → permitido; Origin `https://evil.com` → preflight rechazado

## 7. OpenAPI spec sync

- [ ] 7.1 Reescribir `docs/api-spec.yml`: añadir paths `/api/v1/transactions`, `/api/v1/transactions/{id}/category`, `/api/v1/accounts`, `/api/v1/categories` con sus parámetros, schemas, security
- [ ] 7.2 Agregar schemas `TransactionDTO`, `AccountDTO`, `CategoryDTO`, `PageDTO_TransactionDTO`, `UpdateCategoryRequest`, `ProblemDetail` (referencia RFC 7807)
- [ ] 7.3 Agregar `securitySchemes: bearerAuth (http, bearer, jwt)` y aplicar a todos los endpoints excepto `/actuator/health`
- [ ] 7.4 Verificar que el YAML es válido OpenAPI 3.1 (e.g. `openapi-cli validate` o herramienta equivalente)

## 8. Configuración + env vars

- [ ] 8.1 Actualizar `application.yml` con `app.auth.supabase.jwt-secret`, `app.auth.supabase.issuer`, `app.auth.supabase.audience` (defaults null/empty para forzar configuración explícita)
- [ ] 8.2 Actualizar `application-local.yml` con valores dummy de auth para tests locales sin Supabase real
- [ ] 8.3 Actualizar `application-test.yml` para que los tests Testcontainers generen su propio JWT secret
- [ ] 8.4 Actualizar `.env.example` con `JWT_SECRET=`, `SUPABASE_DB_URL=`, `SUPABASE_SERVICE_ROLE_KEY=`, `SUPABASE_JWT_ISSUER=`, `SUPABASE_JWT_AUDIENCE=`, `CORS_ALLOWED_ORIGINS=`
- [ ] 8.5 Documentar en `docs/development-guide.md` cómo correr el backend local apuntando a Supabase remoto (variables y precauciones)

## 9. Aplicar V004 a Supabase remoto (acción del operador)

- [ ] 9.1 Operator (no agente): backup pre-op del schema (Supabase MCP `list_migrations` previo, screenshot del estado de la tabla via `list_tables`)
- [ ] 9.2 Operator: Supabase MCP `apply_migration` con `name="v004_merchants"` y `query=<contenido de V004__merchants.sql>` contra `akkoqdjmmozyqdfjkabg`
- [ ] 9.3 Verificar `list_tables` reporta `myfinance.merchants` con `rls_enabled=true` y `transactions.merchant_id` existe
- [ ] 9.4 Verificar advisor de seguridad (Supabase MCP `get_advisors type=security`) no reporta nuevos issues

## 10. Cierre

- [ ] 10.1 Actualizar `progress.md` (last_completed, next_step, blockers)
- [ ] 10.2 Adversarial review — invocar skill `adversarial-review` o agente `adversarial-reviewer`; resolver Blockers/Majors
- [ ] 10.3 Commit con `/commit`, PR a `main`
- [ ] 10.4 Merge a `main`
- [ ] 10.5 Notion: marcar TASK-BE-03, TASK-BE-04, TASK-BE-05, TASK-BE-06 como done

## 11. Out of scope de este change (next changes)

- [ ] 11.1 (CHANGE `frontend-swap-to-backend`) — Reescribir body de `frontend/src/services/{transactions,accounts,categories}Service.ts` para `fetch('/api/v1/...')` con `Authorization: Bearer <session.access_token>`
- [ ] 11.2 (CHANGE `backend-deploy-{target}`) — Resolver OQ1, configurar deploy del backend Java; tareas dependen del target elegido
- [ ] 11.3 (CHANGE `api-spec-ci-gate`) — TASK-BE-07: gate CI que verifica `docs/api-spec.yml` matches handlers Spring
- [ ] 11.4 (CHANGE `flyway-runtime`) — TASK-DB-06: Flyway como runner de migraciones a Supabase remoto (reemplaza el paso manual del operador)
