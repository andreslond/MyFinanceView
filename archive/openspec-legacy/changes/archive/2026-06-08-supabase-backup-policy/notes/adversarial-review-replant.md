# Adversarial review — supabase-backup-policy replant (2026-06-01)

Reviewer: adversarial-reviewer subagent, Opus 4.7 (1M context), invoked from main
worktree at `D:\dev\workspace\MyFinanceView\.claude\worktrees\supabase-replant`.

Diff base: HEAD on `main` (commit `f522a6b` — "feat(spec): supabase-backup-policy
v3 — retroactive Gate B+C cuts"). 31 files in worktree (24 staged, 7 modified,
4 untracked listed in `git status`). 2756 insertions / 141 deletions per
`git diff --stat`.

Threat model used (auto-rejecting OUT-of-scope findings): per
`openspec/changes/supabase-backup-policy/proposal.md` "## Threat model" section
— local-forensic, off-VPS pinger, public webhook, network-printer, and
dual-paper-custody adversaries are explicitly out of scope.

---

## Verdict
**FAIL**

The implementation under `scripts/backup/` is internally coherent with v3 and the
Gate C cuts have been correctly applied to the worker code, Dockerfile,
compose, n8n PreOp workflow, `.env.example`, and README. However, the **delta
spec under `openspec/changes/supabase-backup-policy/specs/database-backups/spec.md`
was NOT replanted to match v3.** It still mandates dual-recipient encryption,
healthchecks.io ping, public pre-op webhook, Traefik IP allowlist middleware,
and "four-channel alerting" — none of which the code implements. Archiving this
change as-is (tasks 12.1 + 12.5 + /openspec-sync-specs) would publish a canonical
capability spec that contradicts the shipped behavior, and would fail an honest
re-read at any future date. This is a single Blocker but a hard one because
it's exactly the artefact the workflow promotes into `openspec/specs/database-backups/`.

A second Blocker is operational: the runner container has no working rclone
remote at runtime (no config bind-mount, no `RCLONE_CONFIG_*` env vars
populated, no entrypoint that materializes a config from `BACKUP_R2_*`). Every
`/status` call and every worker rclone op will fail. This is invisible until
deploy-time but the implementation is staged as "done".

Two stale-scope leaks in `test-smoke.sh` round it out as Majors.

---

## Counts
- Blockers: 2
- Majors: 4
- Minors: 6
- Questions: 2

---

## Blockers

### B1 — Delta spec NOT replanted to v3; still mandates dual-recipient, healthchecks.io, public webhook + Traefik

**Evidence:** `openspec/changes/supabase-backup-policy/specs/database-backups/spec.md`

