## ADDED Requirements

### Requirement: JWT authentication via Supabase

El sistema SHALL validar cada request a `/api/v1/**` contra un JWT emitido por Supabase Auth presente en el header `Authorization: Bearer <token>`. La validación SHALL verificar firma HS256 contra `JWT_SECRET`, claim `exp` no expirado, claim `iss` exacto contra `app.auth.supabase.issuer`, claim `aud` exacto contra `app.auth.supabase.audience`, y claim `sub` parseable como `UUID`. El `userId` resultante SHALL exponerse a controllers vía `@AuthenticationPrincipal UUID userId`. `/actuator/health` y `/actuator/info` SHALL permanecer públicos.

#### Scenario: JWT válido es aceptado
- **WHEN** un cliente envía `GET /api/v1/transactions` con `Authorization: Bearer <jwt-válido>`
- **THEN** la request alcanza el controller con `userId` correctamente populado, y la response tiene status 200

#### Scenario: Request sin Authorization header
- **WHEN** un cliente envía `GET /api/v1/transactions` sin header `Authorization`
- **THEN** el sistema responde 401 con body `ProblemDetail` tipo `https://myfinanceview.local/errors/unauthorized`

#### Scenario: JWT con firma inválida
- **WHEN** un cliente envía un JWT firmado con secret distinto al de Supabase
- **THEN** el sistema responde 401 sin alcanzar el controller

#### Scenario: JWT expirado
- **WHEN** un cliente envía un JWT con `exp` en el pasado
- **THEN** el sistema responde 401

#### Scenario: JWT con issuer inesperado
- **WHEN** un cliente envía un JWT con `iss` distinto al configurado
- **THEN** el sistema responde 401

#### Scenario: JWT con audience inesperada
- **WHEN** un cliente envía un JWT con `aud` distinto a `"authenticated"`
- **THEN** el sistema responde 401

#### Scenario: Endpoints públicos no requieren JWT
- **WHEN** un cliente envía `GET /actuator/health` sin Authorization header
- **THEN** el sistema responde 200 con `{"status":"UP"}`

### Requirement: User-scoped data access (defense equivalent to RLS)

El backend SHALL conectar a Supabase con `service_role` (RLS-bypassed) y SHALL agregar `WHERE user_id = ?` a TODAS las queries de tablas user-owned, usando el `userId` extraído del JWT. NO se permite query sin filtro por `userId` excepto sobre tablas system (`banks`, `exchange_rates`, `categories` con `user_id IS NULL`).

#### Scenario: List de transacciones aísla por userId
- **WHEN** userA hace `GET /api/v1/transactions`
- **THEN** la response SOLO contiene transacciones con `userId == userA.id`; las transacciones de userB son inalcanzables aunque compartan `account_id` o `category_id`

#### Scenario: PATCH no puede mutar transacciones de otro usuario
- **WHEN** userA envía `PATCH /api/v1/transactions/{txId}/category` con `txId` que pertenece a userB
- **THEN** el sistema responde 404 (no 403, para evitar enumeration) y NO modifica la fila

#### Scenario: Categorías system son visibles a todos
- **WHEN** cualquier user autenticado hace `GET /api/v1/categories`
- **THEN** la response contiene tanto las categorías custom del user (`user_id == userId`) como las categorías system (`user_id IS NULL`)

### Requirement: GET /api/v1/transactions with paginated filters

El sistema SHALL exponer `GET /api/v1/transactions` con query params `accountId` (UUID, opcional), `categoryIds` (CSV de UUIDs, opcional), `page` (int 1-based, default 1), `pageSize` (int, default 25, max 100). El response SHALL ser `PageDTO<TransactionDTO>` ordenado por `occurredAt DESC, id DESC`. `hasMore` SHALL calcularse pidiendo `pageSize+1` filas y truncando.

#### Scenario: Lista con paginación por defecto
- **WHEN** un user con 489 transacciones llama `GET /api/v1/transactions`
- **THEN** el response es `{ rows: [...25 items], page: 1, pageSize: 25, hasMore: true }`

