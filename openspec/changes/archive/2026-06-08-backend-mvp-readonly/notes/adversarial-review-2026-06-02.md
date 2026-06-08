# Adversarial Review — `backend-mvp-readonly` — 2026-06-02

Scope: artefacts only (proposal.md + design.md + specs/backend-rest-api/spec.md + tasks.md + notes/closing-mvp-frontend-readonly.md). No code in this change yet.

Baseline read: SPEC.md, docs/base-standards.md, docs/backend-standards.md, docs/data-model.md, openspec/specs/backend-runtime/spec.md, backend/database/migrations/V001..V003, frontend/src/services/types.ts, .env.example, docs/api-spec.yml, archived `mvp-frontend-readonly` artefacts implied by closing note.

---

## Blockers

### B1 — V004 cannot satisfy spec for `GET /api/v1/categories`: `display_name` column does not exist and is not added by any migration in this change
**Evidence:**
- `backend/database/migrations/V001__initial_schema.sql:73-87` defines `myfinance.categories` with columns `id, user_id, name, type, icon, color, is_active, created_at, updated_at`. **No `display_name`.**
- `docs/data-model.md:48` confirms: "Pending TASK-DB-01: `display_name` column in Spanish (NOT NULL post-backfill)."
- `docs/data-model.md:124-129` reserves `V004 — TASK-DB-01: categories.display_name (ES)` — i.e. `display_name` is the V004 migration in the canonical data-model.
- `openspec/changes/backend-mvp-readonly/specs/backend-rest-api/spec.md:130-134` (Requirement "GET /api/v1/categories") asserts ordering `displayName ASC NULLS LAST, name ASC` and the scenario asserts "21 `CategoryDTO` en orden alfabético por `displayName`".
- `openspec/changes/backend-mvp-readonly/tasks.md:23` adds `displayName` to `CategoryDTO` record; `tasks.md:33` orders by `display_name NULLS LAST, name ASC`.
- **No task in tasks.md §1.x adds `display_name` to `myfinance.categories`.** §1 only creates the `merchants` table and `transactions.merchant_id`.
- Result: the moment the implementer runs jOOQ codegen against the schema with V004 applied, `Categories.DISPLAY_NAME` will not exist and the repo will fail to compile, OR if they hand-write the query, the integration test will fail with `column "display_name" does not exist`.

**Recommended fix (pick one, document in design.md):**
- (a) Expand V004 to add the `display_name` column AND seed the 19 system categories with Spanish labels — same migration; rename file `V004__merchants_and_categories_display_name.sql` or split as `V004__categories_display_name.sql` + `V005__merchants.sql` (then update §3 in data-model.md accordingly).
- (b) Drop `displayName` from CategoryDTO for this MVP and fall back to ordering by `name`; defer Spanish UX to a follow-up change. The frontend `types.ts:39` already treats `displayName` as nullable so this is forward-compatible.

### B2 — V004 collides with the V-number reservation in `data-model.md §3`
**Evidence:**
- `docs/data-model.md:124-129` says **V004 = TASK-DB-01: categories.display_name**.
- `docs/data-model.md:131-138` says **V005 = TASK-DB-03: accounts cut/payment day**.
- `docs/data-model.md:141-150` says **V006 = TASK-DB-02: installments**.
- `docs/data-model.md:153-169` says **V007 = TASK-DB-04: merchants table**.
- `openspec/changes/backend-mvp-readonly/design.md:141-167` (D8) and `tasks.md:1-7` (§1.1) hijack V004 for `merchants`, which is reserved for V007.
- The proposal acknowledges no superseding amendment to `data-model.md §3` reservations beyond the impact note (`proposal.md:80-81`) which talks about a *forward-going* edit to data-model.md after the change lands but does not call out that V004 has been re-assigned.
- This is silent V-number rewriting. Future agents reading `data-model.md` (the canonical doc per `SPEC.md §🎯` hierarchy item 2) will produce conflicting migrations.

**Recommended fix:**
- Either keep the canonical V-numbering (this change becomes V007 OR V004 if you also fold display_name in — see B1 fix (a)) and update `data-model.md §3` in the same PR.
- OR explicitly amend `data-model.md §3` in the design.md, stating "the previously planned V004..V007 sequence is collapsed/renumbered; the new sequence is …". Today the design.md does not acknowledge the prior plan exists.

