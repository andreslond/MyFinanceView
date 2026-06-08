# backend-rest-api Specification

## Purpose
Read-only REST API surface for MyFinanceView. Defines the four MVP endpoints (`GET /api/v1/transactions`, `PATCH /api/v1/transactions/{id}/category`, `GET /api/v1/accounts`, `GET /api/v1/categories`), JWT authentication via Supabase JWKS, user-scoped data isolation, CORS configuration, RFC 7807 error responses, and supporting concerns (BigDecimal serialization, Actuator hardening). Created by archiving change `backend-mvp-readonly`.

## Requirements

### Requirement: JWT authentication via Supabase JWKS (ES256)

El sistema SHALL validar cada request a `/api/v1/**` contra un JWT emitido por Supabase Auth presente en el header `Authorization: Bearer <token>`. La validación SHALL usar **Spring Security OAuth2 Resource Server** con `NimbusJwtDecoder.withJwkSetUri(...)` apuntando al endpoint público JWKS de Supabase (`app.auth.supabase.jwks-uri`, env var `SUPABASE_JWT_JWKS_URI`). El sistema SHALL verificar: (a) firma asimétrica `ES256` contra una clave del JWKS (rotación automática vía cache de 5 min del decoder), (b) claim `exp` no expirado, (c) claim `iss` exacto contra `app.auth.supabase.issuer`, (d) claim `aud` igual a `"authenticated"`, (e) claim `sub` parseable como `UUID`. El `userId` resultante SHALL exponerse a controllers vía `@AuthenticationPrincipal UUID userId`. **Únicamente** `/actuator/health` SHALL permanecer público; cualquier otro path del actuator SHALL devolver 404 (no 403 — sin exponer la existencia del path).

#### Scenario: JWT válido es aceptado
- **WHEN** un cliente envía `GET /api/v1/transactions` con un `Authorization: Bearer <jwt-firmado-por-supabase>`
- **THEN** la request alcanza el controller con `userId` correctamente populado, y la response tiene status 200

#### Scenario: Request sin Authorization header
- **WHEN** un cliente envía `GET /api/v1/transactions` sin header `Authorization`
- **THEN** el sistema responde 401 con body `ProblemDetail` tipo `https://myfinanceview.local/errors/unauthorized` y `detail` genérico

#### Scenario: Authorization header malformado
- **WHEN** un cliente envía `GET /api/v1/transactions` con `Authorization: NotBearer xyz`, `Authorization: Bearer` (sin token), `Authorization: <token-sin-prefijo>`, o `Authorization: Bearer abc.def` (JWT con solo 2 segmentos en vez de 3 — parser falla antes de la verificación de firma)
- **THEN** el sistema responde 401 y el body NO contiene ninguna substring del header recibido

#### Scenario: JWT firmado con clave EC distinta a la del JWKS
- **WHEN** un cliente envía un JWT firmado por un par de claves EC P-256 distinto al que sirve el JWKS de Supabase
- **THEN** el sistema responde 401 sin alcanzar el controller, y el body del ProblemDetail NO ecoa el token ni el `kid`

#### Scenario: JWT con algoritmo no permitido
- **WHEN** un cliente envía un JWT firmado con `alg: HS256` o `alg: none`
- **THEN** el sistema responde 401 (el decoder rechaza algoritmos no listados en el JWKS)

#### Scenario: JWT expirado
- **WHEN** un cliente envía un JWT con `exp` en el pasado
- **THEN** el sistema responde 401

#### Scenario: JWT con issuer inesperado
- **WHEN** un cliente envía un JWT con `iss` distinto al configurado en `app.auth.supabase.issuer`
- **THEN** el sistema responde 401

#### Scenario: JWT con audience inesperada
- **WHEN** un cliente envía un JWT con `aud` distinto a `"authenticated"` (e.g. `"service_role"`)
- **THEN** el sistema responde 401

#### Scenario: JWT con `sub` no UUID
- **WHEN** un cliente envía un JWT con `sub: "not-a-uuid"`
- **THEN** el sistema responde 401 (parseo del claim falla en el `Converter<Jwt, AbstractAuthenticationToken>`)

