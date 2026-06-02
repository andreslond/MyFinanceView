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

### D1 — Auth JWT Supabase verificada en filter Spring Security, sin sesión propia

Decisión: el backend valida el JWT emitido por Supabase Auth en cada request. No emite cookies, no mantiene sesión, no rota tokens. El `Authorization: Bearer <jwt>` lo trae el frontend desde su `useAuth()` actual.

Validación:
- Algoritmo `HS256` con `JWT_SECRET` env var = el mismo que Supabase usa para firmar.
- Claim `exp` no expirado.
- Claim `iss == "https://akkoqdjmmozyqdfjkabg.supabase.co/auth/v1"`.
- Claim `aud == "authenticated"`.
- Claim `sub` parseado como `UUID` → expuesto vía `@AuthenticationPrincipal UUID userId`.
- Si cualquier validación falla: `401 Unauthorized` con `ProblemDetail` tipo `unauthorized`.

Alternativas descartadas:
- **OAuth2 Resource Server con JWK URL.** Supabase usa HS256 con secret simétrico, no JWK pública. No aplica.
- **Spring Session.** Introduce store propio para nada — el JWT es self-contained y el TTL lo controla Supabase.
- **Emitir cookie httpOnly del backend.** Doble session store (Supabase localStorage + cookie backend) que hay que mantener en sync. Vale la pena cuando haya deploy productivo permanente del backend; en MVP es overengineering.

Razón: máxima compatibilidad con el MVP frontend ya desplegado. El swap-out del frontend cambia solo el body de los services; auth no se toca.

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

### D5 — PATCH idempotente + feedback loop transaccional

Decisión: `PATCH /api/v1/transactions/{id}/category` body `{categoryId}`:

1. Cargar transacción por `id` filtrando por `userId` del JWT. Si no existe → 404.
2. Si `body.categoryId === tx.categoryId` → return 200 con el DTO actual, sin tocar DB.
3. Si difiere, abrir transacción jOOQ:
   - `UPDATE myfinance.transactions SET category_id = ?, updated_at = NOW() WHERE id = ? AND user_id = ?`
   - Si `tx.merchantId IS NOT NULL`: `UPDATE myfinance.merchants SET confidence = LEAST(0.95, confidence + 0.05), match_count = match_count + 1, last_confirmed_at = NOW() WHERE id = tx.merchant_id AND user_id = ?`. Si `display_name` quedara obsoleto, no se toca (el frontend no envía nombre — eso queda para el merchant UI de Épica 4).
   - Si `tx.merchantId IS NULL`: UPSERT en `merchants` con `raw_pattern = normalize(tx.description)`, `display_name = tx.description`, `category_id = body.categoryId`, `confidence = 0.50`, `match_count = 1`, `last_confirmed_at = NOW()`. Asignar el `merchant_id` resultante a `transactions.merchant_id` en el mismo trace.
4. Commit. Return 200 con el DTO actualizado.

Constraints:
- Cap de `confidence` a `0.95` (alineado con `SPEC.md §7`). Si ya está en 0.95, queda en 0.95 (LEAST).
- `match_count` empieza en 1 para merchants nuevos, +1 para existentes.
- `raw_pattern` se genera por `normalize(description)` (lowercase, strip whitespace, strip dígitos al final tipo `*1234`) — la función queda en `MerchantUpserter.normalize(String)`. Documentada como decisión interna; ajustable sin breaking change.

Alternativas descartadas:
- **Trigger Postgres.** Rechazado en el adversarial review del MVP frontend (B4, B5). El feedback loop vive en código Java, no en SQL.
- **PATCH no idempotente — siempre touch.** Inflaría `match_count` con cada doble click del modal del frontend. Rompería la métrica.
- **No tocar `merchants` si `merchantId` es NULL.** Pierde el primer aprendizaje del usuario. Mejor crear el merchant con `confidence` modesto.

Razón: idempotencia barata vía read-before-write, feedback loop transaccional sin window de split-brain, semántica alineada con SPEC.md §7. Mantenibilidad alta porque toda la lógica vive en `MerchantUpserter` testeable con Testcontainers.

### D6 — DTOs alineados con el shape inventado por el MVP frontend

Decisión: los records Java DTO usan los nombres camelCase que `frontend/src/services/types.ts` ya inventó (`occurredAt`, `accountId`, `categoryId`, `merchantId`, `createdAt`, `updatedAt`). Jackson serializa con `@JsonProperty` cuando el nombre del record diverge del Java naming. Tipos:
- IDs: `UUID` → Jackson serializa como string.
- Tiempo: `OffsetDateTime` UTC → ISO 8601 string vía `jackson-datatype-jsr310` (registrado en `ObjectMapper` config).
- Money: `BigDecimal` → serializado como string vía `JsonSerializer<BigDecimal>` custom que llama `toPlainString()`. Garantiza que `"0.10"` no se vuelva `0.1`.
- Currency: `String` ISO 4217 (e.g. `"COP"`).