### B3 — AccountDTO uses `name` but `myfinance.accounts` has no `name` column
**Evidence:**
- `backend/database/migrations/V001__initial_schema.sql:90-101` — `myfinance.accounts` columns: `id, user_id, bank_id, type, currency, last4, nickname, active, created_at, updated_at`. The display label is `nickname`, not `name`.
- `openspec/changes/backend-mvp-readonly/specs/backend-rest-api/spec.md:118` "ordenadas por `name ASC`" — column does not exist.
- `openspec/changes/backend-mvp-readonly/specs/backend-rest-api/spec.md:122` scenario "array de 3 `AccountDTO` ordenado por `name`".
- `openspec/changes/backend-mvp-readonly/tasks.md:22` AccountDTO record fields `id, userId, name, bankId, accountType, currency, createdAt, updatedAt`.
- `frontend/src/services/types.ts:25-34` AccountDTO also has `name` (shape was invented by the MVP frontend, called out as such).
- D6 says "DTOs alineados con el shape inventado por el MVP frontend" — but the shape is wrong relative to the schema, and the spec inherits the inversion without correcting it.

**Recommended fix:**
- Decide and document: `AccountDTO.name = accounts.nickname` mapped explicitly (preferred — least churn), or rename `accounts.nickname` → `accounts.name` in V004 (riskier; breaks any external consumer).
- Update the spec's Scenario "Cuentas del usuario" to spell out the column-to-field mapping.
- Update `tasks.md §4.2` and §3.2 to reference the source column unambiguously so the implementer can't drift.

### B4 — CategoryDTO has `parentId` but the schema has no `parent_id` column
**Evidence:**
- `backend/database/migrations/V001__initial_schema.sql:74-84` — `categories` columns: `id, user_id, name, type, icon, color, is_active, created_at, updated_at`. **No `parent_id`.**
- `openspec/changes/backend-mvp-readonly/tasks.md:23` "Crear `api/dto/CategoryDTO.java` record (`id, userId, name, displayName, parentId, createdAt, updatedAt`)".
- `frontend/src/services/types.ts:42` `parentId: string | null;` — also invented.
- No task in §1.x adds `parent_id` to `categories`; no scenario in spec.md validates parent-child relationships.
- The implementer will either (a) hardcode `parentId = null` always (silent contract lie), or (b) try to query a non-existent column.

**Recommended fix:**
- Drop `parentId` from CategoryDTO in this MVP. The frontend already accepts `null`; no behaviour change for the consumer.
- OR add the column in a separate migration with a clear use case. The current spec has no requirement that mentions parent categories.

### B5 — Feedback loop semantics diverge from `SPEC.md §7` without amendment
**Evidence:**
- `SPEC.md §7 ("Loop de retroalimentación")` lines 444-450: `UPDATE merchants SET confidence += 0.1, match_count++, last_confirmed_at = now()`. Threshold for skipping LLM is `0.85` (`SPEC.md §4.1` line 242: "`confidence >= 0.85` → categorizar directo").
- `openspec/changes/backend-mvp-readonly/design.md:100-106` (D5): increment `+0.05`, cap `0.95` ("LEAST(0.95, confidence + 0.05)").
- `openspec/changes/backend-mvp-readonly/specs/backend-rest-api/spec.md:88-91` scenario "Confidence capped en 0.95" with starting value 0.93 → ending 0.95.
- This silently re-tunes a behavioural curve documented in the project north-star (SPEC.md §7). The implementation slows learning by 50% and changes the asymptote.
- The drift is not justified anywhere — no "alternativas descartadas" entry, no link back to SPEC.md.

**Recommended fix:**
- Either align with `SPEC.md §7` (increment 0.1, cap likely should be `<=1.0` or the SPEC.md-declared 0.85 if 0.85 is "good enough to skip LLM" then anything above is moot — clarify in design.md), OR amend SPEC.md §7 in the same change to justify the slower-learning curve.
- Note: SPEC.md is **§🎯 hierarchy rank 1** per CLAUDE.md and base-standards.md §1; the change MUST acknowledge the override.

