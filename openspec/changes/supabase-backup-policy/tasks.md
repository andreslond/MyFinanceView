## 1. Operator prerequisites (one-time, manual, before any code lands)

- [ ] 1.1 On the Windows PC, install `age` from https://github.com/FiloSottile/age/releases (or `winget install age`) and confirm `age --version` works
- [ ] 1.2 **Generate the age key pair:** `age-keygen -o $env:USERPROFILE\.config\myfinance-backup\age-identity.txt`. Copy the matching recipient (public key â€” the line starting with `age1`) into the clipboard for step 2.2.
- [ ] 1.3 Restrict NTFS ACLs on `age-identity.txt` to the current user only; print one paper copy of the identity using Notepad â†’ File â†’ Print. Use a printer you trust (USB-connected printer preferred; for shared/networked printers, clear print job history after). Seal in envelope and file at a secure physical location (operator's documented choice â€” home safe / firebox / locked drawer). **Forensic-recovery hardening (Tails live-USB key generation, `cipher /W` free-space wipe, `vssadmin delete shadows`, dual-paper at a second location) is explicitly out of scope per `proposal.md ## Threat model` v3 â€” local forensic adversary is not in the v1 model.**
- [ ] 1.4 In the Cloudflare R2 dashboard, create an API token scoped to `Object Read & Write` on the existing `my-finance-view-backups` bucket; capture the Access Key ID, Secret Access Key, and the 32-char account ID
- [ ] 1.5 In Cloudflare R2, configure **FIVE** Object Lifecycle Policies on `my-finance-view-backups`: `daily/` 30 days, `weekly/` 90 days, `monthly/` 365 days, **`pre-op/` 90 days (B5 fix)**, **`quarantine/` 365 days (M15 fix)**; screenshot the dashboard for the operator runbook
- [ ] 1.6 Generate a Gmail App Password at https://myaccount.google.com/apppasswords with label `MyFinanceView backups`; capture the 16-char value
- [ ] 1.7 Invent an unguessable ntfy.sh topic slug (32+ chars, mix of letters and digits) and subscribe to it from the operator's phone via the ntfy app
- [ ] 1.8 Invent one 32-char secret: `MYFINANCE_BACKUP_RUNNER_SECRET` (n8n â†” sidecar shared header). **No webhook shared-secret needed in v1** â€” PreOp is triggered from the n8n UI only (no public webhook); see `proposal.md ## Threat model` v3.
- [ ] 1.9 In Supabase Dashboard â†’ MyFinanceView project (`akkoqdjmmozyqdfjkabg`) â†’ Settings â†’ Database, capture or reset the `postgres` role password; confirm the Session Pooler endpoint is `aws-0-us-west-2.pooler.supabase.com:5432`
- [ ] 1.10 In the existing Uptime Kuma UI on the VPS, create a Push Monitor named `MyFinance Daily Backup` with heartbeat = 24 h and grace period = 6 h; copy the resulting push URL (contains an unguessable token). **Wire AT LEAST ONE downstream notification channel to this monitor (finding #8 fix):** open the Kuma monitor's *Notifications* tab and attach one of (Telegram bot, Discord webhook, Gmail SMTP, ntfy.sh, generic webhook) so that grace-period exhaustion produces an alert the operator actually sees. **Smoke-verify the channel BEFORE activation:** click Kuma's "Test" button on the chosen notification channel and confirm a test message arrived. A Kuma monitor with no notification channel is silent on failure. Document the wired channel(s) in `scripts/backup/README.md Â§2.5.5` so a future operator picking up the rotation knows which to rotate.

## 2. Repository scaffold

- [x] 2.1 Create `scripts/backup/`, `scripts/backup/runner/`, `scripts/backup/workers/`, `scripts/backup/n8n/`, and `scripts/backup/recipients/`
- [x] 2.2 Write `scripts/backup/recipients/primary.txt` containing exactly the age public key from task 1.2 (one line, starts with `age1`). The filename is `primary.txt` for forward-compatibility â€” if a second recipient is reinstated later (see `design.md` Open Questions), it would be added as `recipients/recovery.txt` without renaming.
- [x] 2.3 Confirm root `.gitignore` already blocks `.env`, `.env.*` except `.env.example` (committed earlier); add explicit lines for `age-identity*`, `**/age-identity*`, `*.identity`, **`!scripts/backup/recipients/*.txt`** (whitelist the PUBLIC recipient files which DO need to be tracked), and a sanity rule rejecting any file whose contents start with `AGE-SECRET-KEY-`
- [x] 2.4 Confirm `.env.example` documents the full backup-pipeline variable contract: `BACKUP_DB_*`, `BACKUP_R2_*`, `MYFINANCE_BACKUP_AGE_*`, `MYFINANCE_BACKUP_NTFY_TOPIC`, `MYFINANCE_BACKUP_GMAIL_APP_PASSWORD`, `MYFINANCE_BACKUP_RUNNER_URL`, `MYFINANCE_BACKUP_RUNNER_SECRET`, `MYFINANCE_BACKUP_KUMA_PUSH_URL`. Each entry MUST be only a placeholder; no real secret may be committed. **NOT present (v3 cuts):** `MYFINANCE_BACKUP_HEALTHCHECKS_URL` (deferred â€” see `design.md` Decision 7), `MYFINANCE_PREOP_WEBHOOK_SECRET` (no webhook in v1 â€” see `design.md` Decision 9).
- [x] 2.5 Write `scripts/backup/README.md` with sections:
   - [x] 2.5.1 Overview â€” what this directory does, link to `openspec/specs/database-backups/spec.md`
   - [x] 2.5.2 Architecture â€” link to `openspec/changes/supabase-backup-policy/design.md` Â§10 (sidecar diagram)
   - [x] 2.5.3 Environment setup â€” explicit pointer: "the backup runner reads `scripts/backup/.env.local` (gitignored). The variable contract lives in the root `.env.example`. Copy that section into `scripts/backup/.env.local` on the VPS and fill the values. There is no duplicate `.env.example` here â€” DRY."
   - [x] 2.5.4 Operator runbook â€” install on VPS, smoke test, manual pre-op trigger **from the n8n UI** (Execute Workflow button with `reason` field), manual recovery from R2 using the age identity
   - [x] 2.5.5 Key rotation â€” age recipient (rebuild image after replacing the file under `recipients/`), age identity (re-encrypt drill), R2 token, Gmail App Password, Kuma push URL, runner shared-secret
   - [x] 2.5.6 Disaster scenarios:
     - **Paper AND PC identity simultaneously lost** = UNRECOVERABLE (the catastrophic key-loss path under the single-recipient design; documented and accepted per `proposal.md ## Threat model` v3)
     - **Paper destroyed (PC identity intact)** = re-print from PC immediately
     - **PC compromised / lost (paper intact)** = recover with paper on a clean machine
     - **VPS gone, Cloudflare gone simultaneously** = restore from monthly external-disk archive (frequency limit: â‰¤ 1 month old)
   - [x] 2.5.7 **Annual disaster drill checklist (operator habit, recurring January):** decrypt one snapshot with the paper identity on a clean machine, confirm the paper is still readable, re-print if smudged or water-damaged
- [x] 2.6 Create `openspec/templates/supabase-write-checklist.md` with a reusable "task 0" template for future OpenSpec changes touching Supabase remote: reads R2 `status/last-success.json`, confirms < 24 h old, **or directs the operator to run `MyFinanceBackup-PreOp` from the n8n UI** (Execute Workflow with `reason` field) and re-check `status/last-preop.json`. This is the artefact behind the process-gate Requirement's "future changes append this as task 0" claim.

## 3. Sidecar runner â€” Node + Express HTTP layer (`scripts/backup/runner/`)

- [x] 3.1 Write `package.json` pinning `express@^4`, `helmet@^7`, `pino@^9` and Node engines `>=22 <23`
- [x] 3.2 Run `npm install` once locally to produce `package-lock.json`; commit both
- [x] 3.3 Write `server.js` exposing `/healthz` (GET, no auth, returns version), `/status` (GET, no auth â€” see M14 below), and the three `/run/*` POST endpoints; bind only to `0.0.0.0:8080` inside the container. **`/status` MUST read from R2 (`r2:my-finance-view-backups/status/{last-success,last-preop,last-verify,last-drill}.json`), NOT from the runner's local `/var/lib/myfinance-backup/status/` cache** â€” R2 is the canonical source of truth so the watchdog sees the same state regardless of which runner replica answers (M14 fix). The `lastDrill` key surfaces `status/last-drill.json` for the disaster-drill watchdog (finding #5 fix); when the file is missing, the runner returns `lastDrill: null` rather than 404. Cache the R2 response for 60 s in-memory to avoid hammering R2 from a misconfigured polling client. If R2 is unreachable, `/status` returns 503 with `{"error":"r2_unreachable"}` rather than stale data â€” this propagates through to the n8n Watchdog as a workflow error and triggers the ErrorHandler.
- [x] 3.4 Write `auth.js` middleware that compares `X-Runner-Secret` header against `process.env.MYFINANCE_BACKUP_RUNNER_SECRET` using `crypto.timingSafeEqual`; reject mismatches with 401 and an empty body
- [x] 3.5 Write `mutex.js` exporting an `acquireRunLock()` / `releaseRunLock()` pair backed by an in-process boolean. **The mutex covers HTTP entrypoints only** (B5 fix): when `backup-preop.sh` invokes `verify-restore.sh` as a subprocess (not via HTTP), the Node mutex is irrelevant and the chained verify proceeds. A concurrent external `POST /run/*` while a run is active returns 409 with `{"error":"run_in_progress"}`. Document this asymmetry in the module's JSDoc.
- [x] 3.6 Write `workers.js` that spawns `/opt/myfinance-backup/workers/<name>.sh` via `child_process.spawn`, streams stdout/stderr into a log file under `/var/lib/myfinance-backup/logs/<run-id>.log`, and parses the worker's final JSON line as the HTTP response payload. **For verify chains (B7 fix):** the runner MUST (a) scrub the identity from the child env via `{ env: { ...process.env, MYFINANCE_BACKUP_AGE_IDENTITY: undefined } }`, (b) write the identity bytes onto the child's stdin pipe (`child.stdin.write(identity); child.stdin.end();`), and (c) document that the worker is expected to read stdin into a bash variable and persist it to a tmpfs file before calling `age -d -i`. The runner does NOT itself write the tmpfs file â€” that responsibility lives in the worker so the file lifetime is tied to the worker's EXIT trap, not to a separate Node-managed lifecycle that would race the worker on failure paths. The previous draft's `age -d -i /dev/stdin < snapshot.tar.age` worker invocation is REJECTED â€” it conflicts with the ciphertext stdin redirect and never works in practice.
- [x] 3.7 Wire pino structured logging with redaction of any header named `x-runner-secret` and any env var matching `*_SECRET`, `*_PASSWORD`, `*_KEY`
- [x] 3.8 Add a Jest test for the auth middleware (timing-safe rejection of wrong/missing secret) and one for the mutex (sequential vs concurrent calls)

## 4. Bash workers (`scripts/backup/workers/`)

- [x] 4.1 Write `_common.sh` (sourced by every worker): `set -euo pipefail`, `IFS=$'\n\t'`, logging helpers (`log_info`, `log_error`), `emit_json_result` helper that prints the final JSON line consumed by `workers.js`
- [x] 4.2 Write `alert.sh` exporting `dispatch_alert <title> <body>`; fires ntfy POST and Gmail SMTP in parallel via `&` + `wait`; returns 0 if at least one channel succeeded
- [x] 4.3 Write `backup-daily.sh`:
   - [x] 4.3.0 **pg_isready precheck:** `pg_isready -h "$BACKUP_DB_HOST" -p "$BACKUP_DB_PORT" -t 5 || { log_error "Supabase pooler $BACKUP_DB_HOST:$BACKUP_DB_PORT unreachable. If pg_dump previously worked, the pooler hostname may have moved â€” check Supabase Dashboard â†’ Connect â†’ Session pooler and update .env.local."; exit 3; }`
   - [x] 4.3.1 `pg_dump -Fc -Z 9 -t auth.users` against `$BACKUP_DB_HOST` using the Session Pooler endpoint; write `auth-users.dump` to a `mktemp -d` working dir. NO `--data-only` â€” captures DDL AND rows; verify-restore.sh will discard the DDL and only load the data because the production DDL references Supabase-only tables (B2 fix).
   - [x] 4.3.2 `pg_dump -Fc -Z 9 -n myfinance` against the same endpoint; write `myfinance.dump` to the same working dir.
   - [x] 4.3.3 Write `README.txt` with decryption hints (single identity path); `tar -cf snapshot.tar` the three files
   - [x] 4.3.4 **Encrypt against the single recipient (v3 cut):** `age -r "$(cat /opt/myfinance-backup/recipients/primary.txt)" -o snapshot.tar.age snapshot.tar`. The recipient file is committed under `scripts/backup/recipients/primary.txt`; the matching identity is operator-controlled and never reaches the daily-backup path (it is only read by `verify-restore.sh` from the runner-injected stdin pipe).
   - [x] 4.3.5 Compute LOCAL_SHA256 of `snapshot.tar.age`; rename to `YYYY-MM-DD.tar.age`
   - [x] 4.3.6 `rclone copy` to `r2:my-finance-view-backups/daily/`; `rclone check` for parity
   - [x] 4.3.6b **Post-upload SHA-256 re-verify (M12 fix):** re-download the just-uploaded object from R2 into a separate path under the working dir, compute its SHA-256, compare with LOCAL_SHA256. On mismatch: `rclone moveto r2:my-finance-view-backups/daily/<file> r2:my-finance-view-backups/quarantine/<timestamp>-daily-<file>` server-side, dispatch alert, exit non-zero. This catches truncated multipart uploads whose remote-side checksum matches the truncated bytes.
   - [x] 4.3.7 If Sunday, `rclone copyto` from `daily/` to `weekly/YYYY-Www.tar.age` (server-side)
   - [x] 4.3.8 If day-of-month=1, `rclone copyto` from `daily/` to `monthly/YYYY-MM.tar.age` (server-side)
   - [x] 4.3.9 **Chained restore-verify (reviewer Q2 fix):** invoke `verify-restore.sh --target daily/$(basename <just-uploaded>)` as an in-process subprocess; pass the runner's `$MYFINANCE_BACKUP_AGE_IDENTITY` along on the subprocess's stdin pipe (or rely on it being inherited if the daily script itself was started with the identity on its own stdin â€” Node's `workers.js` does this). On verify failure: `rclone moveto r2:my-finance-view-backups/daily/<file> r2:my-finance-view-backups/quarantine/<timestamp>-daily-<file>`, write the failure into `status/last-verify.json`, dispatch alert, exit non-zero. On verify success: upsert `status/last-verify.json` with green probes.
   - [x] 4.3.10 Upsert `status/last-success.json` to R2 with path, size, sha256, ISO-8601 UTC timestamp, durationMs (only reached on verify success). **Finding #10 fix â€” same-day quarantine-then-success visibility:** before writing, the worker MUST `rclone lsf r2:my-finance-view-backups/quarantine/ | grep "^$(date -u +%Y-%m-%d)-daily-"` to detect quarantined daily artefacts produced earlier the same UTC day. If any are found, populate `previous_failed_attempts: <count>` and `quarantined_artefacts: ["quarantine/<full-path>", â€¦]` in the upserted JSON. The watchdog / operator reading the file later thereby learns the day had â‰¥1 failed attempt before the green success and can investigate. Without these fields, a quarantineâ†’success day appears as a clean green run and the quarantined sibling is forgotten.
   - [x] 4.3.11 Append one line to `status/status.log` on R2
   - [x] 4.3.12 Kuma success ping (truly fire-and-forget): `curl -sS --max-time 10 "$MYFINANCE_BACKUP_KUMA_PUSH_URL?status=up&msg=ok&ping=$ELAPSED_MS" > /dev/null 2>&1 || log_info "kuma push failed (non-fatal)"`. NO `-f` flag.
   - [x] 4.3.13 Delete the working dir; `emit_json_result` with the success payload
   - [x] 4.3.14 On any failure (any step above exiting non-zero through `set -euo pipefail`), capture last 20 log lines, call `dispatch_alert "MyFinance backup FAILED" "$LOG_TAIL"`, exit non-zero (which makes Node return 500). The Kuma success ping is NOT sent on the failure path, allowing the in-cluster dead-man-switch to fire on schedule.
- [x] 4.4 Write `backup-preop.sh`:
   - [x] 4.4.1 **Validate `REASON` matches `^[A-Za-z0-9._+-]{3,60}$` (B4 fix â€” defense in depth, retained even though n8n UI is now the only caller);** on rejection emit JSON `{"error":"invalid_reason","regex":"^[A-Za-z0-9._+-]{3,60}$","got":"<the input>","example_accepted":"flyway-baseline","example_accepted_2":"v4.1-migration"}` and exit 2 (Node maps to HTTP 400 with this body)
   - [x] 4.4.2 Run the same pipeline as `backup-daily.sh` 4.3.0â€“4.3.6 but write to `pre-op/YYYY-MM-DDTHH-MM-SSZ-<reason>.tar.age`. Single-recipient encryption applies identically (4.3.4).
   - [x] 4.4.2b **Post-upload SHA-256 re-verify (M12 fix)** â€” same as 4.3.6b but for the `pre-op/` upload. On mismatch: move to `quarantine/<timestamp>-pre-op-<file>`, emit JSON `{"error":"upload_corrupted","local_sha256":"â€¦","r2_sha256":"â€¦","quarantined_to":"quarantine/â€¦"}`, dispatch alert, exit non-zero (Node returns HTTP 500 with this body).
   - [x] 4.4.3 Invoke `verify-restore.sh --target pre-op/<just-uploaded>` as an in-process subprocess (NOT via HTTP â€” same-container call). Pass the age identity through subprocess stdin.
   - [x] 4.4.4 On verify failure: `rclone moveto r2:my-finance-view-backups/pre-op/<file> r2:my-finance-view-backups/quarantine/<timestamp>-pre-op-<file>` server-side; emit JSON `{"error":"verify_failed","probe":{â€¦},"quarantined_to":"quarantine/â€¦"}`; do NOT update `status/last-preop.json`; dispatch alert via `dispatch_alert`; exit non-zero (Node returns HTTP 500).
   - [x] 4.4.5 On verify success, upsert `status/last-preop.json` with sha256 + probes; emit JSON success `{"artefact":"pre-op/<file>","sha256":"<hash>","verifyResult":{"probes":[â€¦]}}` (Node returns HTTP 200).
- [x] 4.5 Write `verify-restore.sh`:
   - [x] 4.5.1 Argument parsing: optional `--target <r2-path>`; default = newest object under `daily/` via `rclone lsf --files-only daily/ | sort -r | head -n 1`. `cd /var/lib/myfinance-verify` (compose-declared tmpfs, sibling of `/var/lib/myfinance-backup`).
   - [x] 4.5.2 `rclone copy r2:my-finance-view-backups/<target>` into `/var/lib/myfinance-verify/`.
   - [x] 4.5.3 **Read age identity from stdin into a bash variable, then write to tmpfs file (B7 fix):**
     ```bash
     # Read identity from stdin (Node pipes it in; see Â§3.6)
     IFS= read -rd '' AGE_IDENTITY < /dev/stdin || true
     # Defensive scrub in case env was ever set
     unset MYFINANCE_BACKUP_AGE_IDENTITY 2>/dev/null || true
     # Write to tmpfs with mode 0600; the leading umask guarantees the mode regardless of system default
     ( umask 0177 && printf '%s\n' "$AGE_IDENTITY" > /var/lib/myfinance-verify/.identity )
     # Defensive: scrub the variable too
     AGE_IDENTITY=""
     # Install trap NOW so even an Ctrl-C before next line wipes the file
     trap 'shred -u /var/lib/myfinance-verify/.identity 2>/dev/null || rm -f /var/lib/myfinance-verify/.identity 2>/dev/null || true' EXIT INT TERM
     # Decrypt â€” age reads identity from file path, ciphertext from positional arg
     age -d -i /var/lib/myfinance-verify/.identity snapshot.tar.age > snapshot.tar
     ```
     The previous draft's `age -d -i /dev/stdin < snapshot.tar.age` is REJECTED â€” stdin would be consumed by the `< ciphertext` redirect.
   - [x] 4.5.4 `tar -xf snapshot.tar` into `/var/lib/myfinance-verify/`.
   - [x] 4.5.5 **UUID-derived container name (M16 fix):** `VERIFY_CONTAINER="myfinance-verify-$(uuidgen | tr -d '-' | head -c 16)"`. Augment the EXIT trap to also stop the container: `trap 'docker stop "$VERIFY_CONTAINER" >/dev/null 2>&1 || true; shred -u /var/lib/myfinance-verify/.identity 2>/dev/null || rm -f /var/lib/myfinance-verify/.identity 2>/dev/null || true' EXIT INT TERM`
   - [x] 4.5.6 Spawn the ephemeral postgres:
      ```
      docker run -d --rm \
        --name "$VERIFY_CONTAINER" \
        --network n8n_net \
        -v /var/lib/myfinance-verify:/backup:ro \
        -e POSTGRES_PASSWORD=verify \
        -e POSTGRES_DB=postgres \
        postgres:17
      ```
      **DNS wait loop (M17 fix):** `for i in $(seq 1 10); do getent hosts "$VERIFY_CONTAINER" && break; sleep 1; done; getent hosts "$VERIFY_CONTAINER" || { log_error "Docker DNS never registered $VERIFY_CONTAINER"; exit 4; }`. Then `until pg_isready -h "$VERIFY_CONTAINER" -p 5432 -U postgres -t 5; do sleep 1; done` with a 60-s overall wall-clock cap.
   - [x] 4.5.7 **Pre-create `auth` schema and stub before restore (B2 fix):** `PGPASSWORD=verify psql -h "$VERIFY_CONTAINER" -U postgres -d postgres -v ON_ERROR_STOP=1 -c "CREATE SCHEMA IF NOT EXISTS auth; CREATE TABLE IF NOT EXISTS auth.users (id uuid PRIMARY KEY);"`. Then restore in order: (a) `pg_restore -h "$VERIFY_CONTAINER" -p 5432 -U postgres -d postgres --data-only --table=users -Fc /backup/auth-users.dump` (loads just the id column rows into the stub; `--data-only` discards the broken DDL); (b) `pg_restore -h "$VERIFY_CONTAINER" -p 5432 -U postgres -d postgres -Fc /backup/myfinance.dump` (full DDL + data; FKs to `auth.users(id)` resolve against the stub).
   - [x] 4.5.8 For each query in `verify-queries.sql`, run via `PGPASSWORD=verify psql -h "$VERIFY_CONTAINER" -U postgres -d postgres -t -A` and compare against the documented threshold; emit a probe result entry. **NO `latest_transaction_age_days` probe** (B3 fix).
   - [x] 4.5.9 Teardown â€” the EXIT trap stops the container (`--rm` removes it on stop) AND wipes the tmpfs identity file unconditionally.
   - [x] 4.5.10 Upsert `status/last-verify.json` to R2 with target path, probes, timestamp.
   - [x] 4.5.11 On any failure exit non-zero; the trap guarantees both container cleanup and identity wipe even on `set -euo pipefail` propagation.
- [x] 4.6 Make all worker scripts executable (`git update-index --chmod=+x scripts/backup/workers/*.sh`) and confirm `set -euo pipefail` is the first non-shebang line in each
- [x] 4.7 **Smoke-test the bash workers against Postgres-in-Docker (finding #12 fix â€” project rule `base-standards.md Â§5`: integration tests use real Postgres, no mocks):** write `scripts/backup/test-smoke.sh` that:
   1. Boots a local Postgres 17 container via `docker run -d --name myfinance-smoke-pg --network n8n_net -e POSTGRES_PASSWORD=smoke -e POSTGRES_DB=postgres postgres:17`, waits `pg_isready`.
   2. Seeds the schema: applies `scripts/backup/test-fixtures/seed.sql` (this fixture file MUST be committed and MUST contain `CREATE SCHEMA myfinance`, the minimal table set `transactions`, `accounts`, `categories` matching the production DDL, AND row counts that exceed the verify thresholds â€” 400 transactions, 3 accounts, 20 categories â€” plus a stub `auth.users(id uuid PRIMARY KEY)` with 1 row).
   3. Runs `backup-daily.sh` against the seeded local Postgres by injecting `BACKUP_DB_HOST=myfinance-smoke-pg BACKUP_DB_PORT=5432 BACKUP_DB_PASSWORD=smoke BACKUP_DB_USER=postgres BACKUP_DB_NAME=postgres BACKUP_R2_*=<dummy-or-minio-localhost>` (smoke test against a local MinIO container OR a stubbed rclone target â€” operator chooses).
   4. Asserts the produced artefact decrypts (against the test-fixtures recipient identity, which IS committed under `scripts/backup/test-fixtures/recipients/`), the SHA-256 re-verify path executes, and `verify-restore.sh` chained from the daily script returns green probes.
   5. Tears down the smoke containers and exits 0 on success, non-zero on any failure.
   6. **CI hook:** add to `.github/workflows/backup-smoke.yml` (or equivalent if the operator picks a different CI later) to run on every PR touching `scripts/backup/`. Currently this project has no CI configured per `SPEC.md`; until CI lands, the operator runs this script manually before any change to `scripts/backup/*.sh` lands on main. Document in `scripts/backup/README.md Â§2.5.6`.

   This is the integration test that converts the bash workers from "untested" to "tested against real Postgres". Project rule `base-standards.md Â§5` forbids mocking the database; this script is the conforming alternative.

## 5. Verification probe suite â€” `scripts/backup/verify-queries.sql`

- [x] 5.1 `SELECT count(*) FROM myfinance.transactions` with threshold `>= 300` and probe key `transactions_count`
- [x] 5.2 `SELECT count(*) FROM myfinance.accounts` with threshold `>= 1` and probe key `accounts_count` â€” **M8 fix**: previous draft was `WHERE active = true >= 3` which tested operator business state (how many cards), not backup integrity, and would fire false alarms the day a card is archived
- [x] 5.3 `SELECT count(*) FROM myfinance.categories` with threshold `>= 19` and probe key `categories_count`
- [x] 5.4 `SELECT count(*) FROM auth.users` with threshold `>= 1` and probe key `auth_users_count` (the verify-restore stub plus the `--data-only` load should produce â‰¥ 1 row)
- [x] 5.5 **DROPPED (B3 fix):** the previous `latest_transaction_age_days <= 7` probe is REMOVED. That signal â€” recency of ingest â€” conflates backup integrity with ingest health. A 10-day vacation or a Christmas-week dip in transaction volume would fire false alerts on a perfectly valid backup. Recency monitoring is a separate concern, deliberately out of scope for this verify suite.
- [x] 5.6 Add a header comment (m19 fix â€” be honest about probe semantics):
   ```
   -- verify-queries.sql v1 â€” restore probe suite for MyFinanceView backups.
   --
   -- SEMANTICS: these thresholds are SMOKE DETECTORS, not ALARMS. They catch
   -- "the restore produced an empty or near-empty database" â€” a structural
   -- failure. They do NOT catch row-level corruption that stays within the
   -- threshold (e.g. losing 50 of 400 transactions, since 350 still â‰¥ 300).
   -- A proper integrity alarm would compare against the previous successful
   -- verify's counts (requires persistent state on R2; deferred).
   --
   -- WHEN TO RE-BASELINE: bump the floors after a meaningful schema change
   -- (e.g. when `savings_goals` lands, add a probe with a sensible floor;
   -- when `transactions` count durably exceeds 1000, raise its floor to ~700).
   -- Always bump the version number at the top of this file when editing.
   ```

## 6. Dockerfile and compose extension

- [x] 6.1 Write `scripts/backup/Dockerfile.runner` based on **`node:22-alpine3.21`** (pinned â€” Alpine 3.21 is the first release that carries `postgresql17-client` in apk); `apk add --no-cache postgresql17-client age rclone docker-cli tar gzip curl jq bash tini util-linux` (util-linux for `uuidgen`); assert `pg_dump --version | grep '^pg_dump (PostgreSQL) 17\.'` in the same RUN layer so the build fails fast if the package install drifts; `COPY runner/`, `COPY workers/ /opt/myfinance-backup/workers/`, **`COPY recipients/ /opt/myfinance-backup/recipients/`** (single `primary.txt` file for the single-recipient encryption pass â€” v3); `chmod +x` the workers; `chmod -R 0444 /opt/myfinance-backup/recipients/` (recipient is read-only inside the image â€” a compromised runner cannot rewrite it to a recipient the attacker controls); entrypoint `/sbin/tini --`; CMD `node server.js`
- [ ] 6.2 Build the image locally on the VPS via `docker compose -f n8n/docker-compose.yml -f scripts/backup/docker-compose.yml build myfinance-backup-runner`; the build itself asserts `pg_dump --version` reports 17.x, so success of `docker compose build` is sufficient evidence. Smoke: `docker run --rm myfinance-backup-runner pg_dump --version` reports `pg_dump (PostgreSQL) 17.x`
- [ ] 6.2b **Alpine pin freshness check (finding #11 fix â€” Alpine 3.21 EOLs eventually; the change may sit unimplemented for months):** before activation, confirm that `node:22-alpine3.21` STILL pulls AND `apk add postgresql17-client` still resolves. Run `docker pull node:22-alpine3.21` and `docker run --rm node:22-alpine3.21 sh -c "apk update && apk search postgresql17-client | head -1"`; expect the search to return a `postgresql17-client-â€¦` package line. If the base image has been retagged to a non-existent digest OR the apk package has been renamed/removed, the pin needs updating BEFORE activation. Update `scripts/backup/Dockerfile.runner` accordingly (likely bumping to `node:22-alpine3.22` or whatever the current Alpine LTS is) and re-run task 6.2. Document the verified pin and date in `scripts/backup/README.md Â§2.5.2` (architecture) so a future operator can repeat the check on rotation.
- [x] 6.3 Write `scripts/backup/docker-compose.yml` defining the `myfinance-backup-runner` service:
   - [x] 6.3.1 Image built from `Dockerfile.runner`; `restart: unless-stopped`; `env_file: ./.env.local`; resource limits 256 MB RAM; NO host port published
   - [x] 6.3.2 `networks: [n8n_net]` only; declare `n8n_net` as `external: true` in the `networks:` top-level section
   - [x] 6.3.3 Bind-mount the persistent runner state: `/var/lib/myfinance-backup:/var/lib/myfinance-backup` (read-write â€” holds `status/`, `logs/`, `working/`)
   - [x] 6.3.4 Bind-mount the Docker socket: `/var/run/docker.sock:/var/run/docker.sock` (read-write â€” needed to spawn the ephemeral `postgres:17` verify container)
   - [x] 6.3.5 Declare an ephemeral tmpfs for verify working dirs OUTSIDE the persistent bind-mount: `tmpfs: ["/var/lib/myfinance-verify:size=512m,mode=0700"]`. The hermano-path (`/var/lib/myfinance-verify`, not nested under `/var/lib/myfinance-backup`) avoids Docker's nested-mount edge cases. Plaintext decrypted snapshots live ONLY in this tmpfs.
   - [x] 6.3.6 Workers MUST NOT call `mount`/`umount` â€” the tmpfs is declared at compose level, no `CAP_SYS_ADMIN` required inside the container
- [x] 6.4 Declare `n8n_net` as `external: true` in this compose file so it joins the existing network created by n8n's compose project
- [ ] 6.5 Smoke test on the VPS: `docker compose ... up -d myfinance-backup-runner`; from inside the n8n container `curl http://myfinance-backup-runner:8080/healthz` returns 200

## 7. n8n workflows (`scripts/backup/n8n/`)

- [x] 7.1 Author `MyFinanceBackup-Daily.json`: Schedule Trigger (02:30 America/Bogota) â†’ HTTP Request POST `http://myfinance-backup-runner:8080/run/daily` with header `X-Runner-Secret = {{ $credentials.runnerSecret }}` and **1200s timeout** (covers pg_dump + chained restore-verify; reviewer Q2 fix) â†’ on non-2xx the workflow's Error Trigger fires
- [x] 7.2 Author `MyFinanceBackup-PreOp.json`: **Manual Trigger only â€” no public webhook in v1 (v3 cut, see `proposal.md ## Threat model`)**. Workflow shape: Manual Trigger (with `reason` field as a workflow input parameter) â†’ Function node validates `reason` against **`^[A-Za-z0-9._+-]{3,60}$` (B4 fix â€” retained as defense in depth)** â€” on regex failure throw an error so the n8n UI shows the operator a clear validation message AND the ErrorHandler fires the same alert path â†’ HTTP Request POST `http://myfinance-backup-runner:8080/run/preop` with header `X-Runner-Secret = {{ $credentials.runnerSecret }}` and body `{"reason": "{{ $json.reason }}"}` and **1200s timeout** â†’ the sidecar's JSON response surfaces in the n8n execution log. Operator invokes via the n8n UI's "Execute Workflow" button.
- [ ] 7.3 **DROPPED â€” verify is now chained inside Daily (reviewer Q2 fix).** The previous `MyFinanceBackup-VerifyRestore.json` (Sunday 03:30 weekly verify) is REMOVED from this change. Reason: a verify-lag of up to 6 days is too long for a single-user system with cheap VPS CPU. The `/run/verify` HTTP endpoint stays on the sidecar for ad-hoc operator invocation.
- [x] 7.4 Author `MyFinanceBackup-Watchdog.json`: Schedule Trigger (daily 09:00 America/Bogota) â†’ HTTP Request GET `http://myfinance-backup-runner:8080/status` â†’ IF node: if `lastSuccess` is null OR `(now - lastSuccess.timestamp) > 30h` OR `lastVerify` is null OR `(now - lastVerify.timestamp) > 30h`, call the shared "Dispatch Alert" sub-workflow with title `MyFinance backup STALE` and body containing the JSON status. Watching both timestamps catches the case where the daily run uploaded but verify failed (in which case `last-success.json` is NOT updated but `last-verify.json` is â€” operator should still see the alert). **Additionally (finding #5 fix â€” disaster-drill cadence probe):** the same workflow MUST also fetch `r2://my-finance-view-backups/status/last-drill.json` (the `/status` endpoint surfaces it as `lastDrill`); if `lastDrill` is null OR `(now - lastDrill.timestamp) > 400 days`, the workflow MUST invoke the Dispatch Alert sub-workflow with title `MyFinance backup drill OVERDUE` and a body pointing at `scripts/backup/README.md Â§2.5.7`. This alert is low-severity (does not fail the daily pipeline) but fires on every watchdog tick until the operator uploads a fresh drill JSON.
- [x] 7.5 Author `MyFinanceBackup-ErrorHandler.json`: Error Trigger (binds to the workflows above) â†’ Dispatch Alert sub-workflow with title derived from the failing workflow name
- [x] 7.6 Author `MyFinanceBackup-DispatchAlert.json` (the shared sub-workflow): Execute Workflow trigger taking `{title, body}` â†’ ntfy HTTP Request POST `https://ntfy.sh/{{ $credentials.ntfyTopic }}` with title header (Continue On Fail = true) â†’ in parallel Gmail Send Email node configured with credential `MYFINANCE_BACKUP_GMAIL_APP_PASSWORD` pinned to `host=smtp.gmail.com port=587 secure=false (STARTTLS) user=aftorresl01@gmail.com` (M13 fix; Continue On Fail = true) â†’ both fire regardless of the other's outcome; the workflow never returns a non-2xx so calling workflows do not double-alert
- [x] 7.7 Validate every workflow file with `jq empty < scripts/backup/n8n/<file>.json` and confirm n8n's importer accepts it (manual UI import on the VPS). The set of committed workflows is **FIVE**: Daily, PreOp (manual-trigger-only), Watchdog, ErrorHandler, DispatchAlert.

## 8. VPS deployment

- [ ] 8.1 On the VPS, populate `scripts/backup/.env.local` (gitignored) with every secret from Â§1 and Â§2.4. Verify file mode `600` and owner = the operator user. Configure the `rclone` remote in `~/.config/rclone/rclone.conf` (or `/etc/rclone.conf` for system-wide use) with **`provider = Cloudflare`** under `[r2]`:
   ```
   [r2]
   type = s3
   provider = Cloudflare
   access_key_id = <BACKUP_R2_ACCESS_KEY_ID>
   secret_access_key = <BACKUP_R2_SECRET_ACCESS_KEY>
   endpoint = https://<BACKUP_R2_ACCOUNT_ID>.r2.cloudflarestorage.com
   acl = private
   ```
- [ ] 8.2 BEFORE `docker compose up`, confirm the existing n8n's Docker network name matches what the compose file declares: `docker network ls | grep n8n_net` MUST return a row. If the operator's n8n compose project actually created a different network name (e.g. `n8n_default`, `<project>_n8n_net`), either rename it for consistency or override the `networks` block in `scripts/backup/docker-compose.yml` accordingly â€” the runner WILL fail to start otherwise and the n8n workflows' `http://myfinance-backup-runner:8080` URL silently fails name resolution
- [ ] 8.3 `docker compose -f n8n/docker-compose.yml -f scripts/backup/docker-compose.yml up -d myfinance-backup-runner` and confirm `docker ps` lists the container as healthy
- [ ] 8.4 From the n8n UI, Import each `scripts/backup/n8n/*.json` file in order (FIVE files: Daily, PreOp, Watchdog, ErrorHandler, DispatchAlert); map credentials in n8n's Credentials UI: `MYFINANCE_BACKUP_AGE_IDENTITY` (identity for verify), `MYFINANCE_BACKUP_RUNNER_SECRET`, `MYFINANCE_BACKUP_NTFY_TOPIC`, `MYFINANCE_BACKUP_GMAIL_APP_PASSWORD`, `MYFINANCE_BACKUP_KUMA_PUSH_URL`. **Not present in v1 (v3 cuts):** `MYFINANCE_PREOP_WEBHOOK_SECRET`, `MYFINANCE_BACKUP_HEALTHCHECKS_URL`.
- [ ] 8.4a **Re-link errorWorkflow after import (M3 fix from replant adversarial review):** n8n's `errorWorkflow` setting in the exported JSON carries the target workflow NAME as a string (e.g. `"errorWorkflow": "MyFinanceBackup-ErrorHandler"`), but at import time n8n binds errorWorkflow by numeric ID — a bare name string is silently ignored in most n8n versions. **For each of Daily, PreOp, and Watchdog: open the workflow → Settings → Error Workflow dropdown → select `MyFinanceBackup-ErrorHandler` → Save.** Verify by intentionally triggering a failure (e.g. invalid `BACKUP_DB_PASSWORD`) and confirming the ErrorHandler workflow fires (n8n Executions list shows a run of `MyFinanceBackup-ErrorHandler` with the failing parent's data). Without this step, n8n-side failures produce no ErrorHandler alert (the Kuma dead-man-switch still catches workflow non-execution, but ErrorHandler-only paths go silent).
- [ ] 8.5 Confirm Uptime Kuma push URL is reachable from inside the runner container: `docker exec myfinance-backup-runner curl -fsS "$MYFINANCE_BACKUP_KUMA_PUSH_URL?status=up&msg=test"` returns 2xx

## 9. Smoke tests

- [ ] 9.1 From the operator's Windows PC, `curl http://<vps-public-ip>:8080/healthz` SHOULD fail (connection refused) â€” confirms the runner is not internet-exposed
- [ ] 9.2 From the n8n UI, click "Execute Workflow" on `MyFinanceBackup-PreOp` with `reason="Flyway Baseline!"` (note the space and bang) â€” expect the Function node to throw with the regex rejection message containing `regex` AND `example_accepted` keys (B4 fix verification)
- [ ] 9.3 From the n8n UI, click "Execute Workflow" on `MyFinanceBackup-PreOp` with `reason="v4.1-migration_attempt2"` â€” expect HTTP 200 from the sidecar, an artefact in `pre-op/` on R2, AND the eventual artefact path contains the literal slug `v4.1-migration_attempt2` (B4 fix verification)
- [ ] 9.4 From the n8n UI, click "Execute Workflow" on `MyFinanceBackup-PreOp` with `reason="initial-bootstrap"`; expect HTTP 200, an artefact in `pre-op/` on R2, `verifyResult.probes` green in the response, AND `status/last-preop.json` updated on R2
- [ ] 9.5 **Identity drill (single-recipient smoke):** on a clean machine (NOT the operator's primary PC if possible), retrieve the paper identity from the envelope, type it into a local file, run `age -d -i identity.txt < <one-pre-op-snapshot-downloaded-from-R2>.tar.age > /tmp/check.tar` and confirm `tar -tf /tmp/check.tar` lists the three expected files. Then wipe `/tmp/check.tar` and the typed identity file. This proves the recovery chain works end-to-end.
- [ ] 9.6 Force a failure to test the alerting path: temporarily set `BACKUP_DB_PASSWORD` to an invalid value in `.env.local`, restart the runner, trigger `MyFinanceBackup-Daily` manually; confirm BOTH ntfy push and Gmail email arrive AND that Kuma has NOT been pinged
- [ ] 9.7 Restore the correct password; trigger `MyFinanceBackup-Daily` manually again; confirm `last-success.json` AND `last-verify.json` update, the Kuma monitor shows "Up"
- [ ] 9.8 Force a verify-failure path: temporarily lower a probe threshold in `verify-queries.sql` (e.g. set transactions floor to `>= 9999999`), trigger `MyFinanceBackup-Daily` manually; confirm the artefact is moved server-side from `daily/` to `quarantine/<timestamp>-daily-<file>`, `status/last-verify.json` records the failing probe, alerts fire (M15 fix verification). Restore the threshold after the test.
- [ ] 9.9 Trigger `MyFinanceBackup-Watchdog` manually after rewinding `last-success.json` to a timestamp > 30 h old; confirm dual-channel alert fires

## 10. Activation

- [ ] 10.1 In n8n, set Schedule Trigger to Active for `MyFinanceBackup-Daily` and `MyFinanceBackup-Watchdog` (no separate VerifyRestore schedule â€” verify is chained inside Daily; PreOp stays manual-trigger-only)
- [ ] 10.2 Wait one full 24-hour cycle; confirm the daily run fired automatically at 02:30 BOG, the daily/ artefact lands in R2, `last-success.json` AND `last-verify.json` update, Kuma shows the push received
- [ ] 10.3 Wait one full week; confirm Sunday's run promoted to `weekly/` AND `last-verify.json` continues to update green for every daily run in that week
- [ ] 10.4 If today's date is the 1st of a month at the time of activation, manually fire `MyFinanceBackup-Daily` again to confirm the `monthly/` promotion path; otherwise set a calendar reminder to spot-check on the 1st
- [ ] 10.5 **Calendar reminder â€” monthly external-disk archive (M11):** set a recurring calendar reminder (1st of each month, 10:00 BOG) titled "rclone copy r2:my-finance-view-backups/monthly/ E:\myfinance-backups-archive\" with the full command in the body. Operator habit, not an n8n workflow (operator decision 2026-05-13).
- [ ] 10.6 **Annual disaster drill calendar reminder + initial drill (finding #5 fix):** set a recurring annual reminder (e.g. January 15th) titled "MyFinance backup disaster drill" with checklist body referencing `scripts/backup/README.md Â§2.5.7`. THEN perform the initial drill immediately as part of activation: retrieve the paper from the envelope, confirm legibility, decrypt one snapshot end-to-end on a clean machine, then upload the result to R2:
   ```bash
   # Generate and upload last-drill.json as part of bootstrap
   cat > /tmp/last-drill.json <<EOF
   {"timestamp":"$(date -u +%Y-%m-%dT%H:%M:%SZ)","paperIntact":true,"reprinted":false,"snapshotUsed":"<the snapshot path>","operatorInitial":"AT"}
   EOF
   rclone copy /tmp/last-drill.json r2:my-finance-view-backups/status/
   rm /tmp/last-drill.json
   ```
   Without this initial drill artefact, the watchdog (task 7.4) will start firing `MyFinance backup drill OVERDUE` from day 1 of activation.

## 11. Documentation and memory updates

- [ ] 11.1 Add section "Backup & disaster recovery" to `docs/development-guide.md` covering daily cadence (with chained verify), pre-op procedure (n8n UI "Execute Workflow" example with `reason` field), restore procedure from snapshot (single-identity path documented), age key rotation procedure
- [ ] 11.2 Add subsection "Backup before any Supabase write" to `docs/development-guide.md` listing covered operations (Flyway migrate/baseline/repair/clean, ad-hoc DDL/DML via psql, MCP apply_migration, MCP execute_sql) and the freshness expectations (24 h daily / 60 min pre-op). **Use the words "expected" / "should" / "operator discipline" â€” NOT "blocked" / "refuses" / "enforced" (B6 fix â€” the gate is documentation-only and the docs must reflect that honestly)**
- [ ] 11.3 Cross-reference the new section from `SPEC.md Â§12` (PrÃ³ximos Pasos Inmediatos)
- [ ] 11.4 Cross-reference the new section from `docs/data-model.md Â§3` (migrations section)
- [ ] 11.5 Update auto-memory `project_supabase_production_data.md` to add a `[[supabase-backup-policy]]` link and the explicit "24 h daily or 60 min pre-op" freshness expectation (NOT "rule"). Replace the "backup policy pendiente" note with "backup policy live since YYYY-MM-DD". This is an OPERATOR action (the auto-memory store is user-scoped, not project-scoped); listed here so the operator does not forget it during /opsx:archive.

## 12. Validation and archive

- [ ] 12.1 Run `openspec validate supabase-backup-policy --strict` from the repo root; fix any structural complaints until it passes clean
- [ ] 12.2 Run the adversarial-review skill against the change; address any Blocker or Major findings â€” **applying Gate C threat-model triage** (`proposal.md ## Threat model`): findings against out-of-scope adversaries (local forensic, off-VPS dead-man-switch, public webhook) are auto-rejected
- [ ] 12.3 Update the change with a note in `proposal.md` confirming the policy is live and the bootstrap artefact path
- [ ] 12.4 Notify the project page on Notion that `flyway-migrations` Â§7 is unblocked
- [ ] 12.5 `/opsx:archive supabase-backup-policy` and `/openspec-sync-specs` to merge the delta into `openspec/specs/database-backups/`
