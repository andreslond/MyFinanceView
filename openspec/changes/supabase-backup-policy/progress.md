# Live progress for openspec/changes/supabase-backup-policy/
# Schema: openspec/templates/progress-template.md
# Replanted on branch feat/supabase-backup-policy-replant on 2026-05-31
# after squash-replanting the implementation that lived on
# feat/supabase-backup-policy-impl (pre-monorepo base) onto current main +
# v3 spec cuts (cherry-picked from supabase-cuts worktree).
# Adversarial-review feedback applied 2026-06-01.

current_task: "1.1"
last_completed: "7.7"
next_step: "Operator §1 prereqs (v1 contract, 2026-06-01 cuts applied) — install age on Windows PC, generate single primary identity, configure R2 token + ONE `daily/` 30d lifecycle policy + Resend API key on verified `datachefnow.com` domain (sender `alerts@datachefnow.com`) + ntfy topic + Supabase role password + runner shared secret. **No** Gmail App Password (Gmail → Resend), **no** Uptime Kuma Push Monitor (in-cluster dead-man-switch dropped — relies on operator's external uptime monitor on `n8n.datachefnow.com`). See `notes/operator-prereqs-checklist.md` for step-by-step."
decisions_pending_design_update:
  - "Preflight evidence: 2026-05-31 — `scripts/preflight.ps1` ran TWICE during the replant session (once at session start before any edits, once after the replant). Both runs: mvn compile OK, working tree warnings expected (replant artefacts), supabase-backup-policy artefacts complete, rclone skip (not installed locally — local dev does not need it). 0 fails on either run."
  - "Adversarial-review pass 1 (2026-06-01): subagent FAIL verdict with 2 Blockers, 4 Majors, 6 Minors, 2 Questions. Report at openspec/changes/supabase-backup-policy/notes/adversarial-review-replant.md. B1 (delta spec not replanted to v3) and B2 (no rclone runtime config) FIXED in this same session. M1 (test-smoke.sh v2 cruft) FIXED. Q1 (preflight evidence) ANSWERED. M2-M4 + m1-m6 + Q2 documented as follow-ups (operator-decision; not blocking)."
  - "Adversarial-review pass 2 (2026-06-01): subagent PASS WITH GAPS verdict with 0 Blockers, 0 Majors, 2 Minors, 1 Question. Report at openspec/changes/supabase-backup-policy/notes/adversarial-review-replant-pass2.md. N1 (snake_case heredoc drill in README + tasks.md) FIXED. N2 (spec-internal camelCase vs snake_case inconsistency in error bodies) FIXED via mandate-scope clarification. Q-N1 (whether to broaden camelCase sweep to verifyResult/allPassed/etc) DEFERRED to operator decision."
  - "v1 scope cuts applied (2026-06-02): three operator decisions of 2026-06-01 (R2 lifecycle 5→1, alerts 4→2 channels + Gmail→Resend, no Traefik IP allowlist) propagated to proposal.md, tasks.md, design.md, specs/database-backups/spec.md, .env.example, and notes/operator-prereqs-checklist.md. Adversarial-review pass 3 (2026-06-02): subagent FAIL verdict with 5 Blockers + 6 Majors. All 5 Blockers + critical Majors fixed in same edit pass (notes/adversarial-review-v1-cuts.md). Watchdog enumeration removed from progress.md line 32."
blockers: []
last_updated: "2026-06-02T00:00:00Z"

# Notes for resuming this change:
# - 82+ of 126 checkboxes ticked in tasks.md (mostly §2-§7: scaffold + runner + workers + n8n).
# - All runner Jest tests pass locally (auth.test.js + mutex.test.js, 11 tests).
# - npm install ran; package-lock.json is committed.
# - n8n PreOp workflow rewritten manual-trigger-only (v3 Gate C cut M3: no public webhook).
# - Single-recipient age encryption applied (v3 Gate C cut B1: no recovery key).
# - healthchecks.io off-VPS pinger removed (v3 Gate C cut M1: deferred).
# - Delta spec replanted to v3 (Blocker B1 from adversarial-review fixed 2026-06-01).
# - rclone entrypoint shim added at runner/docker-entrypoint.sh (Blocker B2 fixed 2026-06-01).
# - test-smoke.sh stripped of recovery.txt + HEALTHCHECKS_URL references (Major M1 fixed).
# - README + tasks.md drill heredocs aligned to camelCase per spec mandate (Minor N1 fixed 2026-06-01).
# - Spec camelCase mandate scope clarified to exclude legacy error-body snake_case keys (Minor N2 fixed 2026-06-01).
# - Daily.json simplified — dropped IF Check HTTP Status + neverError:true so errorWorkflow path actually fires on non-2xx (Major M2 fixed 2026-06-01).
# - tasks.md 8.4a added — operator step to re-link errorWorkflow dropdown after import in each of Daily/PreOp (Watchdog dropped in v1 cut 2026-06-02; Major M3 from earlier round documented 2026-06-01).
# - Final adversarial-review verdict (pass 3, 2026-06-01): PASS — 0 Blockers, 0 Majors, 0 Minors, 0 Questions. Report at notes/adversarial-review-replant-pass3.md.
# - Outstanding deferred follow-ups (operator-decision, not blocking archive): m1 (.gitignore content-scan), m2 (mktemp /tmp), m3 (trap chain), m4 (dead test-fixture), m6 (last_updated precision), Q2 (Phase 2 socket framing), Q-N1 (broader camelCase sweep). All documented in pass-1 + pass-2 reports.
# - Remaining 44- tasks are: §1 operator prereqs (manual, ~30 min on the Windows PC + cloud dashboards),
#   §6.2/6.2b/6.5 (VPS build + Alpine pin freshness check), §7.3 (DROPPED placeholder),
#   §8-§12 (VPS deploy, smoke tests, activation, docs+memory updates, validate + archive).
# - Implementation snapshot branch feat/supabase-backup-policy-impl can be deleted after this lands.
# - Phase 2 design doc at plans/2026-05-31-supabase-backup-multi-tenant-design.md (multi-tenant + n8n state backup) — opened only AFTER Phase 1 archives.
# - Outstanding Majors from adversarial-review (M2-M4) and Minors (m1-m6) are documented in notes/adversarial-review-replant.md; operator decides which to address pre-archive vs as Phase 2 / follow-ups.