#### Scenario: JWKS rotation
- **GIVEN** un decoder con caché de JWKS cargado para `kid=K1`
- **WHEN** Supabase rota la clave a `kid=K2` y un cliente envía un JWT firmado con `K2`
- **THEN** el sistema invalida el cache, refresca el JWKS, verifica con `K2`, y responde 200

#### Scenario: Endpoint público `/actuator/health` no requiere JWT
- **WHEN** un cliente envía `GET /actuator/health` sin Authorization header
- **THEN** el sistema responde 200 con `{"status":"UP"}` sin detalles de DB connection

#### Scenario: `/actuator/info` y otros endpoints del actuator devuelven 404
- **WHEN** un cliente envía `GET /actuator/info`, `GET /actuator/env`, `GET /actuator/configprops`, o `GET /actuator/heapdump` (con o sin JWT)
- **THEN** el sistema responde 404 — el path no se expone (no 403 ni 401, para no filtrar la existencia)

### Requirement: User-scoped data access (defense equivalent to RLS)

El backend SHALL conectar a Supabase con `service_role` (RLS-bypassed) y SHALL agregar `WHERE user_id = ?` a TODAS las queries de tablas user-owned, usando el `userId` extraído del JWT. NO se permite query sin filtro por `userId` excepto sobre tablas system (`banks`, `exchange_rates`, `categories` con `user_id IS NULL`). El sistema SHALL aplicar **visibility guards** análogos para inputs que referencien IDs ajenos potencialmente cross-user (e.g. `categoryId` en PATCH).

#### Scenario: List de transacciones aísla por userId
- **WHEN** userA hace `GET /api/v1/transactions`
- **THEN** la response SOLO contiene transacciones con `user_id == userA.id`; las transacciones de userB son inalcanzables aunque compartan `account_id` o `category_id`

#### Scenario: PATCH no puede mutar transacciones de otro usuario
- **WHEN** userA envía `PATCH /api/v1/transactions/{txId}/category` con `txId` que pertenece a userB
- **THEN** el sistema responde 404 (no 403, para evitar enumeration) y NO modifica la fila de userB

#### Scenario: PATCH no puede asignar categoryId de otro usuario (anti-IDOR)
- **GIVEN** userB tiene una categoría custom `cat-B-private` (`user_id == userB`); userA no tiene esa categoría
- **WHEN** userA envía `PATCH /api/v1/transactions/{tx-A-id}/category` con body `{"categoryId": "<cat-B-private>"}`
- **THEN** el sistema responde 404 con `ProblemDetail` tipo `https://myfinanceview.local/errors/not-found`, sin diferenciar entre "no existe" y "pertenece a otro usuario"; la transacción `tx-A-id` permanece sin modificar (`updated_at` no cambia); `cat-B-private` permanece sin modificar; el body del ProblemDetail NO contiene el UUID rechazado

#### Scenario: PATCH con accountId cross-user en query filter es ignorado
- **GIVEN** userA y userB cada uno con sus propias cuentas; userB tiene `account-B`
- **WHEN** userA hace `GET /api/v1/transactions?accountId=<account-B>`
- **THEN** la response es `{rows: [], page: 1, pageSize: 25, hasMore: false}` — el filtro queda intersectado con `WHERE user_id = userA` y no hay match

#### Scenario: Categorías system son visibles a todos
- **WHEN** cualquier user autenticado hace `GET /api/v1/categories`
- **THEN** la response contiene tanto las categorías custom del user (`user_id == userId`) como las categorías system (`user_id IS NULL`)

### Requirement: GET /api/v1/transactions with paginated filters

El sistema SHALL exponer `GET /api/v1/transactions` con query params `accountId` (UUID, opcional), `categoryIds` (CSV de UUIDs, opcional), `page` (int 1-based, default 1), `pageSize` (int, default 25, max 100). El response SHALL ser `PageDTO<TransactionDTO>` ordenado por `occurredAt DESC, id DESC`. `hasMore` SHALL calcularse pidiendo `pageSize+1` filas y truncando. Cuando un `accountId` o `categoryIds` referencia recursos ajenos al `userId`, SHALL devolver resultados vacíos (filtro AND con `user_id`), no error.

