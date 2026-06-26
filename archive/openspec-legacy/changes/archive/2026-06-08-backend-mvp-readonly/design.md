## Context

El MVP frontend ya está desplegado en Vercel y consume Supabase PostgREST directamente vía `supabase-js` (decisión D1 de `mvp-frontend-readonly/design.md`). Tres deudas explícitas quedaron pendientes para el backend Java:
- Feedback loop a `myfinance.merchants` (D2 de ese design).
- DTOs reales en `docs/api-spec.yml` (D4 caveat).
- Agregaciones de plata, billing summary y dashboards (Non-Goals del MVP frontend).

Este design define cómo entregar el corte mínimo del backend Java que (a) reemplaza el camino directo a Supabase para los 4 endpoints que el MVP frontend ya usa y (b) implementa el feedback loop. El backend ya tiene scaffolding del change `2026-05-13-backend-scaffolding` (capability `backend-runtime`): bootstrap Spring Boot 3.4 + Java 25 + virtual threads + Hikari + jOOQ skeleton + `/actuator/health` + Docker Compose Postgres local + CI. Falta la capa REST + auth + feedback loop.

Restricciones del proyecto:
- [`docs/base-standards.md §2`](../../../docs/base-standards.md) — frontend nunca computa agregaciones de plata.
- [`docs/backend-standards.md §2`](../../../docs/backend-standards.md) — monolito modular por dominio. Sin clean architecture en capas. Sin JPA. Sin WebFlux.
- [`docs/backend-standards.md §10`](../../../docs/backend-standards.md) — `BigDecimal` para dinero (HALF_EVEN, scale 2), `OffsetDateTime` UTC en DB, `UUID` para IDs, DTOs como records inmutables, errores como `ProblemDetail`.
- [`SPEC.md §7`](../../../SPEC.md) — feedback loop a `myfinance.merchants` con `confidence`, `match_count`, `last_confirmed_at`.
- [`CLAUDE.md`](../../../CLAUDE.md) — preflight obligatorio, TDD, Testcontainers, `progress.md` por change.
- [`docs/workflow.md §2 Gate C`](../../../docs/workflow.md) — proposal SHALL declarar `## Threat model` (cumplido en `proposal.md`).

## Goals / Non-Goals

**Goals:**
- Backend Java REST funcional con auth JWT Supabase y 4 endpoints (`GET transactions/accounts/categories`, `PATCH transactions/{id}/category`) que el MVP frontend pueda consumir con un swap del body de los services.
- Feedback loop a `myfinance.merchants` operativo desde el PATCH, transaccional con el UPDATE de `transactions`.
- DTOs publicados en `docs/api-spec.yml` para que el frontend tenga contrato real (cierra D4 caveat del MVP frontend).
- Aislamiento por usuario garantizado en código (no en RLS) — el backend usa `service_role` y filtra por `userId` del JWT en cada query.
- Tests Testcontainers para todo (cero mocks de DB, regla del proyecto).

**Non-Goals:**
- Billing summary, breakdown por categoría, proyección de cierre, vistas analíticas. Quedan para Épica 5.
- Notifications, webhooks, Telegram. Épica 5.
- Budgets endpoints (CRUD). Épica 4.
- Savings goals. Épica 4.
- Multi-user / sharing / invitaciones. SPEC.md describe single-user.
- Rate limiting / WAF — Vercel/Cloudflare frente, no backend.
- Deploy productivo del backend — TASK-BE-08 cierra cuando el operador decida target (OQ1).
- Refactor del frontend — eso es un change posterior `frontend-swap-to-backend`.
- Migrar a Flyway runtime — TASK-DB-06 separado.

## Decisions

### D1 — Auth JWT Supabase via JWKS asimétrico (ES256), Spring Security OAuth2 Resource Server

> **Origen del cambio:** adversarial-review 2026-06-02 (Blocker B7) + Major M12. La asunción inicial "Supabase usa HS256 con secret simétrico" fue refutada por un probe directo al endpoint público de Supabase. Sostener D1 en su versión inicial habría producido **outage de auth al 100% en producción** porque `NimbusJwtDecoder.withSecretKey(...)` no puede verificar firmas EC.

Decisión: el backend valida el JWT emitido por Supabase Auth en cada request usando **Spring Security OAuth2 Resource Server** con **`NimbusJwtDecoder.withJwkSetUri(...)`** apuntando al JWKS público de Supabase. No emite cookies, no mantiene sesión, no rota tokens. El `Authorization: Bearer <jwt>` lo trae el frontend desde su `useAuth()` actual.

Probe verificado contra `https://akkoqdjmmozyqdfjkabg.supabase.co/auth/v1/.well-known/jwks.json` el 2026-06-02 (ver `notes/adversarial-review-2026-06-02.md` B7):

```json
{"keys":[{"alg":"ES256","crv":"P-256","kid":"312a0a41-87d7-4797-b25b-df8940280445","kty":"EC","use":"sig","x":"...","y":"..."}]}
```

Validación:
- Algoritmo `ES256` (ECDSA P-256). El `NimbusJwtDecoder` rota automáticamente cuando Supabase rota la clave (refresh JWKS bajo demanda con caché de 5 min por default).
- JWKS URI configurado vía `app.auth.supabase.jwks-uri` (env var `SUPABASE_JWT_JWKS_URI`, default `https://akkoqdjmmozyqdfjkabg.supabase.co/auth/v1/.well-known/jwks.json`).
- Claim `exp` no expirado (default validator de Nimbus).
- Claim `iss == "https://akkoqdjmmozyqdfjkabg.supabase.co/auth/v1"` (`JwtIssuerValidator`).
- Claim `aud == "authenticated"` (`JwtClaimValidator<List<String>>` custom).
- Claim `sub` parseado como `UUID` por un `Converter<Jwt, AbstractAuthenticationToken>` custom → expuesto vía `@AuthenticationPrincipal UUID userId`.
- Si cualquier validación falla: `401 Unauthorized` con `ProblemDetail` tipo `unauthorized` y `detail` genérico (sin echo del token ni del claim ofensor — ver D11).

**Estrategia de tests (resuelve M12 del adv-review):**
- `TestJwtFactory` genera un par de claves EC P-256 efímero al arrancar la suite (`KeyPairGenerator("EC")` + `ECGenParameterSpec("secp256r1")`).
- `WireMockServer` sirve un JWKS endpoint con la clave pública correspondiente.
- `@DynamicPropertySource` inyecta `app.auth.supabase.jwks-uri = ${wiremock.jwks.url}`.
- Métodos helper: `valid(UUID userId)`, `expired(UUID userId)`, `wrongAudience(UUID userId)`, `wrongIssuer(UUID userId)`, `signedWithDifferentKey(UUID userId)`, `malformed()`.
- Cobertura obligatoria por test: happy path + cada sad path + JWKS rotation (cambiar la clave en WireMock mid-test y asertar que el segundo request requiere refresh).