### B6 — `categoryId` body parameter is not validated against user-visible categories → cross-user data write vector
**Evidence:**
- `openspec/changes/backend-mvp-readonly/specs/backend-rest-api/spec.md:79-114` (Requirement "PATCH .../category — idempotent with merchant feedback loop") — no scenario validates that `body.categoryId` belongs to the caller's visible set (system categories OR own custom categories).
- `openspec/changes/backend-mvp-readonly/design.md:97-103` (D5 step 3) — the UPDATE only filters `WHERE id = ? AND user_id = ?` on `transactions`; the `categoryId` is taken from the body and written without lookup.
- Threat model in `proposal.md:40-54` covers cross-user *read* and cross-user *mutation of someone else's row*, but **does not cover writing a foreign user's `category_id` onto your own row**. A malicious user can:
  1. Enumerate Supabase auth user IDs OR guess UUIDs.
  2. PATCH their own transaction with `categoryId = <userB's custom category UUID>`.
  3. The transaction succeeds because the FK on `transactions.category_id → categories.id` does not encode tenancy.
  4. Subsequent `GET /api/v1/transactions` will return a transaction with a `categoryId` not in the user's `GET /api/v1/categories` response. The frontend then either shows it blank, or—worse—calls a `getCategory(id)` endpoint and leaks userB's category metadata.
- The current spec on categories at `spec.md:128-138` correctly hides other users' custom categories from reads, but PATCH writes are not gated.
- This is the classic IDOR/horizontal-privilege variant.

**Recommended fix:**
- Add scenario to PATCH requirement: "Body `categoryId` referencing a category not visible to the caller → 400 (or 404 to avoid enumeration)".
- D5 step 3 SHALL gain a guard query: `SELECT 1 FROM myfinance.categories WHERE id = ? AND (user_id IS NULL OR user_id = ?)` inside the same transaction, BEFORE the UPDATE. Fail with 400/404 otherwise.
- Add an integration test in `tasks.md §5.x` that exercises this case.

### B7 — Auth: the change hard-commits to HS256 + symmetric secret, but Supabase Auth has been migrating projects to asymmetric JWTs (ES256/RS256 with JWKS) since late 2024; the design rejects the JWKS path as inapplicable without verifying the project's current signer
**Evidence:**
- `openspec/changes/backend-mvp-readonly/design.md:45` "Algoritmo `HS256` con `JWT_SECRET` env var".
- `openspec/changes/backend-mvp-readonly/design.md:52-53` "OAuth2 Resource Server con JWK URL. Supabase usa HS256 con secret simétrico, no JWK pública. No aplica."
- `openspec/changes/backend-mvp-readonly/proposal.md:47` same hard claim.
- Supabase has been rolling out asymmetric JWTs and a JWKS endpoint at `https://<project>.supabase.co/auth/v1/jwks` for new projects; the dashboard exposes "JWT Signing Keys" with rotation. The project `akkoqdjmmozyqdfjkabg` was created when? If after the asymmetric default, the `SUPABASE_JWT_SECRET` env var still listed in `.env.example:12` is the legacy symmetric secret, but the live signer may differ.
- If the project has been migrated (operator-initiated or auto), the backend will reject every legitimate JWT in production with 401 because HS256 verification against the legacy secret fails on an ES256-signed token.
- Cost of being wrong: silent 100% auth outage on launch.

**Recommended fix (cheap, no rearchitecture):**
- Add a task under §2.x: "Verify Supabase Auth signing algorithm for project `akkoqdjmmozyqdfjkabg` via dashboard or `curl https://akkoqdjmmozyqdfjkabg.supabase.co/auth/v1/jwks` (returns 200+keys if asymmetric). Document the result in `progress.md`."
- Decision-tree in design.md D1: "If asymmetric → use Spring's `JwtDecoder` with `NimbusJwtDecoder.withJwkSetUri(...)`. If symmetric → current HS256 path." This is one short branch, not a "no aplica" dismissal.
- The OQ2 default ("env var `JWT_SECRET`") is still valid for local dev tests that sign their own tokens; this is an orthogonal concern.