#### Scenario: Lista con paginación por defecto
- **WHEN** un user con 362 transacciones llama `GET /api/v1/transactions`
- **THEN** el response es `{ rows: [...25 items], page: 1, pageSize: 25, hasMore: true }`

#### Scenario: Filtro por cuenta
- **WHEN** un user llama `GET /api/v1/transactions?accountId=<uuidA>` (cuenta propia)
- **THEN** todas las filas en `rows` tienen `accountId == uuidA`

#### Scenario: Filtro por múltiples categorías
- **WHEN** un user llama `GET /api/v1/transactions?categoryIds=<uuid1>,<uuid2>`
- **THEN** todas las filas en `rows` tienen `categoryId IN (uuid1, uuid2)`

#### Scenario: Paginación última página
- **WHEN** un user con exactamente 50 transacciones llama `page=2&pageSize=25`
- **THEN** el response es `{ rows: [...25 items], page: 2, pageSize: 25, hasMore: false }`

#### Scenario: Página más allá del final
- **WHEN** un user con 25 transacciones llama `page=2&pageSize=25`
- **THEN** el response es `{ rows: [], page: 2, pageSize: 25, hasMore: false }`

#### Scenario: pageSize fuera de rango se rechaza
- **WHEN** un user envía `pageSize=500` o `pageSize=0` o `pageSize=-1`
- **THEN** el sistema responde 400 con `ProblemDetail` tipo `https://myfinanceview.local/errors/bad-request` y campo `properties.errors` listando la violación; el body NO ecoa el valor recibido

#### Scenario: page fuera de rango se rechaza
- **WHEN** un user envía `page=0` o `page=-5`
- **THEN** el sistema responde 400

### Requirement: PATCH /api/v1/transactions/{id}/category — idempotent with merchant feedback loop and drift detection

El sistema SHALL aceptar `PATCH /api/v1/transactions/{id}/category` con body `{"categoryId": UUID}`. El body SHALL ser obligatorio y `categoryId` no-null; ausencia, null, o UUID inválido SHALL devolver 400. Si la transacción no existe o no pertenece al `userId`, SHALL responder 404. **Antes** del UPDATE, SHALL aplicar visibility guard sobre `categoryId` (categoría system o del usuario); si no es visible, SHALL responder 404 sin echo del UUID. Si `body.categoryId` coincide con el `currentCategoryId` de la transacción, SHALL responder 200 con el DTO actual SIN tocar la DB (idempotencia exacta). Si difiere, SHALL ejecutar en una sola transacción (un único `@Transactional`): (1) `UPDATE` de `transactions.category_id` y `updated_at`, (2) feedback loop a `myfinance.merchants` según D5 del design.md (incremento `+0.10` cap `1.00` cuando merchant ya estaba en esa categoría; **reset** del merchant a `category_id = nuevo, confidence = 0.50, match_count = 1` cuando hay drift; UPSERT idempotente si la transacción no tenía merchant).

#### Scenario: Cambio de categoría con merchant existente confirma la categoría aprendida (re-confirmación)
- **GIVEN** una transacción con `merchantId != null`; el merchant tiene `category_id = catX`, `confidence = 0.60`, `match_count = 3`
- **WHEN** el user cambia la categoría de la transacción a `catX` (la misma del merchant)
- **THEN** la response es 200 con el DTO actualizado; el merchant queda con `category_id = catX`, `confidence = 0.70`, `match_count = 4`, `last_confirmed_at = NOW()`, `updated_at = NOW()`

#### Scenario: Confidence capped en 1.00
- **GIVEN** un merchant con `category_id = catX`, `confidence = 0.95`
- **WHEN** el user re-confirma `catX` en una transacción asociada
- **THEN** el merchant queda con `confidence = 1.00` (no 1.05)

#### Scenario: Drift — el user cambia la categoría a una distinta de la aprendida por el merchant
- **GIVEN** una transacción con `merchantId != null`; el merchant tiene `category_id = catX`, `confidence = 0.90`, `match_count = 5`
- **WHEN** el user cambia la categoría de la transacción a `catY` (distinta de `catX`, ambas visibles para el user)
- **THEN** la response es 200; la transacción queda con `category_id = catY`; el merchant es **reset** a `category_id = catY`, `confidence = 0.50`, `match_count = 1`, `last_confirmed_at = NOW()`, `updated_at = NOW()`; se loguea un evento INFO `event=merchant_drift_reset` sin echo de plata ni descripción