- Line 5 — Requirement text: "encrypted at rest with `age` against TWO project-owned recipients (primary + recovery) in a single encryption pass". The implementation (`scripts/backup/workers/backup-daily.sh:99-102`, `backup-preop.sh:88-91`) uses single-recipient: `age -r "$(cat .../primary.txt)"`.
- Line 32-35 — Scenario "Snapshot is encrypted against both primary and recovery recipients" requires `age -d --identity recovery-paper.txt < snapshot.tar.age` to succeed. No `recovery.txt` is shipped (`scripts/backup/recipients/` contains only `primary.txt`).
- Lines 86-123 — Entire "Encryption with two project-owned age recipients, dual-paper custody" Requirement (six scenarios) requires `recipients/recovery.txt`, geographic separation, recovery identity custody.
- Line 44 — Pre-op Requirement: "the procedure SHALL be invokable via two paths: (a) an HTTP POST to the n8n webhook `/webhook/myfinance-backup-preop` … subject to the Traefik IP allowlist". Implementation has NO webhook (PreOp workflow is manual-trigger only — `scripts/backup/n8n/MyFinanceBackup-PreOp.json`).
- Lines 46-69 — Six scenarios assert "Pre-op webhook rejects …", "Traefik returns HTTP 403", "Traefik IP allowlist". No webhook, no Traefik config exists (`traefik/dynamic/myfinance-preop.yml` does not exist; `Glob '**/traefik/**'` returns no matches).
- Lines 79-84 — Scenario asserts "all four alert channels (ntfy + Gmail + Kuma-non-push-as-implicit + healthchecks.io-non-ping-as-implicit) fire". `alert.sh` fires only ntfy + Gmail. No healthchecks ping anywhere in `scripts/backup/`.
- Lines 251-294 — Entire "Four-channel alerting" Requirement (six scenarios) requires `MYFINANCE_BACKUP_HEALTHCHECKS_URL` env, GET ping on success, dead-man detection. Implementation: `.env.example:87-89` explicitly DELETES this var with a "Gate C cut M1" comment; `backup-daily.sh:238-241` is a comment block "deferred under v3"; no curl to `hc-ping.com` anywhere.
- Lines 367-384 — Entire "Pre-op webhook source-IP allowlist (M3 fix)" Requirement (three scenarios) requires `traefik/dynamic/myfinance-preop.yml`, IP allowlist middleware, two-layer model. No such file or config exists.
- Lines 393-399 — Repository-layout Requirement claims `recipients/primary.txt` AND `recipients/recovery.txt`, "age (two recipients)", "healthchecks.io ping (M1 fix)".
- Line 424-427 — Scenario "Built runner image carries BOTH age recipients" asserts `test -f .../recovery.txt` exits 0. Will fail.
- Lines 471-521 — "Uptime Kuma" Requirement is partially OK; "healthchecks.io off-VPS dead-man-switch (M1 fix)" Requirement (four scenarios) is entirely contradicted.

**Why this is a Blocker:** task 12.1 (`openspec validate --strict`) checks structural validity of the delta spec; task 12.5 archives it and `/openspec-sync-specs` merges the delta into the canonical capability spec under `openspec/specs/database-backups/`. Archiving the current delta would publish a canonical spec where the documented requirements diverge from the shipped code — exactly the failure mode `/adversarial-review` exists to prevent. The proposal.md, design.md, tasks.md, and README.md have all been correctly updated for v3; only the delta spec was missed during the replant.

**Recommended fix (spec):** rewrite `openspec/changes/supabase-backup-policy/specs/database-backups/spec.md` to match v3:
- Replace "Encryption with two project-owned age recipients, dual-paper custody" Requirement with a "Single-recipient encryption with documented unrecoverable-loss path" Requirement matching `scripts/backup/recipients/primary.txt` (and ADD a delta note acknowledging this is the v3 retroactive cut of the v2 dual-paper Requirement).
- Strip the "Pre-op webhook source-IP allowlist (M3 fix)" Requirement entirely; rewrite the "Manual on-demand pre-operation snapshot" Requirement to describe the manual-trigger-only path (Set Reason → Validate Reason → POST /run/preop, per the actual `MyFinanceBackup-PreOp.json`).
- Strip the "healthchecks.io off-VPS dead-man-switch (M1 fix)" Requirement; rewrite "Four-channel alerting" as "Three-leg alerting (ntfy + Gmail + Kuma in-cluster dead-man)" and drop every "four channels" / "VPS-wide outage" / `MYFINANCE_BACKUP_HEALTHCHECKS_URL` reference.
- Update the Repository-layout Requirement's `recipients/recovery.txt` line and the Scenario at line 424-427 that asserts `test -f .../recovery.txt`.
- Update the "Acknowledged coverage gaps (finding #7)" Requirement's item 6 to drop the `traefik/dynamic/myfinance-preop.yml` reference (it's NOT in git because the route doesn't exist; the line currently says "The Traefik config IS in git" which is false).
- Add an Open Question / decision note that re-introducing any of these can be a bounded follow-up if the v3 trade-off proves wrong in practice, with cross-reference to `design.md` Decision 2 / `proposal.md` Threat model.

### B2 — Runner container has no rclone remote at runtime; every R2 call will fail