`PageDTO<T>` es genérico: `{ rows: List<T>, page: int, pageSize: int, hasMore: boolean }`.

Alternativas descartadas:
- **Records `Transaction` directos sin DTO.** Acopla la entidad de dominio al wire. Cuando se agregue billing/categorización rica, el DTO necesita campos que la entidad no tiene.
- **`BigDecimal` como número JSON.** Riesgo de pérdida de precisión en JS (`Number(value)`). El frontend explícitamente espera string.

Razón: cero refactor en el frontend cuando se haga el swap-out. El mapper privado del frontend se borra.

### D7 — `docs/api-spec.yml` se llena con el contrato real en este change

Decisión: este change pobla `docs/api-spec.yml` con los 4 endpoints (`/api/v1/transactions{,/{id}/category}`, `/api/v1/accounts`, `/api/v1/categories`), los 5 schemas (`TransactionDTO`, `AccountDTO`, `CategoryDTO`, `PageDTO_TransactionDTO`, `UpdateCategoryRequest`), y los `ProblemDetail` responses para 400/401/403/404.

Mantener `docs/api-spec.yml` en sync con los handlers Spring requiere un CI gate adicional (e.g. springdoc generate vs committed file). Ese gate queda fuera de scope de este change (es TASK-BE-07 separado). En este change el archivo se escribe a mano alineado a los handlers.

Razón: el frontend (y cualquier futuro consumer) tiene contrato verificable desde el día 1 de este backend MVP. Si el handler diverge del YAML, el adversarial review lo cacha.

### D8 — V004 crea `merchants` con RLS análoga a V002

Decisión: V004 crea `myfinance.merchants` con columnas:
- `id UUID PK DEFAULT gen_random_uuid()`
- `user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE`
- `display_name TEXT NOT NULL`
- `raw_pattern TEXT NOT NULL` — normalizado (lowercase, sin trailing digits típicos como `*1234`).
- `category_id UUID NOT NULL REFERENCES myfinance.categories(id)`
- `confidence NUMERIC(3,2) NOT NULL DEFAULT 0.50 CHECK (confidence BETWEEN 0.00 AND 1.00)`
- `match_count INT NOT NULL DEFAULT 0 CHECK (match_count >= 0)`
- `last_confirmed_at TIMESTAMPTZ`
- `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`
- `updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`
- UNIQUE `(user_id, raw_pattern)`.

Y FK nueva `myfinance.transactions.merchant_id UUID REFERENCES myfinance.merchants(id) ON DELETE SET NULL` (nullable; las 489 filas históricas quedan en NULL hasta que el usuario las re-categorize).

RLS habilitada con policies `merchants_select_own`, `merchants_insert_own`, `merchants_update_own`, `merchants_delete_own` análogas a `accounts_*` en V002. Sin trigger.

Aplicación a remoto: Supabase MCP `apply_migration` con `name="v004_merchants"`, ejecutada por el operador después del adversarial review (no automático).

Alternativas descartadas:
- **Re-introducir trigger.** Rechazado dos veces en revisiones del MVP frontend. El feedback loop vive en Java.
- **`merchant_id` NOT NULL.** Las 489 filas históricas no tienen merchant resuelto; backfill es un change separado (TASK-DB-07 si se prioriza después).

Razón: schema mínimo para soportar el feedback loop. El frontend NO necesita endpoints de `/api/v1/merchants` en este MVP — el backend solo escribe `merchants`, el frontend solo lee category names. Cuando aterrice merchant management UI (Épica 4), se agrega el endpoint en su propio change.

### D9 — CORS allowlist explícita: Vercel + localhost dev

Decisión: `CorsConfig.java` permite Origins:
- `https://*.vercel.app` (preview deploys del proyecto frontend, pattern-matched).
- El dominio productivo Vercel del frontend (string exacto, configurable vía env var `CORS_ALLOWED_ORIGINS`).
- `http://localhost:5173` (Vite dev server local).

Métodos permitidos: `GET, PATCH, OPTIONS`. Headers permitidos: `Authorization, Content-Type`. Credenciales: NO (el frontend manda `Authorization` header, no cookies).

Alternativas descartadas:
- **`*` para origin.** Bloqueado por Spring Security si credentials=true, riesgo de exposure aunque credentials=false. Allowlist explícita es trivialmente mantenible para un MVP.

Razón: superficie de ataque mínima. Cuando llegue el deploy productivo del backend, se agrega el dominio real al allowlist via env var sin recompilar.

## Risks / Trade-offs