#### Scenario: Transacción sin merchant resuelto crea uno (primer aprendizaje, UPSERT idempotente)
- **GIVEN** una transacción con `merchantId == null` y `description = "NETFLIX.COM *1234"`
- **WHEN** el user cambia la categoría a `catY`
- **THEN** se crea (o actualiza por UPSERT en caso de race) un merchant con `raw_pattern = "netflix.com"` (resultado de `MerchantNormalizer.normalize`), `display_name = "NETFLIX.COM *1234"`, `category_id = catY`, `confidence = 0.50`, `match_count = 1`, `last_confirmed_at = NOW()`; la transacción queda con `merchant_id` apuntando al merchant; ambos cambios viven en la misma transacción Postgres

#### Scenario: Idempotencia sin cambio (mismo categoryId)
- **GIVEN** una transacción con `categoryId = catA`
- **WHEN** el user envía `PATCH` con body `{"categoryId": "<catA>"}`
- **THEN** la response es 200 con el DTO actual; `transactions.updated_at` NO cambia; el merchant asociado NO recibe incremento de `match_count` ni de `confidence`; `last_confirmed_at` NO cambia

#### Scenario: Transacción no existe
- **WHEN** el user envía `PATCH /api/v1/transactions/<uuid-random>/category` con body válido
- **THEN** la response es 404 con `ProblemDetail` tipo `not-found`; el body NO contiene el UUID enviado

#### Scenario: `categoryId` apunta a categoría de otro usuario (anti-IDOR)
- **GIVEN** userB tiene `cat-B-private` (`user_id == userB`); userA tiene una transacción válida
- **WHEN** userA envía `PATCH /api/v1/transactions/{tx-A}/category` con body `{"categoryId": "<cat-B-private>"}`
- **THEN** la response es 404; la transacción `tx-A` NO se modifica; `cat-B-private` NO se modifica; el body NO contiene el UUID rechazado

#### Scenario: `categoryId` no existe en absoluto
- **WHEN** el user envía PATCH con body `{"categoryId": "00000000-0000-0000-0000-000000000000"}`
- **THEN** la response es 404 (mismo tratamiento que IDOR — sin diferenciar)

#### Scenario: Body con `categoryId` ausente o null
- **WHEN** el user envía PATCH con body `{}` o `{"categoryId": null}`
- **THEN** la response es 400 con `ProblemDetail` tipo `bad-request` y `properties.errors` listando `categoryId: must not be null`

#### Scenario: Body con UUID inválido
- **WHEN** el user envía PATCH con body `{"categoryId": "not-a-uuid"}`
- **THEN** la response es 400 con `ProblemDetail` tipo `bad-request`

#### Scenario: Atomicidad — rollback si el feedback loop falla
- **GIVEN** un escenario artificial donde el UPDATE de `merchants` falla (e.g. FK violation por inconsistencia inducida por test)
- **WHEN** el user dispara un PATCH que requiere actualizar ese merchant
- **THEN** la transacción Postgres hace rollback completo; `transactions.category_id` NO cambia; el merchant NO cambia; la response es 500 con `ProblemDetail` genérico (sin nombres de tablas ni stack trace)

### Requirement: GET /api/v1/accounts

El sistema SHALL exponer `GET /api/v1/accounts` que retorna `List<AccountDTO>` con las cuentas del `userId` ordenadas por `name ASC`. **Mapping nota:** `AccountDTO.name` se mapea desde la columna `accounts.nickname` del schema (ver D6 del design.md); no existe columna `accounts.name`.

#### Scenario: Cuentas del usuario
- **WHEN** un user con 3 cuentas llama el endpoint
- **THEN** la response es un array de 3 `AccountDTO` ordenado por `name` (alfabéticamente por el valor de `accounts.nickname`)

#### Scenario: `AccountDTO.name` se sirve desde `accounts.nickname`
- **GIVEN** una cuenta con `accounts.nickname = "Davivienda Signature"`
- **WHEN** el user llama el endpoint
- **THEN** la response contiene un `AccountDTO` con `"name": "Davivienda Signature"` (no `"nickname"`)