---

## Majors

### M1 — Scenario "Atomicidad — rollback si el feedback loop falla" (spec.md:111-114) describes a state that the design path cannot actually trigger
**Evidence:**
- `openspec/changes/backend-mvp-readonly/specs/backend-rest-api/spec.md:111-114` Scenario: "un merchant cuya FK `category_id` ha sido borrada inesperadamente" + WHEN "el user dispara un PATCH que requiere actualizar ese merchant" → THEN "la transacción hace rollback".
- `openspec/changes/backend-mvp-readonly/design.md:100-101` (D5 step 3) — the merchant UPDATE only sets `confidence, match_count, last_confirmed_at`. It does NOT touch `category_id` on `merchants`. So a stale/deleted FK on `merchants.category_id` would not surface as an error on this UPDATE (no FK is being modified).
- The failure path the scenario tries to assert is therefore not reachable by the documented code path. The implementer will write a test that either (a) passes trivially (no rollback occurs because no error occurs) or (b) hacks around the design by manually triggering a different SQL error.
- The atomicity intent is correct; the artificial trigger is wrong.

**Recommended fix:**
- Replace the artificial-corruption scenario with a reachable one. Examples:
  - The `UPSERT` step for a new merchant: starve the connection pool / set a unique-violation race by parallel insert with same `(user_id, raw_pattern)`. Assert the first transaction commits and the conflict path is handled.
  - Wrap the merchant UPSERT/UPDATE inside a Spring `@Transactional` and verify that throwing in a Spring callback before commit rolls back the `transactions` UPDATE — testable with a `@SpyBean`-style hook on `MerchantUpserter`.
- Adjust `tasks.md §5.6` accordingly.

### M2 — `merchants.category_id` semantics: design says "if user changes the category, increment confidence of the same merchant" but does not update `merchants.category_id`
**Evidence:**
- `openspec/changes/backend-mvp-readonly/design.md:100-101` (D5 step 3) — when `merchantId != null`, the merchant UPDATE only touches `confidence, match_count, last_confirmed_at`. **`merchants.category_id` is not changed.**
- `tasks.md:42` (§4.12) calls this out as an open question marked "OQ: documentar en design" and defers to TASK-BE-09.
- Result: a merchant learned with `category_id = X` will keep `X` even after the user re-categorizes 10 transactions of that merchant to `Y`. The next ingestion of that merchant (n8n direct-lookup path) will categorize as `X` — the system never learns from the user's correction.
- This breaks the feedback loop's stated purpose ("loop de retroalimentación" — SPEC.md §7).

**Recommended fix:**
- Resolve the OQ in design.md before implementation: should `merchants.category_id` flip when the user changes the category of a transaction that already has `merchant_id` set? Default proposal: yes, set `merchants.category_id = body.categoryId` AND reset `confidence` to `0.5` (or some lower value) when category changes — but cap drift sensibly so a single double-click doesn't reset learning.
- Add a scenario to the PATCH requirement explicitly: "merchant category drift". Right now the spec is silent.

### M3 — Pagination scenario assumes a row count that does not match documented seed/data state
**Evidence:**
- `openspec/changes/backend-mvp-readonly/specs/backend-rest-api/spec.md:56-57` Scenario "un user con 489 transacciones llama `GET /api/v1/transactions`".
- `SPEC.md §4.1` line 210: "Estado actual: 362 transacciones".
- `openspec/changes/backend-mvp-readonly/design.md:156` "las 489 filas históricas quedan en NULL".
- Two different numbers (362 vs 489) coexist in the change. Cosmetic to the scenario itself (pagination math is independent), but signals that the design.md wasn't reconciled with the SPEC.md row-count.
- Risk: implementer seeds tests with the wrong count, or interprets the scenario's "489" as a fixture requirement.

**Recommended fix:**
- Replace `489` with `>= pageSize+1` in the scenario so the assertion is independent of fixture size.
- Reconcile design.md `489` with SPEC.md `362` (or add a note that 489 is post-some-ingestion delta).