#### Scenario: Filtro por cuenta
- **WHEN** un user llama `GET /api/v1/transactions?accountId=<uuidA>`
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
- **WHEN** un user envía `pageSize=500`
- **THEN** el sistema responde 400 con `ProblemDetail` tipo `validation-error`

### Requirement: PATCH /api/v1/transactions/{id}/category — idempotent with merchant feedback loop

El sistema SHALL aceptar `PATCH /api/v1/transactions/{id}/category` con body `{categoryId: UUID}`. Si la transacción no existe o no pertenece al `userId`, SHALL responder 404. Si el `body.categoryId` coincide con el `currentCategoryId`, SHALL responder 200 con el DTO actual SIN tocar la DB (idempotencia). Si difiere, SHALL ejecutar en una sola transacción: (1) `UPDATE` de `transactions.category_id` y `updated_at`, (2) feedback loop a `myfinance.merchants` según D5 del design.md.

#### Scenario: Cambio de categoría con merchant resuelto incrementa confidence
- **GIVEN** una transacción con `merchantId != null` y el merchant tiene `confidence = 0.60, match_count = 3`
- **WHEN** el user cambia la categoría a una nueva válida
- **THEN** la response es 200 con el DTO actualizado, y el merchant queda con `confidence = 0.65, match_count = 4, last_confirmed_at = NOW()`

#### Scenario: Confidence capped en 0.95
- **GIVEN** un merchant con `confidence = 0.93`
- **WHEN** el user cambia la categoría de una transacción asociada
- **THEN** el merchant queda con `confidence = 0.95` (no 0.98)

#### Scenario: Transacción sin merchant resuelto crea uno
- **GIVEN** una transacción con `merchantId == null` y `description = "Netflix*1234"`
- **WHEN** el user cambia la categoría
- **THEN** se crea un merchant con `raw_pattern = "netflix"`, `display_name = "Netflix*1234"`, `category_id = <nuevo>`, `confidence = 0.50`, `match_count = 1`; y la transacción queda con `merchant_id` apuntando al nuevo merchant

#### Scenario: Idempotencia sin cambio
- **GIVEN** una transacción con `categoryId = uuidA`
- **WHEN** el user envía `PATCH` con body `{categoryId: uuidA}`
- **THEN** la response es 200 con el DTO actual, `transactions.updated_at` NO cambia, y el merchant asociado NO recibe incremento de `match_count`

#### Scenario: Transacción no existe
- **WHEN** el user envía PATCH a `/api/v1/transactions/<uuid-random>/category`
- **THEN** la response es 404

#### Scenario: Body con UUID inválido
- **WHEN** el user envía PATCH con body `{categoryId: "not-a-uuid"}`
- **THEN** la response es 400 con `ProblemDetail` tipo `validation-error`

#### Scenario: Atomicidad — rollback si el feedback loop falla
- **GIVEN** un merchant cuya FK `category_id` ha sido borrada inesperadamente (estado inconsistente artificial)
- **WHEN** el user dispara un PATCH que requiere actualizar ese merchant
- **THEN** la transacción hace rollback, `transactions.category_id` NO cambia, y la response es 500 con `ProblemDetail`

### Requirement: GET /api/v1/accounts

El sistema SHALL exponer `GET /api/v1/accounts` que retorna `List<AccountDTO>` con las cuentas del `userId` ordenadas por `name ASC`.

#### Scenario: Cuentas del usuario
- **WHEN** un user con 3 cuentas llama el endpoint
- **THEN** la response es un array de 3 `AccountDTO` ordenado por `name`

#### Scenario: Sin cuentas
- **WHEN** un user nuevo sin cuentas llama el endpoint
- **THEN** la response es un array vacío `[]` con status 200

### Requirement: GET /api/v1/categories