**Evidence:**
- `scripts/backup/Dockerfile.runner` installs `rclone` via `apk add` but does NOT create any rclone config (no `mkdir -p /root/.config/rclone/`, no `COPY rclone.conf`, no entrypoint script that materializes one).
- `scripts/backup/docker-compose.yml` mounts only `/var/lib/myfinance-backup`, `/var/run/docker.sock`, and the verify tmpfs. It does NOT bind-mount `~/.config/rclone/rclone.conf` from the host, nor `/etc/rclone.conf`.
- `.env.example` defines `BACKUP_R2_ACCOUNT_ID`, `BACKUP_R2_ACCESS_KEY_ID`, `BACKUP_R2_SECRET_ACCESS_KEY`, `BACKUP_R2_BUCKET` — but nothing in the runner converts these into rclone remote config. rclone supports `RCLONE_CONFIG_<NAME>_<KEY>` env-var-based remote definition (e.g. `RCLONE_CONFIG_R2_TYPE=s3`, `RCLONE_CONFIG_R2_PROVIDER=Cloudflare`, etc.) but those vars are NOT defined or composed anywhere.
- `scripts/backup/runner/server.js:53-74` `fetchR2Json` shells out to `execFile('rclone', ['cat', 'r2:${R2_BUCKET}/${key}'])`. With no rclone remote `r2:` defined inside the container, every call returns an error like `didn't find section in config file`. `readStatus` calls this four times per `/status` request — every Watchdog tick will get 503 `{"error":"r2_unreachable"}`. Operator will see "STALE" alerts immediately.
- Every shell worker also shells `rclone copy`, `rclone rcat`, `rclone lsf`, `rclone moveto`, `rclone check` against `r2:` — none will work.
- Task 8.1 documents: "Configure the `rclone` remote in `~/.config/rclone/rclone.conf` (or `/etc/rclone.conf` for system-wide use) with `provider = Cloudflare` under `[r2]`". This file lives on the host's filesystem; the runner container cannot see it without a bind mount.

**Why this is a Blocker:** every backup pipeline call, the chained verify, the watchdog, and the status JSON upsert depend on rclone reaching R2. The implementation cannot run a single successful backup as currently composed. This is invisible at code review (no syntax error, all tests pass) but lethal at deploy time.

**Recommended fix (code):** add one of —
1. Bind-mount on host (preferred, matches task 8.1): in `scripts/backup/docker-compose.yml` add `- ~/.config/rclone/rclone.conf:/root/.config/rclone/rclone.conf:ro` (or `/etc/rclone.conf:/etc/rclone.conf:ro`). Document in README §2.5.3 that the file must exist on the VPS before `docker compose up`.
2. Env-var-based config: in `Dockerfile.runner` set fixed values `ENV RCLONE_CONFIG_R2_TYPE=s3 RCLONE_CONFIG_R2_PROVIDER=Cloudflare` and let runtime `BACKUP_R2_*` env vars satisfy `RCLONE_CONFIG_R2_ACCESS_KEY_ID` etc. via an entrypoint shim that maps `BACKUP_R2_*` → `RCLONE_CONFIG_R2_*`. This is more portable (no host filesystem coupling) but requires an entrypoint script (3-5 LOC of bash) and an `RCLONE_CONFIG_R2_ENDPOINT` constructed from the account ID.
Add a smoke test at task 6.5 or 8.5: `docker exec myfinance-backup-runner rclone lsd r2:` returns 0 within 5 s — fail the deploy gate if not. Update README §2.5.3 with the chosen path.

---

## Majors

### M1 — `test-smoke.sh` still writes `recovery.txt` and exports `MYFINANCE_BACKUP_HEALTHCHECKS_URL` (stale v2 references)

**Evidence:**
- `scripts/backup/test-smoke.sh:116` — `echo "${SMOKE_RECIPIENT}" > "${SMOKE_RECIPIENTS_DIR}/recovery.txt"` writes a `recovery.txt` next to `primary.txt`. The workers no longer read `recovery.txt`, so this file is harmless dead code, but it documents v2 behavior in a script that runs as the integration test. A future maintainer will reasonably believe dual-recipient is still in scope.
- `scripts/backup/test-smoke.sh:137` — `export MYFINANCE_BACKUP_HEALTHCHECKS_URL=""` exports a v2 env var that no production worker references. Same maintainer-confusion concern.
- task 4.7 in tasks.md is ticked. The smoke test was NOT updated during the v3 surgery.

