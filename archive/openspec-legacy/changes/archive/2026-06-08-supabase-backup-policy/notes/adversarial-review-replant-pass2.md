# Adversarial review — supabase-backup-policy replant — PASS 2 (2026-06-01)

Reviewer: adversarial-reviewer subagent, Opus 4.7 (1M context), invoked from
main worktree at `D:\dev\workspace\MyFinanceView\.claude\worktrees\supabase-replant`.

Scope (per the second-pass brief): re-check the three Blocker/Major fixes (B1
delta spec replant, B2 rclone entrypoint shim, M1 test-smoke cruft) and Q1
preflight evidence; hunt for NEW issues introduced by the fixes; verify the
unfixed findings are documented as follow-ups (NOT silently dropped).
Out-of-scope adversaries (per `proposal.md ## Threat model`) excluded.

---

## Verdict
**PASS WITH GAPS**

The three Blockers/Majors authorised for fix in this session (B1, B2, M1) are
correctly resolved; Q1 is answered in `progress.md`. The replanted v3 delta
spec is internally consistent — every surviving Requirement traces to shipped
code, and every "deleted" v2 concept (dual-recipient, healthchecks.io, public
webhook, Traefik allowlist, four-channel) appears only in correctly-framed
"cut" / "deleted" prose. The entrypoint shim correctly uses rclone's
documented `RCLONE_CONFIG_<NAME>_<KEY>` env-var-based remote definition; tini
PID 1 semantics are preserved (`ENTRYPOINT ["/sbin/tini", "--",
"/usr/local/bin/docker-entrypoint.sh"]`); the smoke-check (`rclone
listremotes | grep -q ^r2:$`) is local-only (no network call needed) and
catches misspelled env vars at container start.

Two NEW Minor findings introduced or surfaced by the fixes prevent a clean
PASS but do not block archive: (N1) drill-JSON key drift between the
replanted spec (`paperIntact`, camelCase per the new mandate at line 230)
and the README + tasks heredoc that still writes `paper_intact` (snake_case);
(N2) the same camelCase mandate is silently violated by error-body keys in
the spec itself (`quarantined_to`, `local_sha256`, `r2_sha256` at spec
lines 64, 69, 215) — code matches spec at those points, so it's a
spec-internal inconsistency rather than a code-vs-spec drift. One Question
about scope-discipline (Q-N1) on the partial M4 resolution.

The previously-documented Majors (M2 IF wiring, M3 errorWorkflow binding by
name, M4 snake_case vs camelCase) and Minors (m1-m6) and Q2 are all
acknowledged in `progress.md` line 14 or in the pass-1 report at
`notes/adversarial-review-replant.md` as deliberate follow-up deferrals.

---

## First-pass status

