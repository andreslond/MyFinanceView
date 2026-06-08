# Adversarial Review (v2) — `backend-mvp-readonly` — 2026-06-02

Scope: rewritten artefacts after v1 (which surfaced 7 Blockers + 12 Majors + drift detection). This pass independently red-teams the rewrite. Inputs: `proposal.md`, `design.md`, `specs/backend-rest-api/spec.md`, `tasks.md`, `progress.md`, `docs/data-model.md §3`, `docs/base-standards.md`, `docs/backend-standards.md`, memory rule `project-backend-only-path-to-myfinance-schema`, `notes/adversarial-review-2026-06-02.md` (v1, for absorption check).

No code yet in this change — artefact-only review.

---

## Phase A — Absorption check vs v1 findings

| v1 finding | Absorbed in rewrite? | Evidence |
|---|---|---|
| **B1** V004 split (display_name) + V005 (merchants) | ✅ | `design.md:212-294` (D8 split), `tasks.md:1-17` (§1 + §2), `proposal.md:28-30`, `data-model.md:124-154` aligned. No stray reference to "V004=merchants". |
| **B2** Cascade V005→V006, V006→V007, V007→V008 | ✅ | `data-model.md:122,156,166,177` — sequence consistent (V004 display_name, V005 merchants, V006 cut/payment, V007 installments, V008 categorization). `proposal.md:98` matches. |
| **B3** CategoryDTO drops parentId | ✅ | `design.md:180,200` (D6 drop), `spec.md:188,197,199-201` (scenario "CategoryDTO no incluye parentId"), `tasks.md:34,43,101,151,198` consistent, `proposal.md:18,26,33,83,89` consistent. |
| **B4** AccountDTO.name ← accounts.nickname | ✅ | `design.md:169` (mapper table), `spec.md:171,177-180` (scenario "name se sirve desde nickname"), `tasks.md:33,38,42` (mapper + test), `proposal.md:26,83` consistent. |
| **B5** Feedback loop +0.10 cap 1.00 | ✅ | `design.md:113,125,136` (operator decision recorded), `spec.md:119-126` (scenarios use 0.70 and 1.00), `tasks.md:59` (LEAST(1.00, +0.10)), `proposal.md:21-22` matches. |
| **B6** PATCH visibility guard → 404 | ✅ | `design.md:317-352` (D10 dedicated), `spec.md:68-71,147-154` (anti-IDOR scenarios), `tasks.md:65,87` (§5.13 + §6.10), `proposal.md:20,58-59` mentions IDOR vector. |
| **B7** ES256 + JWKS (not HS256) | ✅ | `design.md:40-75` (D1 rewritten), `spec.md:3-5` (Requirement), `tasks.md:22` (withJwkSetUri + ES256 algo). `proposal.md:14-15,52,56` mentions. **No** lingering `withSecretKey` / `SUPABASE_JWT_SECRET` outside historical/rejected-alternative context (verified via grep). |
| **M1** Atomicity scenario reachable | ✅ (partial) | `spec.md:164-167` uses "FK violation por inconsistencia inducida por test" — still slightly artificial; see Major below. |
| **M2** Drift detection | ✅ | `design.md:111-150` (D5 drift branch), `spec.md:128-131` (drift scenario), `tasks.md:85` (§6.8 dedicated test). |
| **M3** Pagination row count 489 vs 362 | ✅ | `spec.md:87` uses 362. No stray 489 anywhere (verified via grep). |
| **M4** CORS preflight rejected → 403 explicit | ✅ | `design.md:296-315` (D9), `spec.md:227-241` (Requirement + scenarios), `tasks.md:98` (§7.6), `tasks.md:103` (§7.11 test). |
| **M5** Malformed Authorization scenarios | ✅ | `spec.md:15-17`, `tasks.md:104` (§7.12). |
| **M6** `categoryId: null` body semantics | ✅ | `spec.md:156-158` (Scenario "Body con categoryId ausente o null"), `tasks.md:36` (`@NotNull UUID`). |
| **M7** Zero-echo ProblemDetail | ✅ | `design.md:354-395` (D11), `spec.md:208-225` (Requirement + scenarios), `tasks.md:96,102` (§7.4 + §7.10 + §3.8). |
| **M8** Actuator surface hardened | ✅ | `design.md:354-382` (YAML in D11), `spec.md:255-277` (Requirement + scenarios for /info, /env, /configprops, /heapdump), `tasks.md:108-141` (full YAML), `tasks.md:146` (§8.6 ActuatorSurfaceTest covers /metrics too). |
| **M9** MerchantUpserter sin DSLContext | ✅ | `tasks.md:71` ("sin parámetro DSLContext"). |
| **M10** raw_pattern normalize freeze | ✅ | `design.md:397-442` (D12 frozen with exact regex + 2-digit threshold), `tasks.md:62` test cases. |
| **M11** WHERE user_id + cross-user accountId | ✅ | `spec.md:73-76` (scenario "PATCH con accountId cross-user en query filter es ignorado"). |
| **M12** TestJwtFactory + WireMock | ✅ | `design.md:61-66`, `tasks.md:24-25` (§3.4 + §3.5). |