**Why this matters now:** task 4.7 is the project's only integration test for the bash workers (per `base-standards.md §5` no-mock rule). It is referenced from `scripts/backup/README.md §2.5.6` and is the gate for any future `scripts/backup/*.sh` edit. Leaving v2 cruft in the gate means the gate itself drifts from the spec.

**Recommended fix (code):** remove lines 116 and 137 of `test-smoke.sh`. Add a comment at the top of the file: "v3 — single-recipient encryption, no healthchecks.io".

### M2 — Daily workflow `Check HTTP Status` IF node has no outgoing connections; non-2xx silently swallowed

**Evidence:**
- `scripts/backup/n8n/MyFinanceBackup-Daily.json` defines a `Check HTTP Status` IF node (lines 52-80) wired downstream of `POST /run/daily`. The `connections` map (lines 82-105) has entries for `Schedule Trigger → POST /run/daily → Check HTTP Status` but NO outgoing connection from `Check HTTP Status`. Combined with the HTTP node's `"neverError": true` (line 46), a sidecar 500 response does NOT bubble up as a workflow error.
- The workflow does declare `"errorWorkflow": "MyFinanceBackup-ErrorHandler"` (line 109), but that only fires on an unhandled node exception — `neverError: true` on the HTTP node suppresses the exception. So a sidecar-returned `500 {"error":"worker_failed"}` ends up as a green workflow run that didn't go anywhere after the IF.
- The bash worker's own `dispatch_alert` on the failure path (`backup-daily.sh:185-186`) DOES fire, so the operator IS alerted via ntfy + Gmail. The Kuma dead-man-switch ALSO catches it (no success ping). So the alerting still works through other legs — but the n8n execution log will show "Successful" for a run that quarantined the artefact and failed verify. This violates the design intent that ErrorHandler is the canonical workflow-level error path (per tasks.md 7.5).

**Why this matters:** the watchdog's "GET /status → stale" path is the secondary signal, but the design's primary signal for "the daily run itself failed" is the n8n ErrorHandler. With this wiring, the ErrorHandler never fires for sidecar-side failures — only for n8n-side failures (network unreachable, schedule misconfig). The operator's mental model of "n8n green = all good" breaks silently.

**Recommended fix (code):** in `MyFinanceBackup-Daily.json`, add two outgoing connections from `Check HTTP Status`: the `false` branch (`main[1]`) should connect to a Stop+Throw node (or an Execute Workflow node calling `MyFinanceBackup-DispatchAlert` with a structured title/body derived from `$json.body.error` + `$json.statusCode`). Alternatively, drop the IF node entirely and remove `neverError: true` from the HTTP node — then a non-2xx response throws and the existing `errorWorkflow` binding does the job. Choose one; current wiring is the worst of both worlds.

### M3 — n8n `errorWorkflow` is bound by workflow NAME, not ID — bindings will not resolve after `n8n import:workflow`

**Evidence:**
- `MyFinanceBackup-Daily.json:109`, `MyFinanceBackup-PreOp.json:125`, `MyFinanceBackup-Watchdog.json:170` all set `"errorWorkflow": "MyFinanceBackup-ErrorHandler"` (the workflow NAME string).
- n8n's `errorWorkflow` setting takes a numeric workflow ID (or the string `"name:<workflow name>"` in newer versions). A bare name string is silently ignored on import in most n8n versions; the workflow imports successfully but the error binding is empty. The operator must manually re-link in the n8n UI after import (tasks.md 8.4 doesn't mention this).

**Why this matters:** if the operator imports and activates without manually fixing the binding, n8n-side failures (e.g. credential expiry, HTTP timeout) produce no ErrorHandler alert. The Kuma dead-man-switch still catches workflow non-execution, but ErrorHandler-only paths (e.g. PreOp validation throw) go silent.

**Recommended fix (docs + n8n):** either (a) update tasks.md 8.4 to add a sub-step "after import, open each workflow's Settings → Error Workflow dropdown and select `MyFinanceBackup-ErrorHandler` — n8n binds by ID at this point, the import string is illustrative only", OR (b) use n8n's `name:<…>` form in the JSON if the target n8n version supports it (worth verifying the operator's n8n version; latest n8n cloud accepts this).