- **[Riesgo aceptado] JWT en `localStorage` del navegador (no httpOnly cookie).** Mitigación: TTL 1h, documentado en threat model del MVP frontend. Acción futura: emitir cookie httpOnly del backend cuando haya deploy productivo.
- **[Riesgo] Feedback loop crea merchants spurios si el usuario cambia categoría de transacciones tipo "DEBITO ATM" donde no hay merchant real.** Mitigación: `normalize(description)` agrupa heurísticamente; si genera ruido visible al usuario, el merchant management UI (Épica 4) le permitirá hacer merge/delete. No bloquea el MVP.
- **[Riesgo] `JWT_SECRET` filtrado → firma de tokens arbitrarios.** Mitigación: el secret vive en env var del deploy del backend; rotación coordinada con Supabase es manual y queda como runbook. El backend no escribe el secret en logs.
- **[Riesgo] Backend usa `service_role` con RLS-bypass → bug de `WHERE user_id = ?` faltante = leak entre usuarios.** Mitigación: tests Testcontainers obligatorios por endpoint, con dos `userId`s seedeados, asertando que cross-user reads/writes fallan. Static analysis adicional (`@SecuredByUserId` annotation chequeada por un test que itera reflectivamente todos los repositorios) queda como follow-up si pasamos a multi-user real.
- **[Riesgo] Schema del MVP frontend diverge del shape Java cuando se haga el swap-out.** Mitigación: este change escribe `docs/api-spec.yml` real; el siguiente change `frontend-swap-to-backend` lo usa como source of truth. Adversarial review compara.
- **[Trade-off] Sin Flyway runtime aún — V004 se aplica via Supabase MCP a mano.** Aceptado: TASK-DB-06 (Flyway) es change separado. El procedimiento manual queda en `progress.md` para no perderlo.

## Migration Plan

Un solo branch `feat/backend-mvp-readonly` (worktree dedicado). Una vez los tests pasen verdes y la adversarial review esté OK, ejecuta el operador la migración V004 contra Supabase remoto (Supabase MCP `apply_migration`) antes de mergear el código al `main`. Razón: si la migración falla, el backend en `main` quedaría apuntando a un schema inexistente.

Orden de implementación (matches `tasks.md`):
1. V004 SQL escrita y testeada local (Docker Compose Postgres 17).
2. SecurityConfig + SupabaseJwtFilter + tests (auth happy/sad path).
3. Repositorios jOOQ (`AccountRepository`, `CategoryRepository`, `TransactionRepository`, `MerchantRepository`) + tests Testcontainers por aislamiento entre `userId`.
4. DTOs + Jackson config + serializer custom `BigDecimal` → string.
5. Controllers + ProblemDetailAdvice + tests REST-assured por endpoint (incluyendo 401, 403, 404, 400, paginación, idempotencia, feedback loop transaccional).
6. CORS config + test que confirma Origin allowlist.
7. `docs/api-spec.yml` poblado.
8. Adversarial review.
9. Operador aplica V004 a remoto (no automático).
10. PR a `main`, merge.
11. **Change separado `frontend-swap-to-backend`** se abre después: swap del body de `src/services/*.ts` en el frontend, redeploy Vercel.

Rollback:
- Si el deploy del backend (cuando aterrice) rompe: revertir merge, el frontend sigue funcionando contra Supabase directo. Sin downtime.
- V004 NO se hace rollback (las 489 filas en `transactions.merchant_id = NULL` no rompen nada; eliminar la FK + tabla `merchants` perdería el aprendizaje acumulado). Migración additive-only.

## Open Questions

- **OQ1 — Deploy target del backend.** Opciones: Railway (familiar al ecosystem npm/python que el operador conoce), Fly.io (Docker, free tier limitado), VPS propio (control total, requiere setup). El operador decide antes de TASK-BE-08; este change implementa código que funciona en cualquier target con env vars. **Default si no se decide:** Fly.io con plan free + región `bog` o `mia` para latencia desde Bogotá; reevaluable.
- **OQ2 — JWT secret en local dev vs prod.** Decisión por defecto: env var `JWT_SECRET` con valor dummy en `application-local.yml` para tests no-auth y valor real (vía `.env.local`) para integración local con Supabase real. Tests Testcontainers que necesitan firmar JWTs generan su propio secret en runtime.
- **OQ3 — ¿Versionar `docs/api-spec.yml` con un job que diff-ee contra los handlers?** Decisión por defecto: NO en este change (TASK-BE-07 separado). En este change el archivo se escribe a mano y el adversarial review verifica match.
- **OQ4 — ¿`normalize(description)` también strip emojis, acentos, dobles espacios?** Decisión por defecto: lowercase + trim + strip de `*\d{4}` al final + collapse de whitespace. Si el operador ve merchants duplicados por casing/acentos en QA, se ajusta sin breaking change.
- **OQ5 — ¿`merchant_id` se backfilla con un job para las 489 filas históricas?** Decisión por defecto: NO en este change. El usuario las "siembra" naturalmente cuando re-categorize en la UI. Backfill puede hacerse después como TASK-DB-07 si se prioriza.