El sistema SHALL exponer `GET /api/v1/categories` que retorna `List<CategoryDTO>` con: (a) todas las categorías system (`user_id IS NULL`) + (b) las categorías custom del `userId`, ordenadas por `displayName ASC NULLS LAST, name ASC` con fallback de `displayName` a `name` cuando es null.

#### Scenario: System + custom merged
- **WHEN** un user con 2 categorías custom llama el endpoint, y hay 19 categorías system
- **THEN** la response contiene 21 `CategoryDTO` en orden alfabético por `displayName` (o `name` si el primero es null)

#### Scenario: Categoría custom de otro usuario no es visible
- **WHEN** userA llama el endpoint
- **THEN** ninguna categoría custom de userB aparece en la response

### Requirement: ProblemDetail error responses (RFC 7807)

El sistema SHALL responder a errores 4xx y 5xx con bodies que matchen RFC 7807 `ProblemDetail`: `{type, title, status, detail, instance}`. Los tipos SHALL usar el prefijo `https://myfinanceview.local/errors/`. El body NO SHALL exponer stack traces ni nombres de tablas/columnas internas.

#### Scenario: 404 ProblemDetail
- **WHEN** el sistema responde 404
- **THEN** el body es `{ "type": "https://myfinanceview.local/errors/not-found", "title": "Resource not found", "status": 404, "detail": "...", "instance": "/api/v1/..." }`

#### Scenario: 401 ProblemDetail
- **WHEN** el sistema responde 401
- **THEN** el body es `{ "type": "https://myfinanceview.local/errors/unauthorized", "title": "Unauthorized", "status": 401, ... }`

#### Scenario: 400 con violaciones de validación
- **WHEN** el sistema responde 400 por bad request
- **THEN** el body incluye un campo `errors` con la lista de violaciones (`{field, message}`)

#### Scenario: 500 sin stack trace
- **WHEN** el sistema captura una excepción no manejada
- **THEN** el body NO contiene stack trace ni nombres de tablas/columnas; el detalle es genérico (`"An unexpected error occurred"`)

### Requirement: CORS allowlist explícito

El sistema SHALL permitir CORS solo para Origins listados en `app.cors.allowed-origins` (config vía `CORS_ALLOWED_ORIGINS` env var); SHALL incluir por default `http://localhost:5173` y el pattern `https://*.vercel.app`. Métodos permitidos: `GET, PATCH, OPTIONS`. Headers permitidos: `Authorization, Content-Type`. Credenciales: NO.

#### Scenario: Origin permitido pasa preflight
- **WHEN** un browser envía preflight `OPTIONS /api/v1/transactions` con `Origin: http://localhost:5173`
- **THEN** la response es 200 con `Access-Control-Allow-Origin: http://localhost:5173`

#### Scenario: Origin Vercel preview es permitido
- **WHEN** un browser envía preflight con `Origin: https://myfinanceview-abc123.vercel.app`
- **THEN** la response es 200 con `Access-Control-Allow-Origin: https://myfinanceview-abc123.vercel.app`

#### Scenario: Origin desconocido se rechaza
- **WHEN** un browser envía preflight con `Origin: https://evil.com`
- **THEN** la response NO incluye `Access-Control-Allow-Origin` y el browser bloquea la request

### Requirement: BigDecimal serialization preserves precision

Todos los campos de tipo `BigDecimal` (notably `TransactionDTO.amount`) SHALL serializarse a JSON como string vía `toPlainString()`. NO SHALL emitirse como número JSON. Cuando llegan en requests (body de UpdateCategory no aplica, pero futuros endpoints sí), SHALL parsearse desde string sin pasar por `double`/`float`.

#### Scenario: amount se serializa como string
- **WHEN** una `TransactionDTO` con `amount = new BigDecimal("12345.67")` se serializa
- **THEN** el JSON output contiene `"amount": "12345.67"` (con quotes)

#### Scenario: scale preservado en serialización
- **WHEN** una `TransactionDTO` con `amount = new BigDecimal("0.10")` (scale 2) se serializa
- **THEN** el JSON output contiene `"amount": "0.10"` (no `"0.1"`)