### M4 — Spec-vs-code key-name drift in `last-success.json`: spec says `previous_failed_attempts` (snake_case), code writes `previousFailedAttempts` (camelCase); same for `quarantinedArtefacts`

**Evidence:**
- `openspec/changes/supabase-backup-policy/specs/database-backups/spec.md:249` — Scenario "Same-day quarantine-then-success is visible in status" requires keys `previous_failed_attempts: <int>` and `quarantined_artefacts: [...]` (snake_case).
- `scripts/backup/workers/backup-daily.sh:212-213` — writes `"previousFailedAttempts": ${PREV_FAILED_ATTEMPTS}` and `"quarantinedArtefacts": ${QUARANTINED_ARTEFACTS}` (camelCase).
- The watchdog code (`MyFinanceBackup-Watchdog.json:50` jsCode) reads `status.lastSuccess.timestamp` only, so it doesn't care — but any future operator query against the documented schema (e.g. `jq .previous_failed_attempts last-success.json`) returns null.

**Recommended fix:** either rename the keys in `backup-daily.sh` to snake_case to match the spec, OR amend the spec scenario to camelCase to match code. Pick one and apply consistently across `last-success.json`, `last-verify.json`, `last-preop.json`, `last-drill.json` (README §2.5.7's `paper_intact` JSON also conflicts with the spec's `papers_intact.{primary,recovery}` — same class of drift).

---

## Minors

### m1 — `.gitignore` "AGE-SECRET-KEY-" content-scan rule from task 2.3 is not implementable in `.gitignore` alone but the task is ticked

**Evidence:** `.gitignore` (lines 32-38) contains filename-based exclusions (`age-identity*`, `**/age-identity*`, `*.identity`) but no content-based rule. The task description ("a sanity rule rejecting any file whose contents start with `AGE-SECRET-KEY-`") is impossible to satisfy in `.gitignore` syntax — `.gitignore` is filename-only. The task is ticked.

**Recommended fix:** either (a) add a pre-commit hook script that grep-rejects `AGE-SECRET-KEY-` in staged files (commit it under `scripts/git-hooks/`), then document install in README, and re-frame the task; OR (b) downgrade task 2.3's wording to "filename rules only" and uncheck the content-scan claim.

### m2 — `mktemp` in `verify-restore.sh:207` uses default `/tmp` (not the tmpfs)

**Evidence:** `scripts/backup/workers/verify-restore.sh:207` `VERIFY_STATUS_FILE="$(mktemp)"` — creates the temp file in `/tmp/` which is regular Alpine disk inside the container, not the `/var/lib/myfinance-verify` tmpfs. The file contains the verify status JSON (not the identity) so it's not a secrets leak, but the EXIT trap on line 208 rebinds `cleanup` instead of appending — readers will assume tmpfs guarantees from the surrounding code apply.

**Recommended fix:** `VERIFY_STATUS_FILE="$(mktemp -p "${VERIFY_DIR}")"` to keep the temp file on the tmpfs alongside the identity. Cosmetic, but makes the "no plaintext on persistent disk" invariant easier to verify.

### m3 — Trap chain replaced rather than extended at `verify-restore.sh:208`

**Evidence:** `scripts/backup/workers/verify-restore.sh:62` installs `trap cleanup EXIT INT TERM`. Line 208 then replaces it: `trap 'rm -f "$VERIFY_STATUS_FILE"; cleanup' EXIT INT TERM`. The replacement preserves `cleanup` so the container is still stopped and identity wiped — but readers must verify by hand. A bash builtin idiom would extend (`trap "$(trap -p EXIT | sed -e 's/^trap -- ['"'"']//' -e 's/['"'"'] EXIT$//'); rm -f \"$VERIFY_STATUS_FILE\"" EXIT`) but that's gnarly. Simpler fix: define `cleanup` to scrub both files conditionally.

**Recommended fix:** consolidate into one cleanup function that handles both files and the container; install the trap once at the top.