### M4 — CORS scenario "Origin desconocido se rechaza" misstates Spring's actual behaviour
**Evidence:**
- `openspec/changes/backend-mvp-readonly/specs/backend-rest-api/spec.md:172-174` Scenario: "Origin `https://evil.com` → THEN la response NO incluye `Access-Control-Allow-Origin` y el browser bloquea la request".
- Spring's `CorsFilter` with a `CorsConfigurationSource` returns **HTTP 403 Forbidden** (with empty body) for preflight from a non-allowlisted Origin, AND omits `Access-Control-Allow-Origin`. The "browser bloquea" framing skips over the server-side status code.
- Tests written from this scenario may assert "200 without ACAO header" and pass against a buggy CORS config that silently 200s without ACAO (i.e., never reached the CorsFilter), which is a false-pass.

**Recommended fix:**
- Tighten scenario to assert: status code (`403` by default in Spring), no `Access-Control-Allow-Origin` header, no `Access-Control-Allow-Methods` header.
- `tasks.md §6.11` already lists `CorsConfigTest`; add the explicit status code to the assertion plan.

### M5 — No scenario for malformed `Authorization` header
**Evidence:**
- `openspec/changes/backend-mvp-readonly/specs/backend-rest-api/spec.md:11-13` covers "without Authorization header" → 401.
- `openspec/changes/backend-mvp-readonly/specs/backend-rest-api/spec.md:15-17` covers "JWT signed with wrong secret" → 401.
- No scenario covers: `Authorization: Bearer ` (empty after Bearer), `Authorization: Token <jwt>` (wrong scheme), `Authorization: Bearer abc.def` (only two segments, not three), `Authorization: Bearer <base64-garbage>`.
- `tasks.md §2.6` mentions "header malformado → 401" as a unit test — but the spec requirement does not list this scenario, so the unit test is unjustified by the spec.

**Recommended fix:**
- Add a single scenario "Authorization header malformed" with at least 3 variants in the WHEN bullet (`empty bearer`, `wrong scheme`, `not three JWT segments`) — all THEN 401.

### M6 — No scenario for `categoryId: null` body (uncategorize semantics)
**Evidence:**
- `openspec/changes/backend-mvp-readonly/specs/backend-rest-api/spec.md:79-114` PATCH requirement scenarios assume `categoryId` is always a valid UUID.
- `openspec/changes/backend-mvp-readonly/tasks.md:25` "UpdateCategoryRequest record (`categoryId: UUID`)" — record field non-null implied for primitives, but the type `UUID` is a reference; Jackson + Bean Validation would accept `null` unless `@NotNull`-ed.
- The DB schema (`V001` line 112) allows `transactions.category_id` to be NULL: `REFERENCES categories(id) ON DELETE SET NULL`. So "uncategorize a transaction" is a semantically valid operation.
- Without a scenario, the implementer will arbitrarily decide: (a) reject null with 400, (b) accept null and skip the feedback loop, (c) accept null and *still* run the feedback loop (which would crash on null categoryId in the UPSERT). All three are plausible; only one is right.

**Recommended fix:**
- Add a scenario: either "Body with `categoryId: null` → 400" (no uncategorize in MVP) or "Body with `categoryId: null` → 200, transaction cleared, no merchant feedback".

### M7 — Threat model: `service_role` key in the backend's runtime memory is a single-point-of-compromise; mitigation is only mentioned as "env var"
**Evidence:**
- `openspec/changes/backend-mvp-readonly/proposal.md:48` defense: "Backend usa `service_role` para conectar a Supabase (RLS-bypassed)".
- `openspec/changes/backend-mvp-readonly/proposal.md:55-57` Fuera de alcance: "Compromiso del laptop del operador" but does not address "backend process memory dump" or "log of `application.yml` rendered with env-var-injected secrets at startup".
- `tasks.md §8.4` adds `SUPABASE_SERVICE_ROLE_KEY` to `.env.example` but no task asserts: (a) the secret is never logged at startup, (b) the secret is never echoed in `/actuator/env` (Spring exposes it under default `endpoints.web.exposure`), (c) the secret is never serialized into a `ProblemDetail` `detail` field.
- Spring Boot's actuator `/actuator/env` is disabled by default but a future change could expose it; the change should declare the invariant now.