#### Scenario: Sin cuentas
- **WHEN** un user nuevo sin cuentas llama el endpoint
- **THEN** la response es un array vacío `[]` con status 200

### Requirement: GET /api/v1/categories

El sistema SHALL exponer `GET /api/v1/categories` que retorna `List<CategoryDTO>` con: (a) todas las categorías system (`user_id IS NULL`) + (b) las categorías custom del `userId`, ordenadas por `display_name ASC`. **Mapping nota:** `CategoryDTO.name` se mapea desde `categories.display_name` (ES, NOT NULL post-V004); el shape `parentId` que el MVP frontend inventó **NO se incluye** en este DTO (ver D6 del design.md; el frontend swap-out lo elimina del tipo TS).

#### Scenario: System + custom merged
- **WHEN** un user con 2 categorías custom llama el endpoint, y hay 19 categorías system
- **THEN** la response contiene 21 `CategoryDTO` en orden alfabético por `display_name`

#### Scenario: `CategoryDTO.name` se sirve desde `categories.display_name`
- **GIVEN** una categoría system con `categories.name = "Dining Out"` (V003 seed en inglés) y `categories.display_name = "Restaurantes y Cafés"` (V004 backfill)
- **WHEN** el user llama el endpoint
- **THEN** la response contiene `{"id": "...", "name": "Restaurantes y Cafés", "type": "expense", ...}` — sin `parentId`

#### Scenario: `CategoryDTO` no incluye `parentId`
- **WHEN** cualquier user llama el endpoint
- **THEN** ningún elemento del array contiene la propiedad `parentId` (verificable por JSON schema strict mode contra `docs/api-spec.yml`)

#### Scenario: Categoría custom de otro usuario no es visible
- **WHEN** userA llama el endpoint
- **THEN** ninguna categoría custom de userB aparece en la response

### Requirement: ProblemDetail error responses (RFC 7807) sin echo de inputs sensibles

El sistema SHALL responder a errores 4xx y 5xx con bodies que matchen RFC 7807 `ProblemDetail`: `{type, title, status, detail, instance}`. Los tipos SHALL usar el prefijo `https://myfinanceview.local/errors/`. El body NO SHALL exponer: stack traces, nombres de tablas/columnas internas, JWTs o partes de JWTs, claims del JWT, UUIDs rechazados por visibility guard, descripciones de transacciones, `external_id`, ni `raw_payload`. El `detail` SHALL ser genérico por tipo.

#### Scenario: 404 ProblemDetail sin echo del UUID
- **WHEN** el sistema responde 404 ante un PATCH con `categoryId` no visible
- **THEN** el body es `{ "type": "https://myfinanceview.local/errors/not-found", "title": "Resource not found", "status": 404, "detail": "Resource not found", "instance": "/api/v1/transactions/{id}/category" }` y NO contiene el UUID enviado por el cliente

#### Scenario: 401 ProblemDetail sin echo del token
- **WHEN** el sistema responde 401 ante un JWT inválido
- **THEN** el body es `{ "type": "https://myfinanceview.local/errors/unauthorized", "title": "Unauthorized", "status": 401, "detail": "Unauthorized" }` y NO contiene ninguna substring del JWT recibido (verificado asertando que las primeras 16 chars del token no aparecen en el body)

#### Scenario: 400 con violaciones de validación, sin echo de valores
- **WHEN** el sistema responde 400 por `pageSize=500`
- **THEN** el body incluye `properties.errors` con la lista de violaciones `[{"field": "pageSize", "message": "must be between 1 and 100"}]` — el valor `500` NO aparece en el body

#### Scenario: 500 sin stack trace ni nombres de schema
- **WHEN** el sistema captura una excepción no manejada (e.g. NPE en el mapper)
- **THEN** el body NO contiene stack trace, nombre de la excepción, ni nombres de tablas/columnas; el `detail` es `"An unexpected error occurred"`

### Requirement: CORS allowlist explícito con rechazo 403