### m4 — `committed test-fixtures/recipients/test-recipient.txt` is dead code

**Evidence:** `scripts/backup/test-fixtures/recipients/test-recipient.txt` (committed) is a placeholder `age1testrecipientplaceholderxxx…`. `test-smoke.sh:108-115` GENERATES a fresh keypair at runtime and writes `primary.txt` + `recovery.txt` (see M1) into a temp dir. The committed file is never read by any worker or test.

**Recommended fix:** either delete `test-fixtures/recipients/` and the corresponding `.gitignore` whitelist concern; OR change `test-smoke.sh` to actually use the committed fixture instead of generating a new one (then the smoke test exercises the exact recipient bytes that the runner image carries). The "generate new keys per run" approach also means the smoke test never exercises the actual recipient-format-parse path the runner uses on Cloudflare — minor but real.

### m5 — README §2.5.7 drill JSON schema (`paper_intact: bool`) does NOT match spec schema (`papers_intact.{primary,recovery}: bool`)

**Evidence:**
- `scripts/backup/README.md:236` — heredoc writes `{"timestamp":"…","paper_intact":true,"reprinted":false,"snapshot_used":"…","operator_initial":"AT"}`.
- `openspec/changes/supabase-backup-policy/specs/database-backups/spec.md:328` — Requirement mandates `{"timestamp":"<ISO-8601 UTC>","papers_intact":{"primary":<bool>,"recovery":<bool>},"reprinted":["primary"|"recovery"|null],...}`.
- Watchdog only reads `lastDrill.timestamp`, so functionally OK, but the contract is contradictory.

**Recommended fix:** update README §2.5.7 to write the v3-simplified single-paper schema (`{"timestamp":"…","paper_intact":true,…}`) AND update the spec Requirement at line 328-333 to match (single primary paper, no recovery). This is part of the B1 spec rewrite.

### m6 — `progress.md` `last_updated` is a fake date (`2026-05-31T00:00:00Z`); diverges from actual replant commit date

**Evidence:** `openspec/changes/supabase-backup-policy/progress.md:13` — `last_updated: "2026-05-31T00:00:00Z"`. Today's date is 2026-06-01, and the latest replant commit on the branch is `f522a6b` whose timestamp the operator can verify with `git log -1 --format=%cI f522a6b`. Per the harness-progress-tracking project rule (`openspec/changes/harness-progress-tracking/design.md` Decision 2 + Open Question 4), `last_updated` SHOULD be the ISO timestamp of the most recent task closure. Stale or fictional dates silently mislead the next session's "resuming from" summary.

**Recommended fix:** the backend-developer subagent's `progress.md` rewrite step should be the source of `last_updated` — set it to the actual commit time of the latest tick. For this replant, bump to the actual replant time (e.g. `2026-05-31T23:06:00Z` or current time at next save).

---

## Questions

### Q1 — Preflight evidence missing from the replant transcript

The harness rule (`CLAUDE.md` "Preflight before non-trivial work") expects `scripts/preflight.ps1` to be invoked as one of the first actions in any `/opsx:apply` session, with the output reported to the operator. The replant was a `/opsx:apply`-class operation (squash-replant + cherry-pick + repo-tree edits). The review brief does not include a preflight transcript and `progress.md` does not record one in `decisions_pending_design_update`. **Was preflight run?** If not, document the skip rationale in `progress.md > decisions_pending_design_update` per the design Decision 5 v2 directive.

**Recommended fix (process):** before commit, append a one-line preflight artefact to `progress.md` or run preflight now and paste the output for the record.

### Q2 — Phase 2 multi-tenant design doc `plans/2026-05-31-supabase-backup-multi-tenant-design.md` references "Phase 1" assumptions that may not all hold

The Phase 2 design doc is correctly marked "brainstorm output, awaiting Phase 2 OpenSpec proposal" and is OUT of Phase 1 scope per the review brief. It does, however, claim Path A is "CHOSEN" partly because "the only path that **isolates the Docker socket to a single-purpose container**". Phase 1 mounts the Docker socket into the sidecar runner via `docker-compose.yml:41` — that's the same socket exposure Path A claims to avoid in Phase 2. **Confirm with operator:** does the Phase 1 socket-mount-in-sidecar match the Phase 2 design intent, or is Phase 2 planning to refactor away from this? This is a non-blocking question for Phase 1 but worth a 1-line clarification in the design doc to avoid future re-litigation.