Alternativas descartadas:
- **HS256 + `JWT_SECRET` simétrico.** Refutado por probe — Supabase no expone el secret simétrico de servicio para verificación; el endpoint público es JWKS asimétrico.
- **`NimbusJwtDecoder.withSecretKey(...)` con `SUPABASE_JWT_SECRET`.** Mismo problema — no puede verificar firmas EC.
- **Filter custom con `java-jwt` o `jjwt`.** Reescribe lo que `OAuth2ResourceServerConfigurer` ya ofrece: cache, refresh, claim validators componibles. Más superficie, menos cobertura de borde.
- **Spring Session.** Introduce store propio para nada — el JWT es self-contained y el TTL lo controla Supabase.
- **Emitir cookie httpOnly del backend.** Doble session store (Supabase localStorage + cookie backend) que hay que mantener en sync. Vale la pena cuando haya deploy productivo permanente del backend; en MVP es overengineering.

Razón: máxima compatibilidad con el MVP frontend ya desplegado, **cero asunciones sin verificar** sobre la infra de auth de Supabase, y rotación de claves resuelta por el stack estándar de Spring sin código custom.

### D2 — Backend usa `service_role` y filtra por `userId` en código

Decisión: la conexión jOOQ usa el `SUPABASE_SERVICE_ROLE_KEY` (RLS-bypass). Todas las queries de repositorios agregan `WHERE user_id = ?` con el `userId` extraído del JWT. RLS de V002 sigue activa pero el backend la ignora — equivalente a haber escrito las mismas reglas en código Java.

Alternativas descartadas:
- **Backend conecta con sesión `authenticated` impersonando al usuario.** Requiere PG `SET LOCAL request.jwt.claims = ...` por transacción. Más fragmento, más ceremonia, performance penalty por SET por query, mismo resultado de aislamiento.
- **Backend usa rol custom dedicado.** Sería un tercer rol en Supabase, peor mantenibilidad.

Razón: defensa equivalente, performance mejor (sin SET LOCAL), 100% auditable por test. Los tests Testcontainers SHALL verificar que un `userId` no puede leer/mutar filas de otro `userId` aunque la SQL esté correcta — la regla "siempre WHERE user_id = ?" es chequeable por static analysis si hace falta más adelante.

### D3 — Capability nueva `backend-rest-api`, no extensión de `backend-runtime`

Decisión: este change crea capability nueva `backend-rest-api` en `openspec/specs/`. `backend-runtime` (scaffolding) queda intacta. Decisión justificada en `proposal.md`: el contrato REST + auth + feedback loop es una superficie funcional separada del runtime.

Alternativas descartadas:
- **Extender `backend-runtime` con MODIFIED Requirements.** El runtime cubre bootstrap, health, virtual threads, build. REST + auth + datos es un dominio nuevo. Mezclar diluye la spec.
- **Capability por endpoint (`transactions-api`, `accounts-api`, ...).** Granularidad excesiva. Auth + ProblemDetail + CORS aplican a todos.

Razón: separación clara de superficies; cuando aterrice TASK-DB-06 (Flyway), modificará `backend-runtime`; cuando aterrice TASK-BE-09 (budgets endpoints), modificará `backend-rest-api`. Cada change touch lo que toca, no más.

### D4 — Paginación cursor-less con `hasMore`, sin `count=exact`

Decisión: `GET /api/v1/transactions?page=N&pageSize=M` devuelve `{ rows: [], page, pageSize, hasMore }`. El backend pide `pageSize + 1` filas y truncea; `hasMore = (filasRecibidas > pageSize)`.

`pageSize` defaultea a 25, capped a 100. `page` 1-based.

Orden: `occurred_at DESC, id DESC` (segundo criterio para estabilidad cuando dos transacciones comparten timestamp — pasa con n8n batches).

Alternativas descartadas:
- **`count=exact` para devolver total.** PostgREST y jOOQ-on-Postgres requieren `COUNT(*) OVER ()` o segunda query — penaliza la query principal sin ganancia para la UX del MVP.
- **Cursor opaco basado en `(occurred_at, id)`.** Mejor para datasets enormes, pero el MVP tiene <500 filas. Page-based es trivial de implementar y el frontend ya espera ese shape.

Razón: paridad shape con el MVP frontend (`Page<T>` con `hasMore: boolean` per `frontend/src/services/types.ts`). Cuando llegue billing/dashboards y haya datasets grandes, se introduce cursor pagination como nuevo endpoint.

### D5 — PATCH idempotente + feedback loop transaccional con drift detection

> **Origen de cambios vs versión inicial:** adversarial-review 2026-06-02 (Blockers B5, B6; Major M2). Operator decisions 2026-06-02: (a) **SPEC.md gana** sobre el incremento → `+0.1` con cap `1.0` (no `+0.05` cap `0.95`); (b) cuando el usuario re-categorize una transacción cuyo merchant ya estaba mapeado a OTRA categoría, **resetear** el merchant en lugar de seguir incrementando (drift logic); (c) **visibility guard** del `categoryId` antes de UPDATE — ver D10.

Decisión: `PATCH /api/v1/transactions/{id}/category` body `{categoryId}`:

1. **Visibility guard del `categoryId`** (anti-IDOR — ver D10): verificar que `body.categoryId` exista y sea visible para `userId` (categoría del sistema con `user_id IS NULL`, o categoría propia del usuario). Si no es visible → **404 Not Found** (no 403, para no filtrar la existencia ajena). Atómico con la query siguiente.
2. Cargar transacción por `id` filtrando por `userId` del JWT. Si no existe o pertenece a otro usuario → **404 Not Found**.
3. Si `body.categoryId === tx.categoryId` → return 200 con el DTO actual, sin tocar DB. **Idempotencia exacta** (mismo cuerpo, mismo estado). **Ordering nota:** el guard del paso 1 corre **siempre antes** del short-circuit de idempotencia — la invariante de seguridad "no aceptar un PATCH con `categoryId` no visible al usuario" domina sobre la optimización de no-op. Edge case raro: si la categoría actual de la transacción fue borrada admin-side entre dos PATCHes con el mismo body, el segundo PATCH devuelve 404 — comportamiento técnicamente correcto (el recurso al que apuntan ya no es visible para el usuario) y aceptado.
4. Si difiere, abrir transacción jOOQ (un único `transactionTemplate.execute`):
   - `UPDATE myfinance.transactions SET category_id = ?, updated_at = NOW() WHERE id = ? AND user_id = ?` → afecta exactamente 1 fila (sino abort + rollback).
   - **Branch A — `tx.merchantId IS NOT NULL`:**
     - Cargar el merchant actual (`SELECT category_id, confidence FROM myfinance.merchants WHERE id = ? AND user_id = ?`).
     - **Si `merchant.category_id === body.categoryId`** (re-confirmación de la categoría existente del merchant):
       - `UPDATE myfinance.merchants SET confidence = LEAST(1.00, confidence + 0.10), match_count = match_count + 1, last_confirmed_at = NOW(), updated_at = NOW() WHERE id = ? AND user_id = ?`.
     - **Si `merchant.category_id !== body.categoryId`** (drift — el usuario discrepa con la categoría aprendida del merchant):
       - `UPDATE myfinance.merchants SET category_id = ?, confidence = 0.50, match_count = 1, last_confirmed_at = NOW(), updated_at = NOW() WHERE id = ? AND user_id = ?` — reset al estado "primer aprendizaje".
       - **`display_name` NO se modifica en drift** — el label visible del merchant permanece estable; solo cambia la categoría aprendida y se resetea la confianza. Razón: el `display_name` representa la identidad humana del comercio, ortogonal a su clasificación.
       - Log `INFO` estructurado: `event=merchant_drift_reset, merchant_id=…, old_category_id=…, new_category_id=…, user_id=…` (sin echo de plata ni descripción).
   - **Branch B — `tx.merchantId IS NULL`** (primer aprendizaje para este merchant):
     - Calcular `rawPattern = MerchantNormalizer.normalize(tx.description)` (ver D12 — congelado).
     - UPSERT idempotente: `INSERT INTO myfinance.merchants (user_id, raw_pattern, display_name, category_id, confidence, match_count, last_confirmed_at) VALUES (…, 0.50, 1, NOW()) ON CONFLICT (user_id, raw_pattern) DO UPDATE SET category_id = EXCLUDED.category_id, confidence = 0.50, match_count = 1, last_confirmed_at = NOW(), updated_at = NOW() RETURNING id` — protege contra race conditions con n8n ingestion concurrente.
     - `UPDATE myfinance.transactions SET merchant_id = ? WHERE id = ? AND user_id = ?` con el `id` devuelto.
5. Commit. Return 200 con el DTO actualizado (re-fetch de `transactions` para devolver `updatedAt` autoritativo).

Constraints:
- Cap de `confidence` a `1.00` (alineado con `SPEC.md §7` — operator decision 2026-06-02: SPEC.md gana sobre la versión inicial del design `+0.05`/`0.95`). Si ya está en `1.00`, queda en `1.00` (LEAST).
- Incremento por confirmación: `+0.10` (idem operator decision).
- `match_count` empieza en `1` para merchants nuevos, `+1` para existentes confirmados, **reset a 1** en drift.
- `raw_pattern` se genera por `MerchantNormalizer.normalize(String)` — **congelada en este change** (ver D12). Cualquier cambio futuro a `normalize` requiere su propio change con plan de re-mapeo.
- Todo Branch A/B vive dentro del mismo `@Transactional` (propagation REQUIRED, isolation READ_COMMITTED de Postgres por default). Si cualquier paso falla, todo rollbackea — sin window split-brain entre `transactions.category_id` actualizado y `merchants` sin actualizar.
- Logging del drift event en INFO **sin echo de plata, descripción, ni `raw_payload`** ([`docs/backend-standards.md §6`](../../../docs/backend-standards.md)).

Alternativas descartadas:
- **Trigger Postgres.** Rechazado en el adversarial review del MVP frontend (B4, B5). El feedback loop vive en código Java, no en SQL.
- **PATCH no idempotente — siempre touch.** Inflaría `match_count` con cada doble click del modal del frontend. Rompería la métrica.
- **No tocar `merchants` si `merchantId` es NULL.** Pierde el primer aprendizaje del usuario. Mejor crear el merchant con `confidence` modesto.
- **Drift = `confidence -= 0.20` (decrement suave).** Más conservador pero opaco para el usuario: dos confirmaciones contradictorias bajan `confidence` a `0.10` y siguen pegadas a la categoría incorrecta. Reset a `0.50` es más auditable y matchea el modelo mental "el usuario te corrigió, empezás de cero con esta categoría".
- **`UPSERT` en Branch A sin diferenciar drift vs re-confirmación.** Pierde el reset semánticamente — el merchant queda con `confidence` alto en la categoría correcta pero el contador acumula confirmaciones de categorías distintas.

Razón: idempotencia barata vía read-before-write, feedback loop transaccional sin window de split-brain, semántica alineada con SPEC.md §7, drift detection para que el aprendizaje del merchant respete el último input del usuario. Mantenibilidad alta porque toda la lógica vive en `MerchantUpserter` testeable con Testcontainers (un test por branch: re-confirm, drift, primer aprendizaje, race UPSERT, idempotency, visibility guard miss).

### D6 — DTOs alineados con el shape inventado por el MVP frontend (con mapeo backend ↔ schema)

> **Origen de cambios vs versión inicial:** adversarial-review 2026-06-02 (Blockers B3, B4). El shape inventado por el MVP frontend diverge del schema actual de `myfinance.*` en dos puntos críticos. Operator decision 2026-06-02: **mapear en backend** (no agregar columnas al schema, no romper el shape del frontend).

Decisión: los records Java DTO usan los nombres camelCase que `frontend/src/services/types.ts` ya inventó (`occurredAt`, `accountId`, `categoryId`, `merchantId`, `createdAt`, `updatedAt`). Jackson serializa con `@JsonProperty` cuando el nombre del record diverge del Java naming. Tipos:
- IDs: `UUID` → Jackson serializa como string.
- Tiempo: `OffsetDateTime` UTC → ISO 8601 string vía `jackson-datatype-jsr310` (registrado en `ObjectMapper` config).
- Money: `BigDecimal` → serializado como string vía `JsonSerializer<BigDecimal>` custom que llama `toPlainString()`. Garantiza que `"0.10"` no se vuelva `0.1`.
- Currency: `String` ISO 4217 (e.g. `"COP"`).

`PageDTO<T>` es genérico: `{ rows: List<T>, page: int, pageSize: int, hasMore: boolean }`.

**Mapeo backend ↔ schema (resuelve B3 y B4 del adv-review):**