**Recommended fix:**
- Add a scenario under "ProblemDetail error responses": "ProblemDetail body does not echo configuration values" with a check that `SUPABASE_SERVICE_ROLE_KEY` substring never appears in any response body or log line during the test suite.
- Add a task: "Confirm `management.endpoints.web.exposure.include` does NOT include `env`, `configprops`, `heapdump`".

### M8 — `tasks.md §4.12` defers a semantic question (merchant category change) to "TASK-BE-09" without that change existing
**Evidence:**
- `openspec/changes/backend-mvp-readonly/tasks.md:42` "si la lógica de "merchant cambia de categoría" requiere split, queda como TASK-BE-09".
- The TASK-BE-09 referenced is the post-MVP budgets endpoints in `SPEC.md §6`, which has nothing to do with merchant category drift.
- This is a phantom forward-reference. When the next agent reads it they will not find the task they were promised.

**Recommended fix:**
- Either spell out the drift behaviour in D5 and remove the deferral, or create an OQ6 in design.md and add the question to `progress.md.decisions_pending_design_update`.

### M9 — `MerchantUpserter.applyFeedback(... dsl: DSLContext)` is passed an explicit DSL — but the design also says it's `@Transactional`; mixing both creates ambiguity
**Evidence:**
- `openspec/changes/backend-mvp-readonly/tasks.md:38` "método `applyFeedback(userId, txId, currentMerchantId, txDescription, newCategoryId, dsl: DSLContext)` que opera dentro de la transacción del caller".
- `openspec/changes/backend-mvp-readonly/design.md:103` "Commit. Return 200 con el DTO actualizado." — implies Spring `@Transactional` boundary in `TransactionService.updateCategory`.
- `openspec/changes/backend-mvp-readonly/tasks.md:50` "abre `@Transactional` con `UPDATE transactions` + `MerchantUpserter.applyFeedback`".
- If `MerchantUpserter` receives a `DSLContext` parameter, the caller decides which connection; Spring's `@Transactional` does NOT automatically rebind a programmatically-passed `DSLContext`. The implementer needs to either (a) pull `DSLContext` from a `TransactionAwareDataSourceProxy`-backed bean (then no need to pass it), or (b) call `dsl.transaction(...)` blocks, which create nested logical transactions (savepoints) and complicate rollback semantics.
- Without clarification the implementer will mix patterns and the "feedback loop transaccional" guarantee in `proposal.md:53` becomes brittle.

**Recommended fix:**
- Either remove the `DSLContext` parameter and let `MerchantUpserter` `@Autowired` a `DSLContext` bean (recommended; standard Spring/jOOQ pattern), OR explicitly document that `MerchantUpserter` runs inside the caller's Spring-managed transaction and the passed `DSLContext` is the one bound to the current `TransactionSynchronizationManager`.
- Tighten tasks.md §4.8 and §5.3 to match.

### M10 — `merchants.raw_pattern UNIQUE(user_id, raw_pattern)` interacts with `normalize()` in a way that can lock out future normalization tweaks
**Evidence:**
- `openspec/changes/backend-mvp-readonly/design.md:154` UNIQUE `(user_id, raw_pattern)`.
- `openspec/changes/backend-mvp-readonly/design.md:108` "raw_pattern se genera por `normalize(description)`... la función queda en `MerchantUpserter.normalize(String)`. Documentada como decisión interna; ajustable sin breaking change."
- This is wrong: if `normalize()` is tweaked later (e.g., to also strip accents per OQ4 follow-up), all existing rows have raw_patterns computed with the OLD normalize. A new UPSERT computes the NEW normalize. The two will collide or duplicate depending on the change. Either:
  - A user that previously had `raw_pattern = "café"` (no accent strip) now adds a new transaction normalized to `"cafe"`. UPSERT inserts a new row. The user now has two merchant entries for the same place. Confidence dilutes across both.
  - OR normalization removes characters that previously produced unique rows, causing UNIQUE violations on UPSERT.
- The "ajustable sin breaking change" claim is false. It's a one-way door once data exists.

**Recommended fix:**
- Either freeze the normalize function in V004 (commit to it as the schema contract) and document that changes to it require a backfill migration, OR store `description_raw` and `description_normalized` separately and put the UNIQUE on `(user_id, description_raw)` (forfeits dedup-by-normalization but is forward-compatible).
- Add an OQ in design.md.

