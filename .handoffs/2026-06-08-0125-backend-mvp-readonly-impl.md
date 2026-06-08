---
created: 2026-06-08T01:25:00Z
branch: feat/backend-mvp-readonly
worktree: .claude/worktrees/backend-mvp-readonly
mode: blocked
---

# backend-mvp-readonly — §1 + §2 artefacts written, Docker offline blocks GREEN validation

## Next step
Operator starts Docker Desktop, then from `backend/` runs `./mvnw test "-Dtest=V004CategoriesDisplayNameTest,V005MerchantsTest"` — expect 10 tests green; on green I delegate §3 (Auth + JWKS + TestJwtFactory + WireMock) via backend-developer subagent.

## Goal
Implement OpenSpec change `backend-mvp-readonly`: deliver the 4-endpoint REST API surface (GET transactions/accounts/categories, PATCH transactions/{id}/category) that lets the deployed Vercel frontend swap from direct Supabase (currently 403) to backend-mediated access. Backend-only path to `myfinance.*` schema — no PostgREST exposure.

## Done this session
- Adversarial review v1 → FAIL (7 Blockers, 12 Majors); rewrote all 5 artefacts (proposal/design/spec/tasks/data-model) absorbing every finding.
- Adversarial review v2 → PASS WITH GAPS (0 Blockers, 2 Majors, 4 Minors).
- Resolved 5 critical operator decisions: V004=display_name + V005=merchants split, AccountDTO.name←nickname mapper, CategoryDTO drops parentId, confidence +0.10/1.00 (SPEC.md wins), drift detection on user re-categorize.
- Verified Supabase JWT is ES256/JWKS (not HS256) via direct curl probe; refactored D1 to OAuth2 Resource Server + `NimbusJwtDecoder.withJwkSetUri`.
- §1 + §2 artefacts written by backend-developer subagent: `V004__categories_display_name.sql` + `V005__merchants.sql` + 2 test classes (10 tests total).
- Discovered V003 reality: `categories.name` holds English display labels (`'Dining Out'`, `'Housing'`), NOT snake_case keys. Propagated correction to design.md D8, spec.md, tasks.md §11.2. Saved memory `project-categories-name-is-english-display-label`.

## Working tree state
- **Committed (this session):** none (push policy: local-only until operator asks PR).
- **Staged:** none.
- **Unstaged (modified):**
  - `docs/data-model.md` — V-number registry: V004=display_name, V005=merchants; shifted V006–V009 by +1; updated indexes table.
  - `openspec/changes/backend-mvp-readonly/proposal.md` — threat model updated (IDOR B6, zero-echo M7, actuator M8, drift).
  - `openspec/changes/backend-mvp-readonly/design.md` — fully rewritten; 12 decisions (D1–D12), all 5 OQs closed.
  - `openspec/changes/backend-mvp-readonly/specs/backend-rest-api/spec.md` — 9 Requirements, ~50 scenarios.
  - `openspec/changes/backend-mvp-readonly/tasks.md` — 13 sections, hard operator gate at §10.5 with literal `proceed` token.
  - `openspec/changes/backend-mvp-readonly/progress.md` — current task=Task #9; blocker=docker_daemon_offline.
- **Untracked (new this session):**
  - `backend/database/migrations/V004__categories_display_name.sql` — ADD COLUMN display_name NOT NULL + backfill 19 system categories with Spanish names matching V003's English `name` keys.
  - `backend/database/migrations/V005__merchants.sql` — merchants table + RLS + `transactions.merchant_id` FK + `idx_transactions_merchant_id` partial + bonus `idx_merchants_user_category`.
  - `backend/src/test/java/com/myfinanceview/db/V004CategoriesDisplayNameTest.java` — 3 Testcontainers tests.
  - `backend/src/test/java/com/myfinanceview/db/V005MerchantsTest.java` — 7 Testcontainers tests.
  - `openspec/changes/backend-mvp-readonly/notes/adversarial-review-2026-06-02.md` — v1 review (FAIL).
  - `openspec/changes/backend-mvp-readonly/notes/adversarial-review-2026-06-02-v2.md` — v2 review (PASS WITH GAPS).