- **B1 (delta spec v3): FIXED.** `openspec/changes/supabase-backup-policy/specs/database-backups/spec.md` is now 458 lines (was 521); every v2 concept that contradicted the code is removed or correctly framed as a cut:
  - Dual-recipient encryption Requirement → replaced with single-recipient Requirement at lines 71-104, named "Encryption with a single project-owned age recipient, single-paper custody (v3 Gate C cut B1)". The Scenario at line 84 explicitly asserts `recovery.txt` is absent. The Scenario at line 370 re-asserts the same in the layout Requirement.
  - Public pre-op webhook + Traefik IP allowlist → entire Requirement removed; "Manual on-demand pre-operation snapshot" Requirement at lines 37-69 is now manual-trigger-only via n8n UI (`Set Reason` → `Validate Reason` Code node), matching `n8n/MyFinanceBackup-PreOp.json` and `backup-preop.sh`. Line 39 explicitly states "There is NO public webhook (v3 Gate C cut M3 …)".
  - healthchecks.io off-VPS dead-man-switch Requirement → entirely removed; "Three-leg alerting (ntfy + Gmail + Kuma)" Requirement at lines 232-276 is the v3 successor, with the rationale documented at line 241 ("Whole-VPS outage compensating signal — the parallel Gmail-ingest workflow…").
  - "Four-channel alerting" wording → cleansed; the only remaining mention at line 234 is the explanatory "retracted the original fourth leg" framing.
  - Repository-layout Requirement at lines 344-358 — `recipients/recovery.txt` is no longer listed; the entrypoint shim is correctly cited at lines 348 and 350.
  - Acknowledged-coverage-gaps item 6 at line 331 — Traefik dynamic config explicitly confirmed NOT in this change ("Traefik dynamic config does NOT exist in this change (v3 Gate C cut M3 …)").
  - **Coherence check (no orphan references to deleted Requirements):** I grepped the spec for any "see Requirement X" pointer where X is now deleted; none found. The "Encryption …" Scenario at line 84 cross-refs the v3 cut framing rather than a v2 sibling; the alerting Requirement cross-refs `proposal.md ## Threat model` rather than the deleted dead-man-switch Requirement; the Quarantine Requirement scenarios at lines 213-230 reference the in-place Three-leg alerting Requirement correctly.
  - **Coherence check (surviving Requirements ↔ shipped code):** spot-checked 5 Scenarios against code — line 84 (`primary.txt` only) ↔ `scripts/backup/recipients/` (verified, only `primary.txt`); lines 232-241 (three-leg alerting) ↔ `workers/alert.sh` + Kuma push at `backup-daily.sh:231-235`; lines 108-128 (rclone runtime config) ↔ `runner/docker-entrypoint.sh`; lines 159-206 (chained verify) ↔ `backup-daily.sh` + `backup-preop.sh` chaining `verify-restore.sh`; lines 230 (camelCase keys) ↔ `backup-daily.sh:212-213` (verified). All consistent.

- **B2 (rclone shim): FIXED.** `scripts/backup/runner/docker-entrypoint.sh` (49 lines) is correct:
  - Sets all six required `RCLONE_CONFIG_R2_*` vars (TYPE=s3, PROVIDER=Cloudflare, ACCESS_KEY_ID, SECRET_ACCESS_KEY, ENDPOINT=https://<account>.r2.cloudflarestorage.com, ACL=private) at lines 33-38.
  - Smoke-check at lines 43-47 uses `rclone listremotes | grep -q '^r2:$'` — this is local config resolution only (no network call), so it succeeds offline as long as env vars are well-formed. Verified against rclone documented behavior (`RCLONE_CONFIG_<NAME>_<KEY>` synthesises a remote without `rclone.conf` ever existing on disk; `rclone listremotes` resolves env-defined remotes).
  - Defensive `if [ -z … ]` checks at lines 19-31 fail fast with operator-facing messages if any of the three required `BACKUP_R2_*` vars are absent — catches the empty-string case the smoke-check would otherwise miss for `ACCOUNT_ID`.
  - `Dockerfile.runner:40` installs the shim at `/usr/local/bin/docker-entrypoint.sh` AFTER `COPY runner/ ./` on line 35, so the source file exists at install-time. The COPY is scoped to the entire `runner/` directory, so `docker-entrypoint.sh` is included.
  - tini PID 1 preserved: `ENTRYPOINT ["/sbin/tini", "--", "/usr/local/bin/docker-entrypoint.sh"]` at line 57, with `CMD ["node", "server.js"]` at line 58 — tini stays PID 1, the shim is its direct child, and the `exec "$@"` at shim line 49 replaces the shim process with `node server.js`. Standard, correct chain.
  - No UID/permission issue: shim is sh-compatible (`#!/usr/bin/env sh`, no bashisms), installed with mode `0755` via `install -m 0755`, and runs as root inside the container.
  - No startup deadlock: shim has no network call, no sleep, no `wait` — it's a pure env-vars-then-exec script.
  - `docker exec myfinance-backup-runner …` still works because docker-exec spawns a fresh process that inherits the container's env (which has the `RCLONE_CONFIG_R2_*` vars set by the shim at startup and persisted in the container's process env via `export`). Verified by reading the shim's exports + Linux docker-exec semantics.