**Absorption summary:** 7/7 Blockers absorbed; 12/12 Majors absorbed (some with residual issues — see findings below). Drift detection is added as design D5 + spec scenario + dedicated test §6.8. The rewrite was structurally honest.

---

## Phase B — New findings (independent red-team of the rewrite)

### Blockers — none

### Majors

#### N-Maj-1 — `spec.md` "Scenario: Atomicidad" still describes a path the design cannot naturally reach
**Evidence:**
- `spec.md:164-167` (Scenario: Atomicidad — rollback si el feedback loop falla): "un escenario artificial donde el UPDATE de `merchants` falla (e.g. FK violation por inconsistencia inducida por test)".
- `design.md:122-128` Branch A (re-confirm + drift) does not modify a FK column on `merchants` that could realistically violate. Branch B's UPSERT writes `category_id = body.categoryId` (already passed the visibility guard of D10); the FK is to `categories(id)` and the row exists per the guard.
- The remaining realistic failure path would be: a parallel DELETE of the category between guard and UPSERT. `design.md:338` acknowledges this race: "no hay endpoint para borrar categorías en este MVP".
- Consequence: the test will likely (a) be skipped, (b) use `@SpyBean` / mock to throw — drifting toward DB mocking, the project anti-pattern, or (c) manually `dsl.deleteFrom(CATEGORIES).execute()` in the same transaction to force a 500.
- v1 M1 said the same; the rewrite kept the wording but did not give the test author a clean recipe.

**Recommended fix (artefact):**
- `tasks.md §6.11`: pick option (c) explicitly — "test borra la categoría dentro del mismo tx via `dsl.deleteFrom(...)` para forzar el FK violation en el UPDATE de `transactions.category_id`". Document that the FK between `transactions.category_id` and `categories.id` is the actual rollback trigger (not the merchant UPDATE).
- OR rewrite the scenario to assert atomicity via a different concrete trigger: the `txRepo.updateCategory` returning 0 rows affected when the transaction was deleted by a parallel admin — testable, and matches the assertion in `tasks.md:79` ("assert 1 row").

#### N-Maj-2 — `design.md` D6 retains a transitional fallback "`CategoryDTO.name` cae al `categories.name` (English internal key)" that contradicts the spec's NOT NULL guarantee and could ship in production
**Evidence:**
- `design.md:176`: "Hasta que V004 se aplique a remoto, `name` cae al `categories.name` (English internal key) — ver D8 para el plan de transición."
- `spec.md:188` Requirement: "`CategoryDTO.name` se mapea desde `categories.display_name` (ES, NOT NULL post-V004)".
- `design.md:462` Migration Plan step 1 says "V004 + V005 SQL escritas y testeadas local"; `tasks.md §11.1-11.2` apply V004 first, then `tasks.md §11.3` V005 — but the code that maps `display_name → name` (`tasks.md §4.8`, §5.3) is built **before** §10 (operator gate) and §11 (apply to remote). If the code is merged before V004 is applied to remote (an ordering bug that `proposal.md:108` actually warns about), every GET /categories returns 500 because `categories.display_name` does not exist in the column set.
- The "fallback to `name`" path D6 documents is **never tested** in the spec (no scenario asserts behaviour when `display_name` is NULL or column absent), and `tasks.md §4.8` / `tasks.md §5.3` hard-code `DISPLAY_NAME` references.
- This creates a hidden ambiguity: D6 says fallback exists; spec + tasks say "no, hard-fail". The implementer will pick one and it may not match the deploy ordering.

**Recommended fix (artefact):**
- Strike the fallback sentence in `design.md:176`. State unambiguously: "El backend depende de V004 aplicada a remoto. Hasta entonces el backend no se considera deployable. La Migration Plan de design.md §Migration Plan ya impone V004 antes del merge a `main`."
- Add scenario to `spec.md` GET /categories Requirement: "Backend asume `categories.display_name NOT NULL`; un error de schema produce 500 con `ProblemDetail` genérico" — or remove the path entirely.

### Minors