### M11 — No scenario asserts that the `WHERE user_id = ?` invariant cannot be bypassed by abusing the `accountId` filter
**Evidence:**
- `openspec/changes/backend-mvp-readonly/specs/backend-rest-api/spec.md:60-61` Scenario "Filtro por cuenta" — asserts results have `accountId == uuidA` but does not assert what happens if `uuidA` belongs to another user.
- The repository (`tasks.md §4.4`) builds `WHERE user_id = ? AND account_id = ?` in the same query. If the implementer accidentally writes `WHERE account_id = ?` alone (because the `user_id` filter is applied at a wrapper level), passing another user's `accountId` returns nothing (because RLS bypass + WHERE user_id filter wins) — OR returns rows if user_id filter is forgotten.
- This is the exact attack the threat model claims to prevent. The spec has no scenario for it.

**Recommended fix:**
- Add scenario: "userA llama `GET /api/v1/transactions?accountId=<userB.account.id>` → response is `rows: [], hasMore: false, page: 1, pageSize: 25` (the user's filter is intersected with own-data scope; no error to avoid enumeration)."
- Add corresponding scenario for `categoryIds`.

### M12 — Test recipe for "JWT con firma inválida" (spec.md:15-18) and "JWT expirado" (spec.md:19-22) lacks a recipe for how to generate them in tests
**Evidence:**
- `openspec/changes/backend-mvp-readonly/specs/backend-rest-api/spec.md:15-29` lists 4 JWT-failure scenarios.
- `openspec/changes/backend-mvp-readonly/tasks.md:18` "Test `SupabaseJwtFilterTest.java` (unit): ..." — but no task provides a `TestJwtFactory` helper.
- Without a documented test helper, each test will hand-roll JWT signing differently → flaky tests, copy-paste drift, and the OQ2 default ("Tests Testcontainers que necesitan firmar JWTs generan su propio secret en runtime") becomes hand-wavy.

**Recommended fix:**
- Add a task in §2.x: "Create `test/.../auth/TestJwtFactory.java` with builder methods `valid(userId)`, `expired(userId)`, `wrongAudience(userId)`, `wrongIssuer(userId)`, `wrongSecret(userId)`. All other tests consume this factory."

---

## Minors

### m1 — Mixed Spanish/English in specs/spec.md scenarios
Many scenarios mix Spanish keywords (`Lista`, `Filtro por cuenta`, `Cuentas del usuario`) with English BDD verbs. `docs/base-standards.md §5` test naming is English (`should{Result}When{Condition}`). Consider standardising scenario titles to either all-Spanish or all-English for grep/tooling consistency. Cosmetic; does not block.

### m2 — `proposal.md ## Capabilities` notation
`proposal.md:30-36` uses both "New Capabilities" and "Modified Capabilities" — but OpenSpec convention (per `openspec/specs/backend-runtime/spec.md` archived structure) typically uses `ADDED Requirements` / `MODIFIED Requirements`. Not breaking, but consistency with archived examples would help future tooling.

### m3 — `tasks.md §1.3` says "verificar que V001..V004 aplican sin errores" via `docker compose up -d`
The compose orchestrator (`backend/database/init-db.sh` per `backend-runtime` spec) runs V000 stubs from `local/` then V001..Vn from `migrations/`. The phrasing "V001..V004 aplican" should also call out V000 explicitly so the implementer doesn't forget the local stub.

### m4 — `tasks.md §8.5` "documentar en `docs/development-guide.md`" — without a section anchor
The guide is large; specify the section (`§Environment` or new "§Backend MVP local dev") so the edit lands in a discoverable place.

### m5 — `tasks.md §10` (Cierre) lists adversarial-review AFTER `progress.md` update — should be reversed
Adversarial review may surface findings that change `last_completed`/`blockers`. Doing the review before the progress update means progress.md is rewritten one extra time. Trivial; reorder.