- **M1 (test-smoke cruft): FIXED.** `scripts/backup/test-smoke.sh`:
  - No line writes `recovery.txt` anymore (grep `recovery.txt` returns only the explanatory comment at line 113 — "v3 single-recipient design (Gate C cut B1) — no recovery.txt is generated.").
  - No line exports `MYFINANCE_BACKUP_HEALTHCHECKS_URL` (grep returns 0 matches in `test-smoke.sh`).
  - Header comment at line 1 says "v3 single-recipient" matching the brief.
  - Control flow is coherent: the smoke test still generates a fresh keypair (line 108), writes only `primary.txt` (line 116), runs `backup-daily.sh` with the smoke recipient set (lines 121-165), and asserts on the final JSON output (lines 184-191). No dangling references to removed vars or files.

- **Q1 (preflight evidence): ANSWERED.** `progress.md:13` records: "Preflight evidence: 2026-05-31 — `scripts/preflight.ps1` ran TWICE during the replant session (once at session start before any edits, once after the replant). Both runs: mvn compile OK, working tree warnings expected (replant artefacts), supabase-backup-policy artefacts complete, rclone skip (not installed locally — local dev does not need it). 0 fails on either run." This is the format the design Decision 5 v2 directive prescribes.

- **Documented-as-follow-up (M2, M3, M4 partial, m1-m6, Q2): YES.** `progress.md:14` documents the adversarial-review verdict, the count of findings, the pointer to the full pass-1 report at `notes/adversarial-review-replant.md`, and "M2-M4 + m1-m6 documented as follow-ups (operator-decision; not blocking)." Note that M4 has a PARTIAL resolution (camelCase mandate added to spec line 230 + the two specific keys `previousFailedAttempts` / `quarantinedArtefacts` aligned) but does not extend to the error-body keys (see N2 below).

---

## New findings introduced by the fixes

### N1 (Minor) — README + tasks.md drill JSON heredoc uses `paper_intact` (snake_case); spec line 305 mandates `paperIntact` (camelCase)

**Evidence:**
- `openspec/changes/supabase-backup-policy/specs/database-backups/spec.md:305` (v3 Recurring disaster-drill cadence Requirement): "writing a JSON blob to `r2://…/status/last-drill.json` containing `{\"timestamp\":\"<ISO-8601 UTC>\",\"paperIntact\":<bool>,\"reprinted\":<bool>,\"snapshotUsed\":\"<r2-path>\",\"operatorInitial\":\"<AT>\"}`".
- `openspec/changes/supabase-backup-policy/specs/database-backups/spec.md:230` (camelCase mandate): "all JSON keys in `status/*.json` files use camelCase (`previousFailedAttempts`, `quarantinedArtefacts`, `lastSuccess`, `lastVerify`, `lastPreop`, `lastDrill`, `paperIntact`)".
- `scripts/backup/README.md:236` (drill upload heredoc): `{"timestamp":"…","paper_intact":true,"reprinted":false,"snapshot_used":"daily/<filename>","operator_initial":"AT"}` — snake_case, four keys drift (`paper_intact`, `snapshot_used`, `operator_initial`, and the `reprinted` value-shape is `bool` here vs `bool` in spec — that one matches).
- `openspec/changes/supabase-backup-policy/tasks.md:210` (drill task heredoc): same snake_case heredoc as README — `{"timestamp":"…","paper_intact":true,"reprinted":false,"snapshot_used":"<the snapshot path>","operator_initial":"AT"}`.

**Why this is new:** the B1 replant added the explicit camelCase mandate at spec line 230 AND rewrote the drill Requirement at line 299-321 with camelCase keys. The README and tasks.md drill heredocs were NOT updated to match — they retain the v2 snake_case heredoc. The watchdog (`MyFinanceBackup-Watchdog.json`) only reads `lastDrill.timestamp`, so functionally nothing breaks at runtime, but a future operator following the README heredoc literally will write a drill file whose `paperIntact` / `snapshotUsed` / `operatorInitial` keys are missing from the perspective of any spec-conformant reader.