- **Red tests:** unknown — Docker offline, Testcontainers cannot run. Artefacts not yet validated GREEN.

## Pending
- Task #9 GREEN validation (operator action — Docker).
- Task #10: §3 Auth — SecurityConfig + `NimbusJwtDecoder.withJwkSetUri` + TestJwtFactory (EC P-256 ephemeral) + JwksWireMockExtension + 8 tests.
- Task #11: §4 DTOs + Jackson + Mappers (AccountMapper does nickname→name; CategoryDTO drops parentId).
- Task #12: §5 jOOQ repositories + isolation tests + visibility guard test.
- Task #13: §6 Services + transactional boundaries + drift detection + atomicity test recipe (FK violation via deleteFrom in same tx).
- Task #14: §7 Controllers + ProblemDetail zero-echo + CORS 403.
- Task #15: §8 OpenAPI spec sync `docs/api-spec.yml`.
- Task #16: §9 Config + env vars + actuator hardening YAML.
- Task #17: §10 STOP — operator gate; pre-V004 backup + state snapshot; literal `proceed` required.
- Task #18: §11 Apply V004+V005 to Supabase remote via MCP (only after `proceed`).
- Task #19: `/commit` + PR + `/opsx:archive` + `/openspec-sync-specs` at close.

## Blockers
- **docker_daemon_offline** — Docker Desktop is not running on this host. Testcontainers cannot start a Postgres container, so the 10 tests for §1+§2 cannot execute RED→GREEN. Unblocks when operator starts Docker Desktop. Blocks Task #9 validation AND all downstream Testcontainers tasks (#10–#14).

## Non-obvious context
- **V003 seeded `categories.name` with English DISPLAY labels** (`'Housing'`, `'Dining Out'`, `'Food & Groceries'`), not snake_case internal keys. Any SQL/jOOQ touching categories MUST match this. Memory `project-categories-name-is-english-display-label.md` documents it. The architectural concept (`name`=stable internal key, `display_name`=user-facing Spanish) still holds — only the *values* in `name` are English strings.
- **V005 SQL uses bare `uuid_generate_v4()`** (V001 style) instead of `extensions.uuid_generate_v4()` (Supabase convention in design.md D8). Works in both local Postgres and remote Supabase via search_path. If Supabase ever drops `extensions` from search_path, switch to qualified form. Documented as risk R1 in progress.md.
- **Bonus `idx_merchants_user_category` index** added in V005 (not in tasks.md §2) — preemptive for `(user_id, category_id)` joins in MerchantUpserter drift detection. No test asserts on it.
- **Hard operator gate** at `tasks.md §10.5` requires literal `proceed` token from operator before any `apply_migration` to Supabase remote. Authorization delegated 2026-06-02 is conditional to this gate — do NOT skip it even with prior "procede" autonomous mandate.
- **Push policy local-only** until operator explicitly asks PR. Do NOT run `/commit` proactively; only commit when operator requests or at agreed chunk closures.
- **Architectural rule (memory `project_backend_only_path_to_myfinance_schema`):** `myfinance.*` SHALL NEVER be exposed in PostgREST; roles `anon`/`authenticated` SHALL NEVER receive GRANT. Only path is backend Java with `service_role` + `WHERE user_id = ?`.
- **adversarial-review-2026-06-02.md was originally written outside the worktree** (in main checkout untracked). Copied into the worktree so the audit trail travels with the branch. Both v1 and v2 are now untracked here.
- **progress.md is the canonical resume mechanism** per CLAUDE.md `/opsx:apply` workflow; this handoff complements but does not replace it. Both updated.

## Sanity check before resuming §3
On Docker-up GREEN, before delegating §3: re-read `openspec/changes/backend-mvp-readonly/tasks.md §3` (Auth) — the subagent recipe specifies TestJwtFactory + WireMock JWKS server, NOT TestSecurityConfig overrides. The JWKS WireMock pattern is the one that survived adversarial review v2; do not regress to a shortcut.