| DTO field | Source column | Mapper rule |
|---|---|---|
| `AccountDTO.id` | `accounts.id` | direct |
| `AccountDTO.name` | `accounts.nickname` | **rename in mapper** — `accounts.nickname` es el "shape funcional" que el MVP frontend bautizó `name`. No se renombra la columna ni se agrega `accounts.name` al schema (proteger el contrato de jOOQ codegen y evitar conflict en V006+). El `AccountMapper.fromRecord(...)` hace `new AccountDTO(rec.getId(), rec.getNickname(), …)`. |
| `AccountDTO.last4` | `accounts.last4` | direct |
| `AccountDTO.type` | `accounts.type` | enum → string lowercase (`checking`/`savings`/`credit_card`) |
| `AccountDTO.currency` | `accounts.currency` | direct |
| `AccountDTO.active` | `accounts.active` | direct |
| `AccountDTO.createdAt` / `updatedAt` | `accounts.created_at` / `updated_at` | direct |
| `CategoryDTO.id` | `categories.id` | direct |
| `CategoryDTO.name` | `categories.display_name` | **post-V004** (display_name en español, NOT NULL). El backend depende de V004 aplicada a remoto antes del merge a `main` — no hay fallback runtime. Si por error el código aterriza antes de V004, `GET /api/v1/categories` falla con 500 y `ProblemDetail` genérico. La Migration Plan (§Migration Plan) hace cumplir el orden V004 antes del merge; el operator gate de `tasks.md §10` es la barrera operativa. |
| `CategoryDTO.type` | `categories.type` | enum → string (`expense`/`income`) |
| `CategoryDTO.color` | `categories.color` | direct, nullable |
| `CategoryDTO.icon` | `categories.icon` | direct, nullable |
| `~~CategoryDTO.parentId~~` | — | **DROP del shape del MVP frontend.** El schema `myfinance.categories` no tiene `parent_id` y no está planeado introducirlo en este change. El campo era especulación del MVP frontend; el adversarial review (B3) lo cazó. El DTO **no incluye** `parentId`; el frontend swap-out lo elimina del tipo TS. |
| `TransactionDTO.id` | `transactions.id` | direct |
| `TransactionDTO.accountId` | `transactions.account_id` | direct |
| `TransactionDTO.categoryId` | `transactions.category_id` | direct, nullable (transacciones sin categoría confirmada) |
| `TransactionDTO.merchantId` | `transactions.merchant_id` | direct, nullable (post-V005 — ver D8) |
| `TransactionDTO.type` | `transactions.type` | enum → string |
| `TransactionDTO.amount` | `transactions.amount` | BigDecimal → string |
| `TransactionDTO.currency` | `transactions.currency` | direct |
| `TransactionDTO.description` | `transactions.description` | direct |
| `TransactionDTO.occurredAt` | `transactions.occurred_at` | OffsetDateTime UTC |
| `TransactionDTO.createdAt` / `updatedAt` | `transactions.created_at` / `updated_at` | direct |

`raw_payload`, `notes`, `external_id`, `amount_base_currency`, `source` NO se exponen en `TransactionDTO` (PII / detalle interno). Si el frontend los necesita, se agregan en un change futuro con threat model dedicado.

Mappers viven en `domain/transaction/TransactionMapper`, `domain/account/AccountMapper`, `domain/category/CategoryMapper`. Static factory methods `fromRecord(Record)` y `fromEntity(Entity)`. Tests unitarios puros (no Testcontainers) verifican cada mapeo no-trivial (en particular `nickname → name`).

Alternativas descartadas:
- **Records `Transaction` directos sin DTO.** Acopla la entidad de dominio al wire. Cuando se agregue billing/categorización rica, el DTO necesita campos que la entidad no tiene.
- **`BigDecimal` como número JSON.** Riesgo de pérdida de precisión en JS (`Number(value)`). El frontend explícitamente espera string.
- **Agregar `accounts.name` al schema como columna real (rename de `nickname`).** Toca codegen de jOOQ, V004 existentes, queries en n8n. No vale el costo cuando un mapper de una línea soluciona el problema.
- **Agregar `categories.parent_id` al schema para honrar el shape del MVP frontend.** Adversarial review B3: el frontend inventó el campo sin que el schema lo respalde; agregarlo introduce data sin uso real (no hay UI de jerarquía categorial planeada). Drop del DTO es lo correcto.

Razón: cero refactor en el frontend cuando se haga el swap-out, **sin churn de schema**, sin agregar columnas especulativas. El mapper privado del frontend se borra en `frontend-swap-to-backend`; el `parentId` se elimina del tipo TS al mismo tiempo (cambio breaking aceptable porque ningún componente actual lo usa — verificable con grep en el frontend antes de swap).

### D7 — `docs/api-spec.yml` se llena con el contrato real en este change

Decisión: este change pobla `docs/api-spec.yml` con los 4 endpoints (`/api/v1/transactions{,/{id}/category}`, `/api/v1/accounts`, `/api/v1/categories`), los 5 schemas (`TransactionDTO`, `AccountDTO`, `CategoryDTO`, `PageDTO_TransactionDTO`, `UpdateCategoryRequest`), y los `ProblemDetail` responses para 400/401/403/404.

Mantener `docs/api-spec.yml` en sync con los handlers Spring requiere un CI gate adicional (e.g. springdoc generate vs committed file). Ese gate queda fuera de scope de este change (es TASK-BE-07 separado). En este change el archivo se escribe a mano alineado a los handlers.

Razón: el frontend (y cualquier futuro consumer) tiene contrato verificable desde el día 1 de este backend MVP. Si el handler diverge del YAML, el adversarial review lo cacha.

### D8 — Migraciones split: V004 `categories.display_name` + V005 `merchants`