**Recommended fix (docs):** update `scripts/backup/README.md:236` and `openspec/changes/supabase-backup-policy/tasks.md:210` heredocs to camelCase (`paperIntact`, `snapshotUsed`, `operatorInitial`). Two-line edit in each file. This is the inverse of the original m5 finding which assumed the spec would adopt snake_case — since the replant chose camelCase, the docs need to match.

### N2 (Minor) — Spec is internally inconsistent on JSON key convention: camelCase mandated at line 230 but error-body keys at lines 64, 69, 215 use snake_case (`quarantined_to`, `local_sha256`, `r2_sha256`)

**Evidence:**
- Spec line 230: "all JSON keys in `status/*.json` files use camelCase".
- Spec line 64 (Scenario "Pre-op fails loudly when post-upload SHA-256 mismatches"): "response status is 500 with body `{\"error\":\"upload_corrupted\",\"local_sha256\":\"…\",\"r2_sha256\":\"…\",\"quarantined_to\":\"quarantine/…\"}`" — snake_case keys in a sidecar response body (not a `status/*.json` file, technically).
- Spec line 69 (Scenario "Pre-op fails loudly when restore-verify rejects"): "response body contains `{\"error\":\"verify_failed\",\"probe\":{...},\"quarantined_to\":\"quarantine/…\"}`".
- Spec line 215 (Scenario "Verify-failed pre-op is moved to quarantine"): "the response body contains `quarantined_to` pointing at the new location".
- `scripts/backup/workers/backup-preop.sh:118, 149` writes those snake_case keys exactly: `{\"error\":\"upload_corrupted\",\"local_sha256\":\"…\",\"r2_sha256\":\"…\",\"quarantined_to\":\"…\"}`. Code matches spec at the snake_case error-body points.
- Spec line 59, 162 use `verifyResult` (camelCase) — and `scripts/backup/workers/backup-preop.sh:162` matches it.

**Why this is new:** the M4 partial fix added a camelCase mandate scoped to `status/*.json` files, which is technically narrower than "all JSON the runner produces." A literal reading of the spec is consistent (mandate is `status/*.json`, error bodies are sidecar HTTP responses), but a casual reader will perceive the spec as contradictory and a future contributor adding a new error path may not know which convention to follow. The code-vs-spec match holds (no drift), but the spec-vs-spec consistency does not.

**Recommended fix (spec):** at spec line 230, broaden the mandate's scope and call out the exception: change "all JSON keys in `status/*.json` files use camelCase" to "all JSON keys in `status/*.json` files AND all sidecar `success` response bodies use camelCase. Error response bodies — `{\"error\":\"…\",\"local_sha256\":…,\"r2_sha256\":…,\"quarantined_to\":…}` — retain snake_case for backwards-compatibility with existing Scenarios at lines 64, 69, 215; new error fields SHOULD follow camelCase." OR rewrite all four error-body Scenarios + `backup-preop.sh:118,149` to use camelCase (`localSha256`, `r2Sha256`, `quarantinedTo`). Either path closes the inconsistency; the former is the smaller diff.

### Q-N1 (Question) — Should the partial M4 fix (camelCase for two new keys + a fresh mandate sentence) have triggered a broader audit of all JSON keys the runner produces?

The M4 fix added one sentence at spec line 230 + aligned the two `last-success.json` keys flagged in pass-1. It did NOT sweep:
- `paper_intact` / `snapshot_used` / `operator_initial` in the drill README + tasks heredocs (now caught as N1).
- `quarantined_to` / `local_sha256` / `r2_sha256` in error-body Scenarios + matching `backup-preop.sh` writes (now caught as N2).
- `error` / `probe` / `verifyResult` / `allPassed` (mixed conventions across the spec).