El sistema SHALL permitir CORS solo para Origins listados en `app.cors.allowed-origins` (config vía `APP_CORS_ALLOWED_ORIGINS` env var); SHALL incluir por default `http://localhost:5173` (solo en perfiles `local`/`test`) y el pattern `https://*.vercel.app`. Métodos permitidos: `GET, PATCH, OPTIONS`. Headers permitidos: `Authorization, Content-Type`. Credenciales: NO (el frontend envía `Authorization` header, no cookies). Preflight desde Origin no permitido SHALL devolver **403 Forbidden** con body vacío.

#### Scenario: Origin permitido pasa preflight
- **WHEN** un browser envía preflight `OPTIONS /api/v1/transactions` con `Origin: http://localhost:5173`, `Access-Control-Request-Method: GET`, `Access-Control-Request-Headers: Authorization`
- **THEN** la response es 200 con `Access-Control-Allow-Origin: http://localhost:5173`, `Access-Control-Allow-Methods: GET, PATCH, OPTIONS`, `Access-Control-Allow-Headers: Authorization, Content-Type`, sin `Access-Control-Allow-Credentials`

#### Scenario: Origin Vercel preview es permitido por pattern matching
- **WHEN** un browser envía preflight con `Origin: https://myfinanceview-abc123.vercel.app`
- **THEN** la response es 200 con `Access-Control-Allow-Origin: https://myfinanceview-abc123.vercel.app`

#### Scenario: Origin desconocido recibe 403 sin headers CORS
- **WHEN** un browser envía preflight con `Origin: https://evil.com`
- **THEN** la response es **403 Forbidden** con body vacío y SIN headers `Access-Control-Allow-*`; el browser bloquea la request real

### Requirement: BigDecimal serialization preserves precision

Todos los campos de tipo `BigDecimal` (notably `TransactionDTO.amount`) SHALL serializarse a JSON como string vía `toPlainString()`. NO SHALL emitirse como número JSON. Cuando llegan en requests SHALL parsearse desde string sin pasar por `double`/`float`.

#### Scenario: amount se serializa como string
- **WHEN** una `TransactionDTO` con `amount = new BigDecimal("12345.67")` se serializa
- **THEN** el JSON output contiene `"amount": "12345.67"` (con quotes)

#### Scenario: scale preservado en serialización
- **WHEN** una `TransactionDTO` con `amount = new BigDecimal("0.10")` (scale 2) se serializa
- **THEN** el JSON output contiene `"amount": "0.10"` (no `"0.1"`)

### Requirement: Actuator surface hardened to `/health` only

El sistema SHALL exponer únicamente `/actuator/health` en la superficie HTTP de Spring Boot Actuator. Los endpoints `/actuator/info`, `/actuator/env`, `/actuator/configprops`, `/actuator/heapdump`, `/actuator/threaddump`, `/actuator/beans`, `/actuator/mappings`, `/actuator/metrics`, y cualquier otro SHALL devolver 404 (deshabilitados via `management.endpoint.{name}.enabled=false` + `management.endpoints.web.exposure.include=health`). `/actuator/health` SHALL responder con `{"status": "UP"}` sin detalles de componentes (`show-details: never`, `show-components: never`) para no filtrar el state de la DB connection a clientes no autenticados.

#### Scenario: `/actuator/health` responde sin detalles
- **WHEN** un cliente envía `GET /actuator/health`
- **THEN** la response es 200 con body `{"status": "UP"}` exactamente — sin claves `components`, `details`, `db`, ni similares

#### Scenario: `/actuator/info` está deshabilitado
- **WHEN** un cliente envía `GET /actuator/info`
- **THEN** la response es 404 (no 200 con body vacío, no 403)

#### Scenario: `/actuator/env` está deshabilitado
- **WHEN** un cliente envía `GET /actuator/env` (con o sin JWT)
- **THEN** la response es 404; ninguna env var (`SUPABASE_JWT_JWKS_URI`, `APP_CORS_ALLOWED_ORIGINS`, etc.) es accesible vía este path

#### Scenario: `/actuator/configprops` está deshabilitado
- **WHEN** un cliente envía `GET /actuator/configprops`
- **THEN** la response es 404

#### Scenario: `/actuator/heapdump` está deshabilitado
- **WHEN** un cliente envía `GET /actuator/heapdump`
- **THEN** la response es 404 (sin descargar heap)
