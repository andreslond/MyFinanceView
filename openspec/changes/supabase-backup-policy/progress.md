# Live progress for openspec/changes/supabase-backup-policy/
# Schema: openspec/templates/progress-template.md
# Replanted on branch feat/supabase-backup-policy-replant on 2026-05-31
# v1 scope cuts applied on 2026-06-02 (R2 lifecycle 5→1, alerts 4→2 + Gmail→Resend,
# no Traefik allowlist). /opsx:apply pass-1 implementation landed 2026-06-02 with
# round-4 adversarial-review B1/B2/M2/M3 fixes applied in the same commit.

current_task: "8.1"
last_completed: "12.2 (spec edits up to round-5; §8.4b added)"
next_step: "Operator §1 prereqs (v1 contract, 2026-06-01 cuts applied) — install age on Windows PC, generate single primary identity, configure R2 token + ONE `daily/` 30d lifecycle policy + Resend API key on verified `datachefnow.com` domain (sender `alerts@datachefnow.com`) + ntfy topic + Supabase role password + runner shared secret. **No** Gmail App Password (Gmail → Resend), **no** Uptime Kuma Push Monitor (in-cluster dead-man-switch dropped — relies on operator's external uptime monitor on `n8n.datachefnow.com`). Then §6 VPS image build, §8 VPS deploy + n8n credential setup (HTTP Header Auth credential `myfinance-backup-resend-auth` carrying `Authorization: Bearer <resend-api-key>` + Generic credentials for AGE_IDENTITY + RUNNER_SECRET; n8n container env vars for NTFY_TOPIC + ALERT_FROM + ALERT_TO), §9 smoke tests, §10 activation, §11.5 memory + §12 archive. See `notes/operator-prereqs-checklist.md` for step-by-step."
decisions_pending_design_update:
  - "Preflight evidence: 2026-05-31 — `scripts/preflight.ps1` ran TWICE during the replant session (once at session start before any edits, once after the replant). Both runs: mvn compile OK, working tree warnings expected (replant artefacts), supabase-backup-policy artefacts complete, rclone skip (not installed locally — local dev does not need it). 0 fails on either run."
  - "Adversarial-review pass 1 (2026-06-01): subagent FAIL verdict with 2 Blockers, 4 Majors, 6 Minors, 2 Questions. Report at openspec/changes/supabase-backup-policy/notes/adversarial-review-replant.md. B1 (delta spec not replanted to v3) and B2 (no rclone runtime config) FIXED in this same session. M1 (test-smoke.sh v2 cruft) FIXED. Q1 (preflight evidence) ANSWERED. M2-M4 + m1-m6 + Q2 documented as follow-ups (operator-decision; not blocking)."
  - "Adversarial-review pass 2 (2026-06-01): subagent PASS WITH GAPS verdict with 0 Blockers, 0 Majors, 2 Minors, 1 Question. Report at openspec/changes/supabase-backup-policy/notes/adversarial-review-replant-pass2.md. N1 (snake_case heredoc drill in README + tasks.md) FIXED. N2 (spec-internal camelCase vs snake_case inconsistency in error bodies) FIXED via mandate-scope clarification. Q-N1 (whether to broaden camelCase sweep to verifyResult/allPassed/etc) DEFERRED to operator decision."
  - "v1 scope cuts applied (2026-06-02): three operator decisions of 2026-06-01 (R2 lifecycle 5→1, alerts 4→2 channels + Gmail→Resend, no Traefik IP allowlist) propagated to proposal.md, tasks.md, design.md, specs/database-backups/spec.md, .env.example, and notes/operator-prereqs-checklist.md. Adversarial-review v1-cuts-round1 (2026-06-02): subagent FAIL verdict with 5 Blockers + 6 Majors. All 5 Blockers + critical Majors fixed in same edit pass (notes/adversarial-review-v1-cuts.md)."
  - "Adversarial-review v1-cuts-round2 (2026-06-02): PASS WITH GAPS — 0 Blockers / 2 Majors / 3 Minors / 1 Question. N2 (README v3 content) + N3 (test-smoke env exports) fixed in pass-3 commit. N1 (workers code) deferred to /opsx:apply. Report at notes/adversarial-review-v1-cuts-round2.md."
  - "Adversarial-review v1-cuts-round3 (2026-06-02): PASS WITH GAPS — 0 Blockers / 0 Majors / 2 Minors / 1 Question. R3-1 (README §2.5.7 Watchdog mention) fixed inline. R3-2 (§2.5.4 v1 framing) tracked open. Report at notes/adversarial-review-v1-cuts-round3.md."
  - "/opsx:apply pass-1 (2026-06-02): implemented §4.2 alert.sh Resend rewrite, §4.3.12 Kuma push removal from backup-daily.sh, §7.6 DispatchAlert.json Resend rewrite, §7.7 JSON validate, deleted MyFinanceBackup-Watchdog.json (§7.4), wrote docs/development-guide.md §12 (§11.1+§11.2), cross-refs from SPEC.md §12 (§11.3) and docs/data-model.md §3 (§11.4). Flipped 20 task checkboxes accordingly."
  - "Adversarial-review v1-cuts-round5 (2026-06-02): PASS WITH GAPS — 0 Blockers / 2 Majors / 3 Minors / 1 Question. R5-M1 (pg_isready short-circuit bypassing ERR trap) + R5-M2 (backup-preop.sh missing ERR trap) FIXED. R5-N1 (progress.md staleness) FIXED via this entry + last_updated bump. R5-N2 (LF→space comment) FIXED. R5-N3 (§8.4b credential rebind step) FIXED via new tasks.md §8.4b. R5-Q1 (set -E future-proofing) addressed via inline comment in on_error. Report at notes/adversarial-review-v1-cuts-round5.md."
  - "Adversarial-review v1-cuts-round4 (2026-06-02): FAIL — 2 Blockers / 3 Majors / 4 Minors / 0 Questions. B1 (alert.sh subshell-scoping bug breaking at-least-one-succeeds contract), B2 (DispatchAlert.json using $env.* instead of n8n credential model), M2 (§4.3.14 worker-side any-failure-dispatches contract not implemented), M3 (backup-daily.sh dead-code re-download block), M4 (false [x] on §4.2/§4.3.14/§7.6). ALL 5 Blockers + Majors fixed in same commit: alert.sh now uses wait-PID pattern + ntfy topic sanity regex + CRLF strip on title; DispatchAlert.json uses HTTP Header Auth credential for Resend Bearer + $env.* for non-secret config (ntfy topic + from + to) with §8.4 rewritten to clarify the model; backup-daily.sh dead-code block deleted + ERR trap installed for §4.3.14 contract; tasks.md §8.4 expanded. Report at notes/adversarial-review-v1-cuts-round4.md."