---

## What I looked at

- `openspec/changes/supabase-backup-policy/proposal.md` (full, 65 lines).
- `openspec/changes/supabase-backup-policy/progress.md` (full, 26 lines).
- `openspec/changes/supabase-backup-policy/tasks.md` (full, 232 lines).
- `openspec/changes/supabase-backup-policy/design.md` (sampled the alternatives section + grep for `recovery|healthchecks|webhook|Traefik`, 30 hits — all in correctly-framed "rejected" / "deferred" contexts).
- `openspec/changes/supabase-backup-policy/specs/database-backups/spec.md` (full, 521 lines — primary source of the B1 finding).
- `scripts/backup/Dockerfile.runner` (full, 52 lines).
- `scripts/backup/docker-compose.yml` (full, 55 lines).
- `scripts/backup/runner/server.js` (full, 199 lines).
- `scripts/backup/runner/workers.js` (full, 114 lines).
- `scripts/backup/runner/auth.js` + `auth.test.js` (full).
- `scripts/backup/runner/mutex.js` + `mutex.test.js` (full).
- `scripts/backup/workers/_common.sh` (full).
- `scripts/backup/workers/alert.sh` (full).
- `scripts/backup/workers/backup-daily.sh` (full, 254 lines — confirmed v3 surgery on encryption + healthchecks).
- `scripts/backup/workers/backup-preop.sh` (full, 162 lines — confirmed v3 surgery; REASON regex validation safe).
- `scripts/backup/workers/verify-restore.sh` (full, 234 lines).
- `scripts/backup/test-smoke.sh` (full, 198 lines — caught M1).
- `scripts/backup/README.md` (sampled §2.5.2 / §2.5.3 / §2.5.5 / §2.5.6 / §2.5.7 + grep for `recovery|healthchecks|webhook|Traefik`).
- `scripts/backup/recipients/primary.txt` (1 line, expected).
- `scripts/backup/test-fixtures/recipients/test-recipient.txt` (1 line, dead — m4).
- `scripts/backup/verify-queries.sql` (sampled via spec reference — 4 probes, no `latest_transaction_age_days`, matches B3 fix).
- `scripts/backup/n8n/MyFinanceBackup-Daily.json` (full — caught M2).
- `scripts/backup/n8n/MyFinanceBackup-PreOp.json` (full — v3 manual-trigger-only, JSON valid, regex enforced; no Webhook node).
- `scripts/backup/n8n/MyFinanceBackup-Watchdog.json` (full — `lastDrill` cadence check present per tasks 7.4 + finding #5).
- `scripts/backup/n8n/MyFinanceBackup-ErrorHandler.json` (full).
- `scripts/backup/n8n/MyFinanceBackup-DispatchAlert.json` (full).
- `.env.example` (full — v3 cuts correctly applied; only HEALTHCHECKS / WEBHOOK_SECRET references are explanatory deletion comments).
- `.gitignore` (full — m1).
- `openspec/templates/supabase-write-checklist.md` (sampled — task 0 template exists).
- `plans/2026-05-31-supabase-backup-multi-tenant-design.md` (first 50 lines — Q2).
- JSON validity of all 5 n8n workflows (node -e JSON.parse — all OK).
- `git diff --stat` and `git status --short` for scope confirmation.

What I deliberately did NOT review at depth (coverage limits — diff is ~2.7k lines):
- The Dockerfile build assertion regex against current Alpine 3.21 apk index — task 6.2b is correctly unticked, operator will run this before activation.
- The actual R2 lifecycle policy configuration (out of code-review scope — task 1.5 is operator-side).
- The Kuma push URL freshness (task 1.10 — operator-side).
- The full text of `design.md` Decisions 1-12 (sampled Decision 2 + alternatives; the rejected-in-v3 framing checked out).