> **Origen de cambios vs versión inicial:** adversarial-review 2026-06-02 (Blockers B1, B2). La versión inicial del design hijackeaba `V004` para `merchants`, pisando el `V004` ya planeado en [`docs/data-model.md §3`](../../../docs/data-model.md) para `categories.display_name`. El conflict habría producido o (a) renumbering forzado de toda la cola de migraciones (V005 cut/payment, V006 installments, V007 merchants, V008 categorization, V009 savings) o (b) un schema divergente entre `data-model.md` y la realidad de Supabase. Operator decision 2026-06-02: **respetar el plan original** — `V004 = display_name`, `V005 = merchants`. Toda la cola posterior corre +1 (V006 cut/payment → V007, V007 installments → V008, etc. — actualizado en `data-model.md` por Task #6).

Decisión: este change introduce **dos migraciones secuenciales**, ambas additive-only, aplicadas en orden por el operador post-adversarial review (vía Supabase MCP `apply_migration`):

**V004 — `categories.display_name` (ES, NOT NULL post-backfill):**

```sql
ALTER TABLE myfinance.categories ADD COLUMN display_name TEXT;
-- Backfill las 19 categorías del sistema con sus nombres en español
UPDATE myfinance.categories SET display_name = 'Restaurantes y Cafés' WHERE name = 'Dining Out' AND user_id IS NULL;
-- (18 más — seed completo en backend/database/migrations/V004__categories_display_name.sql)
ALTER TABLE myfinance.categories ALTER COLUMN display_name SET NOT NULL;
```

El seed de las 19 categorías del sistema (`user_id IS NULL`) usa el mapeo `name → display_name`. **Nota:** V003 seedeó las categorías con labels en inglés directamente en la columna `name` (`'Housing'`, `'Dining Out'`, `'Food & Groceries'`, …) — NO con snake_case keys (`'housing'`, `'restaurants_and_cafes'`). El backend respeta ese estado existente: `name` queda como la key interna que jOOQ codegen usa, y `display_name` es el label ES user-facing. Mapeo abreviado:

| `name` (V003, EN label) | `display_name` (V004, ES) |
|---|---|
| `Dining Out` | `Restaurantes y Cafés` |
| `Food & Groceries` | `Mercado y Supermercado` |
| `Transportation` | `Transporte` |
| `Entertainment` | `Entretenimiento` |
| `Housing` | `Hogar` |
| `Utilities` | `Servicios Públicos` |
| `Healthcare` | `Salud` |
| `Insurance` | `Seguros` |
| `Shopping` | `Compras` |
| `Education` | `Educación` |
| `Personal Care` | `Cuidado Personal` |
| `Subscriptions` | `Suscripciones` |
| `Debt Payments` | `Pago de Deudas` |
| `Other Expense` | `Otros Gastos` |
| `Salary` | `Salario` |
| `Freelance` | `Trabajo Freelance` |
| `Investments` | `Inversiones` |
| `Transfers In` | `Transferencias Recibidas` |
| `Other Income` | `Otros Ingresos` |

Categorías del usuario (`user_id IS NOT NULL`) post-backfill: 0 filas a la fecha del change (verificado en data-model.md §2), pero la migración escribe `display_name = name` como fallback defensivo si aparecieran filas user-owned entre el adversarial review y la aplicación a remoto.

**V005 — `merchants` table + `transactions.merchant_id` FK:**

```sql
CREATE TABLE myfinance.merchants (
  id                UUID PRIMARY KEY DEFAULT extensions.uuid_generate_v4(),
  user_id           UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  display_name      TEXT NOT NULL,
  raw_pattern       TEXT NOT NULL,
  category_id       UUID NOT NULL REFERENCES myfinance.categories(id),
  confidence        NUMERIC(3,2) NOT NULL DEFAULT 0.50 CHECK (confidence BETWEEN 0.00 AND 1.00),
  match_count       INT NOT NULL DEFAULT 0 CHECK (match_count >= 0),
  last_confirmed_at TIMESTAMPTZ,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (user_id, raw_pattern)
);

-- Trigger updated_at (consistente con accounts/transactions)
CREATE TRIGGER set_merchants_updated_at
  BEFORE UPDATE ON myfinance.merchants
  FOR EACH ROW EXECUTE FUNCTION myfinance.trigger_set_updated_at();

-- RLS análoga a accounts_* en V002
ALTER TABLE myfinance.merchants ENABLE ROW LEVEL SECURITY;
CREATE POLICY merchants_select_own ON myfinance.merchants FOR SELECT USING (user_id = auth.uid());
CREATE POLICY merchants_insert_own ON myfinance.merchants FOR INSERT WITH CHECK (user_id = auth.uid());
CREATE POLICY merchants_update_own ON myfinance.merchants FOR UPDATE USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());
CREATE POLICY merchants_delete_own ON myfinance.merchants FOR DELETE USING (user_id = auth.uid());

-- FK nueva en transactions
ALTER TABLE myfinance.transactions
  ADD COLUMN merchant_id UUID REFERENCES myfinance.merchants(id) ON DELETE SET NULL;
CREATE INDEX idx_transactions_merchant_id ON myfinance.transactions(merchant_id) WHERE merchant_id IS NOT NULL;
```

Las 362 filas históricas de `transactions` quedan con `merchant_id = NULL` hasta que el usuario las re-categorize (Branch B de D5).

**Aplicación a remoto (operator gate — task 9 en `tasks.md`):**

1. Operador ejecuta backup pre-V004 según `openspec/changes/supabase-backup-policy-replant` (snapshot manual a R2 `manual/`).
2. Operador verifica state snapshot: `SELECT COUNT(*) FROM myfinance.categories WHERE user_id IS NULL` debe ser 19; `SELECT COUNT(*) FROM myfinance.transactions` debe ser 362 (o el valor vigente).
3. Operador aplica V004 vía Supabase MCP `apply_migration name="v004_categories_display_name"`.
4. Operador verifica post-V004: `SELECT COUNT(*) FROM myfinance.categories WHERE display_name IS NULL` debe ser 0.
5. Operador aplica V005 vía Supabase MCP `apply_migration name="v005_merchants"`.
6. Operador verifica post-V005: `SELECT COUNT(*) FROM myfinance.merchants` debe ser 0 (tabla nueva); `SELECT COUNT(*) FROM myfinance.transactions WHERE merchant_id IS NOT NULL` debe ser 0.

Antes de cualquiera de estos pasos, ambas migraciones SHALL haber pasado tests Testcontainers locales contra Postgres 17 con el dump del `myfinance` schema actual.

Alternativas descartadas:
- **Hijackear V004 para merchants** (versión inicial del design, refutada por adv-review B1+B2).
- **Una sola migración combinada `V004` con ambos cambios.** Acopla dos features ortogonales; si V005 (merchants) falla en remoto, V004 (display_name) ya está aplicada y revertir requiere dos pasos distintos. Mejor separación clara.
- **Re-introducir trigger Postgres para feedback loop.** Rechazado dos veces en revisiones del MVP frontend. El feedback loop vive en Java.
- **`merchant_id` NOT NULL en V005.** Las 362 filas históricas no tienen merchant resuelto; backfill es un change separado.

Razón: respeta el plan documentado en `data-model.md`, mantiene additive-only, separa features ortogonales, deja `categories.display_name` disponible para el `CategoryDTO.name` mapping de D6.

### D9 — CORS allowlist explícita: Vercel + localhost dev, rechazo con 403

> **Origen de cambios vs versión inicial:** adversarial-review 2026-06-02 (Major M4). El status code de un Origin rechazado por CORS no estaba especificado — Spring Security por default devuelve `403 Forbidden` con body vacío para preflight rejection; el spec debe declararlo explícitamente para que el test lo aserte y el frontend pueda diferenciarlo de 401/404.

Decisión: `CorsConfig.java` permite Origins:
- `https://*.vercel.app` (preview deploys del proyecto frontend, pattern-matched vía `addAllowedOriginPattern`).
- El dominio productivo Vercel del frontend (string exacto vía `addAllowedOrigin`, configurable por env var `APP_CORS_ALLOWED_ORIGINS` como CSV).
- `http://localhost:5173` (Vite dev server local — solo en perfil `local` y `test`).

Métodos permitidos: `GET, PATCH, OPTIONS`. Headers permitidos: `Authorization, Content-Type`. Credenciales: NO (el frontend manda `Authorization` header, no cookies — alineado con D1).

**Comportamiento del rechazo:**
- Origin no permitido en preflight `OPTIONS` → **`403 Forbidden`** con body vacío. No se exponen headers `Access-Control-Allow-*`. El navegador bloquea la request real.
- Origin no permitido en request "simple" (GET sin headers preflight-triggering): el response sale sin headers CORS y el navegador lo bloquea client-side — el backend no devuelve 403 en ese caso porque no detecta el problema. Aceptable: los 4 endpoints requieren `Authorization: Bearer ...` que dispara preflight, así que en práctica todo cae al primer path.

Alternativas descartadas:
- **`*` para origin.** Bloqueado por Spring Security si credentials=true, riesgo de exposure aunque credentials=false. Allowlist explícita es trivialmente mantenible para un MVP.
- **Custom `AccessDeniedHandler` que devuelve `ProblemDetail` en rechazos CORS.** Cross-cutting innecesario — el navegador no muestra el body de un preflight rejection al usuario; 403 con body vacío es la convención y matchea lo que el frontend SDK ya espera.

Razón: superficie de ataque mínima, status code explícito y testeable, sin headers CORS leaked en rechazos. Cuando llegue el deploy productivo del backend, se agrega el dominio real al allowlist via env var sin recompilar.

### D10 — Visibility guard del `categoryId` en PATCH (anti-IDOR)

> **Origen:** adversarial-review 2026-06-02 (Blocker B6). El PATCH inicial cargaba la transacción del usuario y ejecutaba el UPDATE sin verificar que `body.categoryId` fuera realmente visible para el usuario. Un atacante con un JWT válido podría inferir si una categoría `user_id`-scoped existe en otro usuario probando UUIDs (200 vs 500 por FK violation). Operator decision 2026-06-02: **404 Not Found** (no 403 ni 400), para no filtrar la existencia ajena.

Decisión: antes del `UPDATE myfinance.transactions ... SET category_id = ?`, el servicio ejecuta:

```sql
SELECT id FROM myfinance.categories
WHERE id = :categoryId
  AND (user_id IS NULL OR user_id = :userId)
LIMIT 1;
```

Esta query devuelve la categoría si:
- es del sistema (`user_id IS NULL` — visible para todos los usuarios autenticados), o
- pertenece al usuario que hace el request.

Si no devuelve fila → **`404 Not Found`** con `ProblemDetail` tipo `not-found` y `detail` genérico (`"Resource not found"`). **No** se diferencia entre "la categoría no existe" y "la categoría existe pero pertenece a otro usuario" — eso filtraría la información que el guard quiere proteger.

Si devuelve fila → continuar al UPDATE de D5.

**Atomicidad:** el SELECT del guard y el UPDATE del PATCH viven en el mismo `@Transactional` (isolation READ_COMMITTED de Postgres). Race condition teórica: otro usuario podría borrar la categoría entre el guard y el UPDATE — el UPDATE fallaría por FK constraint y se mapearía a 500. Aceptable: no hay endpoint para borrar categorías en este MVP (CRUD de categorías es Épica 4).

**Test obligatorio (resuelve B6 del adv-review):**
- Seed: dos usuarios A y B; B tiene categoría `cat-B-private` (`user_id = B`); A no tiene esa categoría.
- A hace `PATCH /api/v1/transactions/{tx-A-id}/category` con `body.categoryId = cat-B-private` y `Authorization: Bearer <A-JWT>`.
- Aserción: **404 Not Found**, body conforme RFC 7807 con `type=/errors/not-found` sin echo del `cat-B-private`.
- Aserción: `tx-A` permanece sin modificar (`updated_at` no cambia).
- Aserción: `cat-B-private` permanece sin modificar.

Alternativas descartadas:
- **403 Forbidden.** Filtra "la categoría existe pero no es tuya". Mismo problema que devolver 401 cuando el username no existe — leak por timing.
- **400 Bad Request.** Implica error sintáctico; el body es válido. El concepto de "no encontrado en tu visibilidad" es semánticamente 404.
- **Sin guard, confiar en FK violation.** El backend devolvería 500 (FK constraint) lo cual ya es un leak: 500 ≠ 200, el atacante distingue. Además expone detalles de schema en logs.

Razón: anti-IDOR sin leak por status code, alineado con la convención de `ProblemDetail` del proyecto, testeable.

### D11 — Actuator hardening y zero-echo en `ProblemDetail`

> **Origen:** adversarial-review 2026-06-02 (Majors M7, M8). El scaffolding de `backend-runtime` deja expuestos `/actuator/info`, `/actuator/env`, `/actuator/configprops`, `/actuator/heapdump`, etc. en el classpath de Spring Boot Actuator. Algunos filtran env vars (incluido el `SUPABASE_JWT_JWKS_URI`, el `CORS_ALLOWED_ORIGINS`, y peor: si por accidente un dev pone `SUPABASE_SERVICE_ROLE_KEY` sin marcarlo sensible, lo expondría). M7: `ProblemDetail` no debe ecoar el token, ni el `categoryId` rechazado, ni la descripción de la transacción.

Decisión: configuración explícita en `application.yml`:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health   # SOLO health. Nada más expuesto por web por default.
  endpoint:
    health:
      show-details: never           # Sin detalles de DB connection en respuesta unauth.
      show-components: never
    info:
      enabled: false                # /actuator/info DESHABILITADO.
    env:
      enabled: false
    configprops:
      enabled: false
    heapdump:
      enabled: false
    threaddump:
      enabled: false
```

`/actuator/health` queda accesible para liveness probes del deploy target. Cualquier otro endpoint del actuator devuelve **404** (no 403 — no exponer la existencia del path).

**Zero-echo en `ProblemDetail` (`@ControllerAdvice`):**
- `detail` siempre es genérico (`"Resource not found"`, `"Bad request"`, `"Unauthorized"`, `"Forbidden"`, `"Internal server error"`).
- **Nunca** se ecoa: el JWT, claims del JWT, el `categoryId` rechazado, la `description` de la transacción, el `external_id`, el `raw_payload`.
- Para errores de validación de body (`@Valid` falla), `properties.errors` contiene mapa `field → "must not be null"` etc. — sin echo del valor recibido.
- `instance` se setea al path del request (`/api/v1/transactions/{id}/category`) — eso no es leak (ya está en logs del request).
- En `ERROR` se loguea el stacktrace completo con `user_id` y `request_id` vía MDC; en el response solo va el `ProblemDetail` genérico.

Test obligatorio (resuelve M7):
- Trigger un 404 por `categoryId` inexistente; aserción: response body NO contiene el UUID que se mandó.
- Trigger un 401 por JWT inválido; aserción: response body NO contiene ninguna substring del JWT.

Razón: principle of least leak — el actuator amplifica todo error de config en data exposure; el `ProblemDetail` es un canal de información que el atacante puede sondear sin auth en algunos paths.

### D12 — `MerchantNormalizer.normalize` congelada en este change

> **Origen:** adversarial-review 2026-06-02 (Minor m4) + OQ4 cerrada por operator decision 2026-06-02. La función `normalize(description)` define el `raw_pattern` del merchant; cambiarla post-hoc rompe la UNIQUE `(user_id, raw_pattern)` y produce merchants duplicados para el mismo comercio real.

Decisión: la implementación canónica de `MerchantNormalizer.normalize(String input)` en este change es:

```java
public final class MerchantNormalizer {
    private static final Pattern TRAILING_DIGITS = Pattern.compile("\\s*\\*?\\d{2,}\\s*$");
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    public static String normalize(String input) {
        if (input == null) return "";
        var s = input.trim().toLowerCase(java.util.Locale.ROOT);
        s = TRAILING_DIGITS.matcher(s).replaceAll("");
        s = WHITESPACE_RUN.matcher(s).replaceAll(" ");
        return s.trim();
    }
}
```

Reglas aplicadas en orden:
1. `null` → `""`.
2. `trim()` + `toLowerCase(ROOT)`.
3. Strip de "trailing digits" típicos de POS (`"NETFLIX.COM *1234"` → `"netflix.com"`, `"RAPPI 42"` → `"rappi"` — strip `\*?\d{2,}` al final, 2+ dígitos para evitar comer "DIDI 7" como "didi"). Operator decision 2026-06-02: 2+ dígitos.
4. Collapse de whitespace múltiples a un único space.
5. `trim()` final.

NO se hace strip de:
- Acentos (`"JUAN VALDÉZ"` permanece distinto de `"JUAN VALDEZ"` — el segundo es el dato real en n8n; el primero no debería aparecer pero si lo hace, el merchant management UI lo resuelve).
- Emojis.
- Caracteres especiales (`"APPLE.COM"` permanece tal cual).

**Compromiso de congelación:** cualquier cambio a esta función post-merge requiere su propio change con plan de re-mapeo (analizar merchants existentes, detectar colisiones potenciales, decidir merge strategy). Documentado en CHANGELOG del módulo.

Tests obligatorios:
- `normalize("NETFLIX.COM *1234")` → `"netflix.com"`.
- `normalize("RAPPI 42")` → `"rappi"`.
- `normalize("DIDI 7")` → `"didi 7"` (1 dígito ≥ 2 falso, no strip).
- `normalize("  JUAN  VALDEZ  *9876 ")` → `"juan valdez"`.
- `normalize(null)` → `""`.
- `normalize("")` → `""`.
- `normalize("APPLE.COM")` → `"apple.com"`.

Razón: regla determinista, congelada, auditable; el riesgo de duplicados merchant queda mitigado por el UNIQUE y por el merchant management UI futuro.

## Risks / Trade-offs

- **[Riesgo aceptado] JWT en `localStorage` del navegador (no httpOnly cookie).** Mitigación: TTL 1h, documentado en threat model del MVP frontend. Acción futura: emitir cookie httpOnly del backend cuando haya deploy productivo.
- **[Riesgo] Feedback loop crea merchants spurios si el usuario cambia categoría de transacciones tipo "DEBITO ATM" donde no hay merchant real.** Mitigación: `MerchantNormalizer.normalize(description)` (D12) agrupa heurísticamente; el merchant management UI (Épica 4) permitirá merge/delete. No bloquea el MVP.
- **[Riesgo mitigado por D1] Asunción de algoritmo de firma JWT.** La versión inicial asumía HS256 + secret simétrico; adv-review B7 lo refutó con probe directo (ES256 asimétrico vía JWKS). El stack final usa el endpoint público de Supabase como source of truth. Rotación de claves: automática vía cache de 5min de `NimbusJwtDecoder`.
- **[Riesgo] Backend usa `service_role` con RLS-bypass → bug de `WHERE user_id = ?` faltante = leak entre usuarios.** Mitigación: tests Testcontainers obligatorios por endpoint y por repository, con dos `userId`s seedeados, asertando que cross-user reads/writes fallan. Static analysis adicional (`@SecuredByUserId` annotation chequeada por un test que itera reflectivamente todos los repositorios) queda como follow-up si pasamos a multi-user real.
- **[Riesgo mitigado por D10] IDOR por `categoryId` inyectado en PATCH.** Adv-review B6 detectó que sin guard, un atacante distinguiría categorías ajenas existentes (UPDATE pasa → 200) vs inexistentes (FK violation → 500). D10 cierra el path devolviendo 404 sin diferenciar.
- **[Riesgo mitigado por D11] Leak por actuator + ProblemDetail eco.** Adv-review M7/M8. El actuator deja solo `/health` expuesto; `ProblemDetail` nunca ecoa el token, claims, o valor del request.
- **[Riesgo] Schema del MVP frontend diverge del shape Java cuando se haga el swap-out.** Mitigación: este change escribe `docs/api-spec.yml` real con el mapeo `nickname → name` y el drop de `parentId` documentados (D6); el siguiente change `frontend-swap-to-backend` lo usa como source of truth.
- **[Riesgo] Drift del merchant produce churn de `confidence` si el usuario corrige categorías erráticamente.** Aceptado: reset a 0.50 (D5) deja el merchant en estado "primer aprendizaje"; el match_count=1 indica explícitamente que no es un patrón confiable todavía. El logging INFO permite detectar usuarios con drift frecuente en QA.
- **[Riesgo] Concurrencia n8n ingestion + PATCH del usuario sobre el mismo merchant.** Mitigación: la rama B usa `INSERT ... ON CONFLICT (user_id, raw_pattern) DO UPDATE` que es atómico en Postgres; la rama A protege con `WHERE user_id = ?` y el isolation READ_COMMITTED hace consistente la lectura del `merchant.category_id` actual.
- **[Trade-off] Sin Flyway runtime aún — V004 y V005 se aplican via Supabase MCP a mano.** Aceptado: TASK-DB-06 (Flyway) es change separado. El procedimiento manual queda en `progress.md` y `tasks.md §9` con checklist.
- **[Trade-off] `MerchantNormalizer.normalize` congelada (D12).** Cualquier cambio a la heurística requiere change con plan de re-mapeo de merchants existentes. Aceptable: la versión 1 cubre los patterns POS conocidos del dataset actual.

## Migration Plan

Un solo branch `feat/backend-mvp-readonly` (worktree dedicado). Una vez los tests pasen verdes y la adversarial review v2 esté OK, ejecuta el agente las migraciones V004 + V005 contra Supabase remoto vía Supabase MCP `apply_migration` (autorización delegada por operator 2026-06-02, condicional a Docker local test verde + state snapshot pre/post), antes de mergear el código al `main`. Razón: si las migraciones fallan, el backend en `main` quedaría apuntando a un schema inexistente.

Orden de implementación (matches `tasks.md`):
1. **V004 + V005 SQL** escritas y testeadas local (Docker Compose Postgres 17 con seed de las 19 categorías del sistema y 362 transacciones sintéticas).
2. **SecurityConfig + OAuth2 Resource Server** (`NimbusJwtDecoder.withJwkSetUri`) + `TestJwtFactory` (EC P-256 efímero) + WireMock JWKS server + tests `should{401,200,...}When{expired,wrongAud,wrongIss,signedWithWrongKey,malformed,jwksRotated,valid}`.
3. **DTOs + Jackson config** + `BigDecimal → string` serializer + mappers (`nickname → name`, drop `parentId`) con tests unitarios puros.
4. **Repositorios jOOQ** (`AccountRepository`, `CategoryRepository`, `TransactionRepository`, `MerchantRepository`) + tests Testcontainers por aislamiento entre `userId` (cada repo: read ajeno = 0 rows, write ajeno = 0 affected).
5. **Servicios** (`TransactionService`, `MerchantUpserter`) + tests transaccionales: idempotency, drift reset, primer aprendizaje, visibility guard, race UPSERT.
6. **Controllers + ProblemDetailAdvice + CorsConfig** + tests REST-assured por endpoint: 401, 403 (CORS reject), 404 (tx ajena), 404 (categoría no visible — anti-IDOR), 400 (body inválido), paginación, idempotencia.
7. **`docs/api-spec.yml`** poblado con los schemas, responses `ProblemDetail`, y los 4 paths.
8. **Actuator hardening + config + env vars** (`SUPABASE_JWT_JWKS_URI`, `APP_CORS_ALLOWED_ORIGINS`, `SUPABASE_DB_URL`, `SUPABASE_SERVICE_ROLE_KEY`). Test que aserta `/actuator/env` y `/actuator/configprops` devuelven 404.
9. **Adversarial review v2** sobre proposal + design + spec + tasks reescritos.
10. **Operator gate (TASK-9):** backup pre-V004 vía `supabase-backup-policy-replant` + state snapshot.
11. **Agente aplica V004** vía Supabase MCP, valida `display_name IS NULL` = 0, aplica V005, valida `merchants COUNT = 0` + `transactions.merchant_id IS NOT NULL COUNT = 0`.
12. **PR a `main`**, merge.
13. **Change separado `frontend-swap-to-backend`** se abre después: swap del body de `src/services/*.ts` en el frontend, drop de `Category.parentId`, redeploy Vercel.

Rollback:
- Si el deploy del backend (cuando aterrice) rompe: revertir merge; el frontend sigue funcionando con 403 contra el path de Supabase directo (el path está bloqueado por la regla "no exposure de myfinance.*" pero el frontend sigue corriendo sin crashear). Sin downtime nuevo introducido por este change.
- **V004 NO se hace rollback** — `categories.display_name` es additive-only; las queries del backend que ya lo usan fallarían si se quita.
- **V005 NO se hace rollback** — las 362 filas en `transactions.merchant_id = NULL` no rompen nada; eliminar la FK + tabla `merchants` perdería el aprendizaje acumulado. Migración additive-only.
- **Si V004 o V005 fallan en remoto durante TASK-11:** Supabase MCP es transaccional por migración; cada `apply_migration` aborta atómicamente. El operator restaura del backup manual de TASK-10 si el state quedara inconsistente.

## Open Questions

> Todas resueltas por operator decision 2026-06-02 antes de la reescritura de este design. Las dejo registradas con la decisión final para preservar el audit trail.

- **OQ1 — Deploy target del backend.** ✅ **CERRADA — default Fly.io (región `bog`/`mia`) hasta TASK-BE-08.** Este change implementa código portable a cualquier target con env vars. Operator queda libre de cambiar antes del deploy real.
- **OQ2 — JWT secret en local dev vs prod.** ✅ **CERRADA — superada por D1 ES256/JWKS.** Ya no hay secret simétrico; el dev local apunta al mismo JWKS público de Supabase. Tests con `TestJwtFactory` (par EC efímero) + WireMock JWKS. Env var renombrada de `SUPABASE_JWT_SECRET` a `SUPABASE_JWT_JWKS_URI`.
- **OQ3 — ¿Versionar `docs/api-spec.yml` con un job que diff-ee contra los handlers?** ✅ **CERRADA — NO en este change.** Operator default aceptado. En este change el archivo se escribe a mano y el adversarial review v2 verifica match. CI gate (TASK-BE-07) queda en backlog.
- **OQ4 — Reglas de `MerchantNormalizer.normalize`.** ✅ **CERRADA — congelada en D12** con la implementación exacta: lowercase ROOT + trim + strip `\*?\d{2,}$` (umbral 2+ dígitos) + collapse whitespace. Sin strip de acentos ni emojis. Operator default aceptado con clarificación del umbral mínimo de dígitos.
- **OQ5 — ¿`merchant_id` se backfilla con un job para las 362 filas históricas?** ✅ **CERRADA — NO en este change.** El usuario las "siembra" naturalmente cuando re-categorize. Backfill puede hacerse después como TASK-DB-07 si el operador lo prioriza. Operator default aceptado.