### m6 — Task 4.7 `MerchantRepository.incrementConfidence(id, userId)` does not include the cap (0.95)
The cap is in design.md D5 ("LEAST(0.95, ...)"). Tasks should mention that the SQL must include LEAST/CASE, or that the cap is enforced in the service layer. Today it's ambiguous which layer owns it.

### m7 — `progress.md` is fresh (`2026-06-01T18:00:00Z`) and ahead of HEAD (`2026-05-31`-ish for the latest commit on `feat/backend-mvp-readonly`); fine for now but flag if it drifts during implementation
Per the process-tooling check, `progress.md` must be re-written after each closed task. Right now `current_task: none` is accurate.

### m8 — Auth Requirement scenario "Endpoints públicos no requieren JWT" (spec.md:31-33) only tests `/actuator/health`; the requirement text also names `/actuator/info`
Add a parallel scenario for `/actuator/info` or amend the existing scenario to include both URLs.

### m9 — `design.md:140` D7 says CI gate is "out of scope of este change" but `tasks.md §11.3` already files it as `CHANGE api-spec-ci-gate`
Consistent, but `proposal.md:23` "CI gate ya queda preparado para diff-arlo contra los handlers (siguiente change)" — the word "preparado" suggests scaffolding lands in this change. Clarify whether anything ships in this change (no, per §11.3) and align wording.

### m10 — Threat model line `proposal.md:58` "Replay del PATCH con `categoryId` distinto al actual — es la operación legítima del modal; no hay nada que prevenir aquí." — true but does not mention what happens if a replay arrives AFTER another concurrent PATCH changed the value
Last-write-wins by default. Acknowledge it explicitly (one sentence) to close the door on "implicitly we did optimistic locking", which the design does NOT include.

---

## Questions

1. **Q1 (re B1) — Should V004 be split into `V004__categories_display_name.sql` + `V005__merchants.sql`, or folded into a single V004?** The data-model.md §3 reservation favours the split; the operator's stated intent is to keep V004 single. Pick one before §1.1 starts.

2. **Q2 (re B7) — What is the actual signing algorithm of the Supabase project `akkoqdjmmozyqdfjkabg` right now (2026-06)?** A 60-second check via `curl https://akkoqdjmmozyqdfjkabg.supabase.co/auth/v1/jwks` resolves it. If 200 + keys → asymmetric; if 404 → symmetric. The change should not commit to HS256 until this is verified.

3. **Q3 (re B5) — Are `+0.05` and cap `0.95` intentional re-tunings of SPEC.md §7's `+0.1` and threshold `0.85`?** If yes, SPEC.md needs an amendment; if no, design.md needs to be corrected. The operator should pick one.

4. **Q4 (re M2) — When the user re-categorizes a transaction whose merchant already has a different `category_id`, what should happen to `merchants.category_id`?** Default proposal: replace + reset confidence to `0.5`. Operator to confirm before §5.x lands.

5. **Q5 (re B6) — Is "PATCH with non-visible categoryId" a 400 (with `validation-error`) or a 404 (to avoid enumeration)?** Either is defensible; pick one consistently with the existing 404-for-other-users'-tx pattern (spec.md:43-45 picks 404 to avoid enumeration → 404 here is consistent).

6. **Q6 (re B3, B4) — Are the missing schema columns (`accounts.name`, `categories.display_name`, `categories.parent_id`) supposed to be added by this MVP or mapped from existing columns?** The proposal says "shape inventado por el MVP frontend — el mapper privado del frontend desaparece tras la migración" (line 21) — implying we adopt the invented shape. But it doesn't say which side gives way. Decision needed before §3.x and §4.x.

7. **Q7 — Should `/actuator/env` and `/actuator/configprops` be explicitly forbidden in `application.yml`?** Spring's defaults already do that, but a new dependency could enable them silently. Worth pinning.

---

## Verdict

**FAIL** — 7 Blockers, 12 Majors, 10 Minors, 7 Questions. The most urgent are B1 (categories.display_name column missing from V004 will fail compilation/queries the moment §3.x runs) and B6 (cross-user categoryId write is an unmitigated horizontal-privilege vector). Both must be resolved in design.md/specs/tasks.md before `/opsx:apply` starts.

## Counts
- Blockers: 7
- Majors: 12
- Minors: 10
- Questions: 7