blockers: []
last_updated: "2026-06-02T06:00:00Z"

# Notes for resuming this change:
# - 46/81 tasks complete after /opsx:apply pass-1 (was 31/81 before; jumped 15 tasks).
# - All runner Jest tests pass locally (auth.test.js + mutex.test.js, 11 tests). Not re-run after /opsx:apply since the runner JS was not touched; only the bash workers + n8n JSON + docs changed.
# - npm install ran; package-lock.json is committed.
# - n8n PreOp workflow rewritten manual-trigger-only (v3 Gate C cut M3: no public webhook).
# - Single-recipient age encryption applied (v3 Gate C cut B1: no recovery key).
# - healthchecks.io off-VPS pinger removed (v3 Gate C cut M1: deferred).
# - Delta spec replanted to v3 (Blocker B1 from earlier adversarial-review round fixed 2026-06-01).
# - rclone entrypoint shim added at runner/docker-entrypoint.sh (Blocker B2 from earlier round fixed 2026-06-01).
# - test-smoke.sh stripped of recovery.txt + HEALTHCHECKS_URL references + Gmail/Kuma env exports (now Resend trio placeholders).
# - README + tasks.md drill heredocs aligned to camelCase per spec mandate.
# - Spec camelCase mandate scope clarified to exclude legacy error-body snake_case keys.
# - Daily.json simplified — dropped IF Check HTTP Status + neverError:true so errorWorkflow path actually fires on non-2xx.
# - tasks.md §8.4a added — operator step to re-link errorWorkflow dropdown after import in each of Daily/PreOp (Watchdog dropped in v1 cut).
# - v1 cuts applied across 7 spec/proposal/design/spec.md/tasks.md/env/checklist files (commit 97355e7 + 1981f9e) and now /opsx:apply pass-1 has landed the code changes (alert.sh, backup-daily.sh, DispatchAlert.json, README, dev-guide §12, SPEC.md §12, data-model §3 cross-refs).
# - Round-4 fixes embedded in /opsx:apply pass-1 commit (this commit).
# - Outstanding deferred follow-ups (operator-decision, not blocking archive): m1 (.gitignore content-scan), m2 (mktemp /tmp), m3 (trap chain), m4 (dead test-fixture), m6 (last_updated precision), Q2 (Phase 2 socket framing), Q-N1 (broader camelCase sweep), R3-2 (§2.5.4 README v1 framing), R3-3 (smoke test contract), R4-N1 (now fixed — split worker vs n8n alerting in dev-guide §12.4), R4-m1 (ntfy topic regex — fixed), R4-m2 (CRLF strip — fixed).
# - Remaining 35- tasks are: §1 operator prereqs (manual, ~30 min on the Windows PC + cloud dashboards),
#   §6.2/6.2b/6.5 (VPS build + Alpine pin freshness check),
#   §8 (VPS deploy + n8n credential setup per the updated §8.4), §9 smoke tests, §10 activation, §11.5 memory, §12.3-12.5 archive.
# - Implementation snapshot branch feat/supabase-backup-policy-impl can be deleted after this lands.
# - Phase 2 design doc at plans/2026-05-31-supabase-backup-multi-tenant-design.md (multi-tenant + n8n state backup) — opened only AFTER Phase 1 archives.