The operator-decision policy at the brief's outset was "fix the 2 Blockers + Major M1 + Question Q1 in the same session; other findings documented as follow-ups." That is a defensible scope discipline, and the pass-1 report and `progress.md:14` correctly document the deferral. The Question for the operator is whether N1 + N2 + Q-N1's bulleted sweep above belong inside the same "follow-up" envelope (pre-archive operator decision) or whether they should be batched into a small post-archive normalisation change. Either answer is reasonable; raising it to make the choice explicit.

---

## Counts (NEW findings only — does NOT recount first-pass findings)

- Blockers: 0
- Majors: 0
- Minors: 2
- Questions: 1

---

## What I looked at

- `openspec/changes/supabase-backup-policy/specs/database-backups/spec.md` (full, 458 lines, end-to-end re-read against the v3 brief; grepped for `healthchecks|four-channel|four channels|Traefik IP|recipients/recovery|MYFINANCE_BACKUP_HEALTHCHECKS|webhook.*pre-op|/webhook/myfinance|papers_intact` — every hit is in a correctly-framed "cut" / "deleted" context).
- `scripts/backup/runner/docker-entrypoint.sh` (full, 49 lines).
- `scripts/backup/Dockerfile.runner` (full, 58 lines — verified COPY order, install order, tini chain).
- `scripts/backup/test-smoke.sh` (full, 198 lines — confirmed M1 lines 116 + 137 of pass-1 are gone, replaced with a v3 comment).
- `scripts/backup/docker-compose.yml` (full, 55 lines — confirmed no rclone-conf bind-mount needed under shim approach).
- `scripts/backup/workers/backup-daily.sh` (sampled lines 200-260 — confirmed `previousFailedAttempts` / `quarantinedArtefacts` camelCase keys match spec line 230 mandate).
- `scripts/backup/workers/backup-preop.sh` (full, 163 lines — caught N2 error-body snake_case keys).
- `scripts/backup/workers/verify-restore.sh` (lines 1-80 — confirmed identity stdin pipe + UUID container name unchanged from pass-1; not re-litigating).
- `scripts/backup/runner/workers.js` (full, 115 lines — confirmed identity scrub from child env unchanged from pass-1).
- `scripts/backup/README.md` (lines 15-50 + 225-243 — confirmed N1 drill heredoc still snake_case; scope-note prose at lines 20-25 is correctly past-tense narrative, not a current-architecture claim).
- `openspec/changes/supabase-backup-policy/progress.md` (full, 34 lines — confirmed Q1 preflight evidence + adversarial-review follow-up documentation present at lines 13-14).
- `openspec/changes/supabase-backup-policy/tasks.md` (line 210 specifically — confirmed drill heredoc still snake_case, matches N1).
- Cross-grep for `paper_intact|paperIntact|operatorInitial|operator_initial|snapshot_used|snapshotUsed|reprinted` and `quarantined_to|quarantinedTo|local_sha256|localSha256|r2_sha256|r2Sha256|verifyResult|verify_result` across the worktree to confirm new findings are real and not narrative-only.
- First-pass report at `openspec/changes/supabase-backup-policy/notes/adversarial-review-replant.md` (full, 240 lines — used as the baseline of unfixed findings to verify each is documented as a follow-up).

What I deliberately did NOT re-examine (per brief: do NOT re-litigate original findings beyond confirming they are fixed):
- M2 IF-node wiring in `MyFinanceBackup-Daily.json` (deferred per operator decision).
- M3 errorWorkflow binding by name (deferred).
- m1 .gitignore content-scan claim (deferred).
- m2 mktemp in `/tmp` (deferred).
- m3 trap chain replacement (deferred).
- m4 dead test-fixture (deferred).
- m6 progress.md `last_updated` value (the field is now `2026-06-01T00:00:00Z` per progress line 16, still a day-precision stamp rather than commit-time, but the brief did not authorise a re-fix here).
- Q2 Phase 2 design doc Docker-socket question (out-of-Phase-1 scope per brief).