#### N-Min-1 — `tasks.md §5.13` does not assert behaviour on `findByIdVisibleToUser(non-existent-uuid, A) → empty`
**Evidence:**
- `tasks.md:65`: covers `cat-system`, `cat-A-own`, `cat-B-private`. Missing the "UUID inexistente en absoluto → empty" case.
- `spec.md:152-154` Scenario "categoryId no existe en absoluto → 404 (mismo tratamiento que IDOR)" requires this path.
- Without the repo-level test, the contract test at `tasks.md:99` will be the sole assertion — the repository contract is left unstated.

**Fix:** add one bullet to §5.13: `findByIdVisibleToUser("00000000-0000-0000-0000-000000000000", A) → empty`.

#### N-Min-2 — Drift scenario does not specify what `merchants.display_name` becomes
**Evidence:**
- `design.md:127`: drift UPDATE is `SET category_id = ?, confidence = 0.50, match_count = 1, last_confirmed_at = NOW(), updated_at = NOW()`. **`display_name` is not touched.**
- `spec.md:128-131` drift scenario asserts category_id/confidence/match_count/last_confirmed_at but not display_name.
- This is probably correct (the human-readable label of the merchant shouldn't churn just because the user re-categorized), but it is implicit. A future reader of D5 may incorrectly UPDATE `display_name` on drift.

**Fix:** add one sentence to D5: "El UPDATE de drift NO modifica `merchants.display_name` — el label visible del merchant permanece estable; sólo cambia la categoría aprendida y se resetea la confianza."

#### N-Min-3 — `spec.md` JWKS rotation scenario does not assert that the second request *triggered* a refresh (could be vacuously satisfied by WireMock serving both keys)
**Evidence:**
- `spec.md:43-46`: "GIVEN un decoder con caché de JWKS cargado para `kid=K1`; WHEN Supabase rota la clave a `kid=K2` y un cliente envía un JWT firmado con `K2`; THEN el sistema invalida el cache, refresca el JWKS, verifica con `K2`, y responde 200".
- `design.md:66`: "cambiar la clave en WireMock mid-test y asertar que el segundo request requiere refresh".
- `tasks.md:26`: `should200WhenJwksRotated` (cambia la clave en WireMock mid-test).
- Implementation pitfall: if `TestJwtFactory` + `JwksWireMockExtension` serves a JWKS containing **both** K1 and K2 from the start, the second request will succeed without ever fetching a new JWKS — the `Nimbus` decoder caches keys by `kid` and a JWT with `kid=K2` will match the cached set without a refresh. The test passes but it doesn't prove rotation works.
- Robust assertion requires WireMock `verify(2, getRequestedFor(urlPathEqualTo("/jwks")))` or equivalent — count the JWKS fetches.

**Fix:** in `tasks.md §3.6`, add to the `should200WhenJwksRotated` test description: "asserta que WireMock recibió ≥ 2 GET al endpoint JWKS (no count vacuoso)". Alternatively, `WireMock.scenarios` to serve only K1 first and only K2 after a state change.

#### N-Min-4 — `tasks.md §4.11` (AccountMapperTest) does not assert that the DTO has NO `nickname` property when serialized to JSON
**Evidence:**
- `tasks.md:42`: "dado `AccountsRecord` con `nickname = "Davivienda Signature"`, `fromRecord` produce `AccountDTO(..., name="Davivienda Signature", ...)` — sin campo `nickname` en el DTO".
- This is a record-shape assertion. The serialization assertion ("`{"name": "...", ...}` does not contain `"nickname"`") would be cleaner via `JacksonSerializationTest` at `tasks.md §4.10`. Currently §4.10 only checks `amount` and `occurredAt`.
- Leak risk is low (a record has only the fields it declares), but the contract is stronger if asserted via JSON.

**Fix:** add an assertion in `tasks.md §4.10`: "el JSON output de `AccountDTO` no contiene la clave `nickname`".

### Questions

#### N-Q-1 — Idempotency vs visibility guard interaction
**Evidence:**
- `design.md:119` step 3: "Si `body.categoryId === tx.categoryId` → return 200 con el DTO actual, sin tocar DB. **Idempotencia exacta**".
- `design.md:117` step 1: "Visibility guard del `categoryId`... antes del UPDATE".
- Order ambiguity: does step 1 (guard) run **before** step 3 (idempotency short-circuit)?
  - If yes (current design.md reading): a PATCH with `body.categoryId === tx.categoryId` where `tx.categoryId` happens to be a category that became invisible to the user (e.g., user-scoped category that was admin-deleted in another tab) → 404 from guard, but the data is unchanged. Confusing UX.
  - If no (guard skipped for no-op): the same scenario succeeds (no DB hit), but you've leaked "the current categoryId is still readable" implicitly.
- Spec scenarios at `spec.md:138-141` (Idempotencia) assume `body.categoryId == catA` and the test seeds categoryA as visible — they don't cover the deleted-category edge.

**Resolution requested before §6.4 lands:** does the visibility guard run before or after the idempotency short-circuit?
- Default proposal: **before** (current D5 reading). Rationale: idempotency is a performance optimization; the security invariant "no PATCH with non-visible categoryId returns 200" should dominate. The deleted-category edge is rare and the 404 is technically correct ("the resource you're trying to write to is not visible to you").
- Document the choice in D5 step ordering and add a one-line scenario.

#### N-Q-2 — Operator gate (`tasks.md §10`) strength
**Evidence:**
- `tasks.md:156`: header "**STOP — Operator gate antes de aplicar a Supabase remoto**".
- `tasks.md:158`: "**STOP — operator gate manual.** Agente publica resumen..."
- `tasks.md:166`: §10.4 ends with `mcp__claude_ai_Supabase__list_migrations` — agent action, not an operator action.
- The phrasing is strong ("STOP", boldface), but no §10.x line explicitly says "operator types `apply` in chat / responds with explicit confirmation before the agent proceeds to §11". §11.1 starts with "Agente ejecuta apply_migration" without an intervening operator approval bullet.
- Risk: an `/opsx:apply` session could interpret §10 as "agent does the snapshot and continues into §11 because §10 has no explicit operator-input pause".

**Resolution requested:** clarify §10 with a closing bullet "10.5 **OPERATOR INPUT REQUIRED** — Agente espera string literal `proceed` del operador antes de pasar a §11. Si el operador responde otra cosa o no responde en 24h, agente NO ejecuta §11 y registra blocker en `progress.md`."

#### N-Q-3 — `Authorization: Bearer ` (empty) and other malformed-header scenarios are listed in `tasks.md §7.12` but only one is explicit in spec
**Evidence:**
- `spec.md:15-17` Scenario "Authorization header malformado": lists `NotBearer xyz`, `Bearer` (sin token), `<token-sin-prefijo>`.
- `tasks.md:104` §7.12 covers same three. Consistent.
- Edge not covered: `Authorization: Bearer abc.def` (only two segments — malformed JWT). v1 M5 explicitly called this out as a gap. The rewrite consolidated "malformed" into one scenario but only the prefix/scheme cases; the JWT-shape case is implicitly covered by "JWT firmado con clave distinta" (`spec.md:19-21`) but the parser failure happens before signature verification.
- Is this intentional or an oversight? Both behave as 401; the scenario count matters for spec rigor.

**Resolution requested:** confirm the three-segment-shape malformed JWT is covered by the existing "JWT inválido" path, or add a fourth WHEN bullet in `spec.md:15-17`: "Bearer abc.def" (two segments).

---

## Phase C — Architecture guardrail spot-checks

- `myfinance.*` PostgREST exposure: explicitly rejected in `proposal.md:8,100,123`. ✅
- JPA / Hibernate / WebFlux / Reactor: no mention except in the negation in `design.md:12`. ✅
- Clean Architecture in layers: not introduced. Packages follow `api/`, `domain/{transaction,account,category,merchant}`, `config/`. ✅
- Money as `BigDecimal`: `design.md:159`, `spec.md:243-253` (Requirement BigDecimal serialization). The only `double`/`float` mentions are in the negative ("no pasar por `double`/`float`" in spec.md:245 and forbidden-list contexts). ✅
- Time as `OffsetDateTime`: `design.md:13,158`. No `LocalDateTime` for timestamps (only one stray mention of "LocalDateTime" — false positive, none in artefacts). ✅
- IDs as `UUID`: consistent across DTOs, repos, JWT `sub` parsing. ✅
- DB mocking proposed in any test: no — `tasks.md §5.11, §5.12, §5.13, §6.6-§6.12` all say Testcontainers. ✅ (but see N-Maj-1: atomicity scenario risks drifting toward mock).
- DTOs as records: `tasks.md §4.1-§4.5` all say `record`. ✅
- ProblemDetail (not raw exceptions): `tasks.md §7.4`, spec.md "ProblemDetail" Requirement. ✅
- Testcontainers + real DB: enforced. ✅

---

## Phase D — TDD discipline spot-check

- `tasks.md §3` (auth): SecurityConfig in §3.1, JwtDecoder in §3.2, Converter in §3.3 → TestJwtFactory in §3.4, WireMock extension in §3.5 → tests in §3.6-§3.8. Tests are at the **end** of the section, not before each piece — slightly non-TDD ordering but defensible because the test infrastructure (TestJwtFactory) needs to exist before tests using it. Minor inversion, not a blocker.
- `tasks.md §4` (DTOs + mappers): types in §4.1-§4.9 → tests in §4.10-§4.12. Same pattern; acceptable for record/mapper unit tests because the implementations are trivial.
- `tasks.md §5` (repos): repos in §5.1-§5.9 → tests in §5.10-§5.13. Acceptable.
- `tasks.md §6` (services): services in §6.1-§6.5 → tests in §6.6-§6.12. Acceptable.
- `tasks.md §7` (controllers): controllers in §7.1-§7.6 → tests in §7.7-§7.12. Acceptable.

TDD discipline is **batch-level, not task-level** in the rewrite. The agent applying these tasks should reorder each section to write test-first. The current ordering is a style not a blocker — `docs/base-standards.md §3` says TDD is per-task RED→GREEN→REFACTOR; this should be enforced by the `backend-developer` subagent, not by the tasks.md ordering. Flag noted.

---

## Phase E — Cross-check vs new questions raised by reviewer

| Question | Verdict | Note |
|---|---|---|
| Spec scenario contradicts a design decision? | No major contradictions found. Minor: D6 fallback-to-`name` line not echoed in spec (see N-Maj-2). |
| Migration Plan order matches tasks.md? | Yes — `design.md:457-475` Migration Plan ↔ `tasks.md` §1-§12 are aligned (V004/V005 → auth → DTOs → repos → services → controllers → spec yml → actuator → operator gate → apply remote → close). |
| Operator gate §10 is a STOP? | Phrasing is strong, but no explicit "wait for operator string". See N-Q-2. |
| PATCH visibility guard ↔ idempotency interaction? | Not addressed — see N-Q-1. |
| Drift branch: `merchants.last_confirmed_at` set to NOW? | Yes, both re-confirm and drift set `last_confirmed_at = NOW()` (`design.md:125,127`). Consistent. |
| Spec scenario count = 362 (not 489)? | Yes — `spec.md:87` uses 362. No 489 anywhere in change artefacts. ✅ |
| Actuator hardening covers `/metrics` and `/prometheus`? | `/metrics` covered (`design.md:380` line via list, `tasks.md:139` explicit, `tasks.md:146` test). `/prometheus` not mentioned. Probably fine because micrometer-prometheus is not on classpath of scaffolding — but the YAML `management.endpoints.web.exposure.include: health` is a positive allowlist so any future Prometheus dep would still 404 by default. Documented behaviour OK. |
| JWKS rotation test asserts refresh actually happened? | Phrasing says "asertar que el segundo request requiere refresh" — but not enforced via WireMock request count. See N-Min-3. |
| Lingering HS256 / JWT_SECRET / SUPABASE_JWT_SECRET? | All occurrences are in rejected-alternative / historical / threat-model-defense contexts (`design.md:42,69,70,447,487`; `proposal.md:52,56,103`; `tasks.md:144` says "Eliminar"). No live config. ✅ |
| `merchants.display_name` on drift? | Not specified. See N-Min-2. |

---

## Verdict

**PASS WITH GAPS** — 0 Blocker / 2 Major / 4 Minor / 3 Question.

The rewrite absorbed every v1 Blocker (B1–B7) and every v1 Major (M1–M12) with structurally consistent edits across `proposal.md`, `design.md`, `spec.md`, `tasks.md`, `progress.md`, and `docs/data-model.md`. No live HS256 / `JWT_SECRET` / `SUPABASE_JWT_SECRET` references. No `myfinance.*` PostgREST exposure. No JPA / WebFlux / `double` for money / `LocalDateTime` for timestamps. The migration sequence is internally consistent (V004=display_name, V005=merchants, V006+ cascade by +1).

Residual issues are non-blocking but worth resolving before `/opsx:apply`:
- **N-Maj-1** (atomicity scenario reachability) and **N-Maj-2** (D6 transitional fallback contradicting NOT NULL guarantee) — both inherited weaknesses from v1 that the rewrite did not fully close.
- **N-Q-1** (idempotency vs visibility guard ordering), **N-Q-2** (operator gate STOP strength), **N-Q-3** (Bearer abc.def edge) — operator decisions, fast to resolve.
- 4 Minors are cosmetic / test-tightening.

Recommend: address N-Maj-1 + N-Maj-2 + N-Q-1 + N-Q-2 in a single 15-minute edit pass before `/opsx:apply` starts §1. The Minors can land mid-implementation.

## Counts
- Blockers: 0
- Majors: 2
- Minors: 4
- Questions: 3
