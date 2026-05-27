## Context

Supabase remote (project `akkoqdjmmozyqdfjkabg`, schema `myfinance`) holds three months of irreplaceable transaction data for a single user. The free tier offers dashboard-level backups but they are not directly downloadable, the operator's workstation is Windows 10 (no always-on machine at home), and there is no staging environment. The in-flight `flyway-migrations` change introduces a one-time `flyway:baseline` (§7) and a subsequent stream of `Vn` migrations — every one of those is a write against the remote database. Memory `supabase-production-data` already states the rule that no write may occur without a verified recent backup; this change is what makes that rule operable.

Constraints inherited from `SPEC.md`, `docs/base-standards.md`, and project memories:

- Real financial data — privacy is a hard constraint. Encrypted at rest is non-negotiable.
- One operator. The operator already runs an n8n instance on a Linux VPS for the Gmail ingest pipeline; that VPS is the de-facto always-on host.
- Free / near-zero monthly cost. Dataset is small (single-user, < 1 MB compressed today, < 100 MB projected over years).
- No application code change — backups must run out-of-band of the Spring Boot app.
- The procedure must be runnable on demand (pre-operation snapshot) and automatically (daily schedule + restore verification).
- The operator owns a Cloudflare account with an existing R2 bucket `my-finance-view-backups` and prefers to keep cloud spend on that account.

Stakeholders: the operator (Andres) is the sole consumer; future Claude Code sessions are the secondary audience because the policy gates `/opsx:apply` against Supabase.

## Goals / Non-Goals

**Goals:**

- A single `pg_dump`-based snapshot pipeline producing **encrypted custom-format** dumps of the `myfinance` schema plus the rows of `auth.users` referenced by FK from `myfinance` (`user_id` columns).
- Two storage locations per snapshot: a primary cloud bucket on Cloudflare R2 (off-Supabase, off-VPS, off-operator-PC) and a transient working copy on the VPS filesystem that is also used as the source for restore verification.
- Automated **restore verification** on a separate cadence that proves the most recent encrypted snapshot can be decrypted and restored end-to-end into an ephemeral Postgres on the VPS.
- A **retention policy** (30 daily / 90 weekly / 365 monthly / 30 pre-op) enforced by R2 lifecycle rules so no manual housekeeping is required.
- A **manual on-demand procedure** (a single workflow execution in n8n) that the operator triggers before any write to Supabase, producing a clearly-labelled `pre-op-<timestamp>-<reason>.tar.age` snapshot that survives the daily retention cycle.
- **Dual-channel alerting** (ntfy.sh push + Gmail SMTP email) when the scheduled run fails, is skipped, or when restore verification fails.
- **Documentation** in `docs/development-guide.md` so the policy is discoverable from the entrypoint.

**Non-Goals:**

- Point-in-time recovery (PITR). Free-tier Supabase does not expose WAL; logical dumps capture state at moment of run only. Acceptable for a single user whose writes are dominated by daily ingest.
- Hot standby / failover. The dataset can tolerate hours of recovery time.
- Backing up the `public` schema. It still contains Chatwoot leftovers (TASK-DT-02) that will be dropped; capturing them in long-lived backups would just pin sensitive third-party data.
- Backing up Supabase Storage buckets. The project does not use object storage yet.
- A custom UI or dashboard. Status is a small n8n workflow output plus a push notification + email.
- Integration into the Spring Boot deployable. The pipeline is a sibling of `database/`, not a Java module.
- Automating the off-VPS, off-R2 third copy. The operator pulls a monthly archive from R2 to an external disk by hand; physical media rotation is documented, not scripted.

## Decisions

### 1. Snapshot tool: `pg_dump --format=custom --compress=9` over the `myfinance` schema

**Why:** Custom format gives selective restore, parallel restore (`pg_restore -j`), and built-in compression. Compression level 9 trades CPU for size on a dataset measured in MB.

**Filter strategy:** two-step dump bundled into one tar.

1. Auxiliary schema+data dump of `auth.users`: `pg_dump -Fc -Z 9 -t auth.users -f auth-users.dump`. Captures both the table DDL and the rows. **Scope clarification (finding #16 fix — earlier draft text "minimum required `auth.users` rows" was misleading):** `pg_dump -t <table>` dumps the ENTIRE table — every row, every column — not only rows referenced by myfinance FKs. For a single-user system this is one row, so the practical difference is nil. For a future multi-user / federated-login state it would mean every auth.users row ends up in every daily snapshot. The operator is aware of this and accepts it; if the project ever needs row-level filtering, the fix is to materialize a filtered view (`CREATE VIEW myfinance.auth_users_proxy AS SELECT id FROM auth.users WHERE id IN (SELECT user_id FROM myfinance.transactions)`) and dump that view instead — deferred. Supabase's other `auth.*` objects (functions, triggers, policies, `auth.identities`, `auth.mfa_factors`, etc.) are NOT captured (acknowledged in the "Coverage gaps" Requirement, finding #7 fix) — only the single `auth.users` table required to satisfy myfinance's foreign keys.
2. Schema + data dump for `myfinance`: `pg_dump -Fc -Z 9 -n myfinance -f myfinance.dump`. Captures everything under the bounded context.

Both files plus a tiny `README.txt` are bundled into one `.tar` before encryption so a single artefact represents a snapshot. **Restore order matters:** `auth-users.dump` MUST be restored first so that `auth.users` exists before myfinance's foreign-key constraints reference it; this is reflected in Decision 6 (`verify-restore.sh`) and in the operator runbook.

**Connection target — Supabase Session Pooler (not direct):** the VPS is IPv4-only, so the direct endpoint `db.akkoqdjmmozyqdfjkabg.supabase.co:5432` (which requires IPv6 unless the project pays the $4/mo IPv4 add-on) is not used. The script connects through the Supabase **Session Pooler** instead:

- Host: `aws-0-<region>.pooler.supabase.com` (the exact region is captured from the Supabase dashboard at install time and stored as `BACKUP_DB_HOST` in n8n credentials).
- Port: `5432` (session mode — supports long-running prepared statements that `pg_dump` requires). **NOT** port `6543` (transaction mode, breaks `pg_dump`).
- Username: `postgres.akkoqdjmmozyqdfjkabg` (Pooler-format: `<role>.<project_ref>`).
- Password: the same Postgres role password from the Supabase dashboard, stored as n8n credential `BACKUP_DB_PASSWORD`.
- TLS: `?sslmode=require` is mandatory; Supabase rejects plaintext.

Concrete connection string in the script:

```
postgresql://postgres.akkoqdjmmozyqdfjkabg:$BACKUP_DB_PASSWORD@aws-0-<region>.pooler.supabase.com:5432/postgres?sslmode=require
```

**Client version:** the VPS MUST have `postgresql-client-17` installed (matching Supabase's Postgres 17.6). A `pg_dump` from `postgresql-client-16` errors out with `server version mismatch`. Install path on Debian/Ubuntu: add the official PGDG apt repository, then `apt-get install postgresql-client-17`.

**Auth model**: `pg_dump` connects as the `postgres` Postgres role over TCP+TLS — it does **not** use the Supabase `anon`/`service_role` JWT tokens (those are PostgREST-layer auth and irrelevant for direct DB access). RLS does not affect the dump because the `postgres` role is the table owner.

**Alternatives considered:**

- `pg_dumpall`: dumps roles + tablespaces we don't own and won't be allowed to restore against a free-tier Supabase. Rejected.
- Plain SQL (`-Fp`): readable but no parallel restore, larger files, no selective restore. Rejected — custom format is the standard.
- Supabase `db dump` CLI: works but is a thin wrapper around `pg_dump` and ties the script to the Supabase CLI release cadence. Direct `pg_dump` is portable.
- Direct endpoint over IPv4 add-on: rejected — operator does not currently pay for the add-on and the Session Pooler covers the use case at zero cost.
- Transaction pooler (port 6543): rejected — incompatible with `pg_dump`'s long-lived prepared statements.

### 2. Encryption: `age` with a single recipient (paper at one physical location)

**Why:** Single small static binary on Linux, modern construction (X25519 + ChaCha20-Poly1305), recipient (public-key) mode. The VPS only ever encrypts (it holds the public key, baked into the runner image); the private identity stays on the operator's workstation. Encrypts the bundle in place: `age -r "$(cat recipients/primary.txt)" -o snapshot.tar.age snapshot.tar`.

**Why single recipient (v3 retroactive scope cut, supersedes B1 dual-recipient):** the threat model declares "local forensic adversary with physical disk access" out of scope. A second recovery paper at a geographically-separated physical location defends primarily against the *simultaneous* destruction of the operator's PC AND the single paper (fire, flood, theft from one location). For a personal finance app that already keeps a monthly external-disk archive (Decision 4) as a tertiary copy, the dual-paper model is defense in depth against a low-probability triple-failure scenario. Accepted residual risk for v1; a future change can promote to dual-recipient if the operator's circumstances change.

**Key custody — single-paper model:**

- **One recipient public key** is committed at `scripts/backup/recipients/primary.txt`. It is baked into the runner image at build time. The VPS only ever needs the public key to **encrypt** new snapshots; it never needs the identity for the daily path.
- **Identity** (private key for `primary.txt`) lives in two places:
  1. On the operator's Windows workstation at `%USERPROFILE%\.config\myfinance-backup\age-identity-primary.txt`, with NTFS ACLs restricted to the user account.
  2. As a printed copy in a sealed envelope filed at a secure physical location (operator's documented choice — e.g., home safe, firebox, locked drawer). Documented in `docs/development-guide.md`.
- **For restore verification**, the VPS reads `MYFINANCE_BACKUP_AGE_IDENTITY` from n8n credentials, passes it through the runner's stdin pipe to the bash worker, and the worker writes it to a tmpfs-backed file at `/var/lib/myfinance-verify/.identity` with mode `0600` for the duration of `age -d -i`. An EXIT trap wipes the file unconditionally (B7 fix — the previous draft tried `age -d -i /dev/stdin < snapshot.tar.age` which is broken because stdin redirection from the ciphertext file replaces stdin with the ciphertext, so age never sees the identity. The tmpfs-file approach uses a private temp file whose path lives in argv but whose content is in RAM only).

**Disaster-recovery scenarios:**

| Scenario | Recoverable? |
|---|---|
| Operator's PC lost, paper intact | YES — recover with paper identity on a clean machine. |
| Paper lost, PC intact | YES — recover with PC identity; re-print and re-file paper immediately. |
| PC and paper both lost simultaneously | NO from encrypted R2 — UNRECOVERABLE. Documented as the catastrophic key-loss path; accepted for v1 per threat model. |

**Alternatives considered:**

- **Dual-recipient (primary + recovery papers at two physical locations):** RETROACTIVELY REJECTED for v1 (was the previous v2 design's B1 fix). Adversary "local forensic adversary with PC + safe access" is out of threat model per `proposal.md ## Threat model`. Re-evaluate if the operator's circumstances change (multi-property, frequent travel, hostile-jurisdiction concerns).
- Multi-recipient with hardware tokens (Yubikey/SOPS): hardware not yet in the operator's toolchain. Deferred.
- GPG: heavier toolchain, more failure modes (key trust, agent). Rejected for a one-person setup.
- OpenSSL `aes-256-gcm` with a passphrase: single factor, no public-key separation, no clean way to do encrypt-only on the VPS. Rejected.
- Bucket-side encryption only (R2 server-side): trusts Cloudflare with plaintext. Rejected because the dataset is real financial data.

### 3. Primary storage: Cloudflare R2 bucket `my-finance-view-backups` via `rclone`

**Why:** The operator already owns the R2 bucket; R2 is S3-compatible, ~$0.015/GB-month with zero egress fees, generous free tier (10 GB storage, 1M Class A ops/month, 10M Class B ops/month), and supports server-side lifecycle rules that mirror the retention requirement. `rclone` with provider `Cloudflare` (S3-compatible mode) handles all access. A dataset projected at < 100 MB over years sits comfortably inside the free tier.

**R2 op-count budget (finding #13 fix — proposal asserted "< $0.10/month" without the op-count math):** the daily critical path generates the following operations per day:

- **Class A (writes/list):** 1 upload (`PutObject` for `daily/YYYY-MM-DD.tar.age`) + 1 server-side promotion on Sundays (`CopyObject` for `weekly/`) + 1 server-side promotion on day-1 of month (`CopyObject` for `monthly/`) + 4 status JSON upserts (`last-success`, `last-verify`, optionally `last-preop` on pre-op days, optionally `last-drill` once/year) + 1 `ListObjects` for the verify-target lookup + 1 `ListObjects` for the quarantine-same-day probe (finding #10). Worst-case daily Class A: ~9 ops. Monthly: ~270 ops. Plus pre-op runs (~12/year × 5 ops = 60 ops/year ≈ 5 ops/month). **Headroom against the 1M/month Class A free tier: 99.97% unused.**
- **Class B (reads):** 1 `GetObject` for the verify pull + 1 `GetObject` for the post-upload SHA-256 re-verify (finding M12) + status JSON reads via the `/status` endpoint (worst-case 4 reads × every watchdog tick = 28 reads/week assuming daily watchdog ≈ 4 reads/day) + the watchdog's `lastDrill` read = 5 reads/day. Worst-case daily Class B: ~7 ops. Monthly: ~210 ops. **Headroom against the 10M/month Class B free tier: 99.998% unused.**
- **Storage:** dataset < 100 MB projected over years, plus ~30 quarantine artefacts × 100 KB ≈ 3 MB ceiling. Well inside the 10 GB free tier.
- **Egress:** every server-side `CopyObject` between R2 prefixes (daily→weekly, daily→monthly, `<prefix>`→quarantine) flows entirely inside R2 with the `provider = Cloudflare` rclone setting — zero egress bytes traverse the runner. Verify downloads ARE egress (R2 → runner) but R2 charges zero for egress to the public internet by Cloudflare's R2 pricing as of 2026.

Even with 10× growth of every line above, the change stays inside the free tier with two orders of magnitude of headroom. The "< $0.10/month" estimate in the proposal is conservative (effectively $0.00/month at this scale).

**R2 endpoint URL:** `https://<CLOUDFLARE_ACCOUNT_ID>.r2.cloudflarestorage.com`. The account ID is configured as `BACKUP_R2_ACCOUNT_ID` in n8n credentials and surfaced in `.env.example` for documentation. Jurisdiction defaults to "global"; if the operator later picks "EU" the endpoint becomes `https://<account_id>.eu.r2.cloudflarestorage.com` — the rclone remote config absorbs the difference and no script changes.

**R2 credentials:** generated by the operator in the Cloudflare dashboard under "R2" → "Manage R2 API tokens" → "Create API token" scoped to `Object Read & Write` on `my-finance-view-backups` only. Two values:

- `BACKUP_R2_ACCESS_KEY_ID`
- `BACKUP_R2_SECRET_ACCESS_KEY`

Both live in n8n credentials. They are never seen by the assistant, never logged, never committed.

**Bucket layout:**

```
my-finance-view-backups/
├── daily/      YYYY-MM-DD.tar.age
├── weekly/     YYYY-Www.tar.age              (every Sunday's copy promoted)
├── monthly/    YYYY-MM.tar.age               (1st-of-month copy promoted)
├── pre-op/     YYYY-MM-DDTHH-MM-SSZ-<reason>.tar.age
├── quarantine/ YYYY-MM-DDTHH-MM-SSZ-<source-prefix>-<filename>.tar.age
└── status/     last-success.json, last-preop.json, last-verify.json, status.log
```

Lifecycle rules: `daily/` keeps 30 days, `weekly/` keeps 90 days, `monthly/` keeps 365 days, **`pre-op/` keeps 90 days (B5 fix — previous draft had 30 days, same as daily, which would lose pre-op snapshots before slow-discovery migration bugs surface; 90 days is a balance between retention and free-tier storage budget), `quarantine/` keeps 365 days** (verify-failed artefacts the operator needs to inspect — long retention because they represent a forensic event). Lifecycle is enforced **bucket-side** by R2 Object Lifecycle Policies, not by the script, so retention survives a script bug.

**Quarantine routing (M15 fix):** when restore-verify rejects a just-uploaded artefact (daily, pre-op, or weekly/monthly promotion), the worker performs a server-side `rclone moveto` of the artefact from its original prefix into `quarantine/` with a name encoding the source prefix and original filename. Status JSON files are updated to point at the quarantine location and the failure is alerted. This guarantees a verified-bad artefact is never retained under a "good" prefix where a future operator might mistake it for a valid snapshot.

**Alternatives considered:**

- Backblaze B2: cheaper per-GB but the operator already pays Cloudflare; consolidating cost is a real win.
- AWS S3: similar pricing, more onboarding friction (account, IAM), egress charges.
- Google Drive via rclone: free 15 GB, no native S3 lifecycle, retention would have to be enforced by the script — fragile. Rejected.

### 4. Secondary copy: transient working directory on the VPS

**Why:** The previous draft of this design assumed a Windows operator running the daily job locally and proposed `%USERPROFILE%\myfinance-backups\` as a permanent second copy. With n8n on a VPS, that local Windows folder is no longer in the daily critical path. The VPS filesystem now holds the working copy *during the run* (needed for `rclone copy` and for restore verification) but the artefact does not need to persist there longer than one verification cycle — the canonical store is R2.

**Resulting policy:**

- The daily script writes the encrypted bundle to `/var/lib/myfinance-backup/working/YYYY-MM-DD.tar.age`, uploads it to R2, computes SHA-256 from both ends, then **deletes the working file** if `rclone check` confirms parity.
- The verify-restore workflow downloads the most-recent `daily/` object from R2 fresh each run — it does not rely on the working dir. This means a corrupt VPS disk cannot poison the verification.
- The operator runs a **manual monthly archive** (documented procedure, not automated): `rclone copy r2:my-finance-view-backups/monthly/ E:\myfinance-backups-archive\` from their Windows PC to an external disk. The reminder mechanism is a calendar entry the operator owns; this change does NOT add an n8n notification workflow for the reminder, because the operator (M11 decision) accepted the realistic slip risk against the cost of adding another workflow. **Honest framing:** this is closer to a 2-1-1 layout (R2 as primary off-site + occasional external-disk archive) than a strict 3-2-1. If the operator finds in practice that the monthly archive slips beyond two months, a future change can add the reminder workflow at low cost (one extra n8n Schedule Trigger + one ntfy/email node) — this is the "promoted to n8n notification if it slips" follow-up listed in Open Questions.

### 5. Scheduling host: n8n workflows on the operator's existing VPS

**Why:** The operator already runs n8n on a Linux VPS for the Gmail-to-Supabase ingest. Co-locating the backup workflows there means: one always-on machine to maintain, one secret store (n8n Credentials) to lock down, reuse of existing alert recipes, and zero new CI/cloud-runner dependency. The VPS Linux environment makes `pg_dump`, `age`, `rclone`, and Docker straightforward.

**Workflows (each lives at `scripts/backup/n8n/<name>.json` for version control, imported into n8n at install time):**

- `MyFinanceBackup-Daily` — Schedule Trigger (daily 02:30 America/Bogota) → HTTP Request `POST http://myfinance-backup-runner:8080/run/daily` with header `X-Runner-Secret: $MYFINANCE_BACKUP_RUNNER_SECRET` (1200 s timeout — covers `pg_dump` + chained verify). At v1 dataset size (~1 MB compressed, ~400 transactions, ~3 accounts, ~20 categories) the worker takes ~5 min wall-clock; the 1200 s budget gives ~15 min of headroom and covers ~24 months of organic growth at < 1 MB/year. `backup-daily.sh` records `durationMs` in `status/last-success.json`; the operator bumps the timeout when daily `durationMs` durably exceeds 600 000 ms. On 2xx the workflow ends; the sidecar internally chains pg_dump → tar → age → rclone upload → weekly/monthly promotion → restore-verify (Decision 6) → status JSON upserts → Uptime Kuma push (Decision 7). On non-2xx the workflow's Error Trigger fires (see ErrorHandler).
- `MyFinanceBackup-PreOp` — **Manual Trigger only** (n8n UI Execute Workflow, see Decision 9) → Validate `reason` against `^[A-Za-z0-9._+-]{3,60}$` → HTTP Request `POST http://myfinance-backup-runner:8080/run/preop` with JSON body `{"reason":"<slug>"}` and `X-Runner-Secret` header → display the sidecar's JSON response inline in the n8n execution log. The sidecar uploads, performs the post-upload R2 SHA-256 re-verify (Decision 9 M12 fix), AND runs full restore-verify before returning 2xx.
- `MyFinanceBackup-Watchdog` — Schedule Trigger (daily 09:00 America/Bogota) → HTTP Request `GET http://myfinance-backup-runner:8080/status` → check `lastSuccess.timestamp` AND `lastVerify.timestamp` in response → if either is > 30 h old or missing, dispatch alert via the shared alerting sub-workflow. This is the in-cluster watchdog; the Uptime Kuma Push Monitor (in-cluster dead-man-switch, Decision 7) is the second in-cluster signal.
- `MyFinanceBackup-ErrorHandler` — n8n Error Trigger (fires when any of the workflows above errors at workflow level) → dispatch alert via the shared alerting sub-workflow. Catches both workflow-engine errors (e.g. the sidecar HTTP call timed out) and explicit non-2xx responses from the sidecar.
- `MyFinanceBackup-DispatchAlert` (sub-workflow) — Execute Workflow trigger taking `{title, body}` → ntfy HTTP Request POST + Gmail SMTP Send Email in parallel (both with Continue On Fail = true). Returns 2xx unconditionally so calling workflows do not double-alert. This is the **fifth** workflow file in `scripts/backup/n8n/` (counting the PreOp manual-trigger workflow).

**Why no separate `MyFinanceBackup-VerifyRestore` workflow:** the previous draft had a weekly Sunday verify; reviewer Q2 (and B2's discovery of restore-correctness bugs) made it clear that letting up to 6 days pass between a snapshot and its first verification is too long for a single-user system with cheap VPS CPU. Verify now runs chained inside `MyFinanceBackup-Daily` (i.e. `POST /run/daily` returns only after restore-verify completes). Cost is ~3–5 min of VPS CPU per day — well within the runner's resource limits. Benefit: every snapshot is verified the day it is taken; a structural restore failure surfaces immediately, not days later. The standalone `/run/verify` HTTP endpoint stays on the sidecar (Decision 10) for ad-hoc invocation from the operator, but it is no longer triggered on a schedule by n8n.

**Why HTTP-to-sidecar instead of Execute Command:** n8n itself runs the vanilla `n8nio/n8n:latest` Alpine image (no `pg_dump`, no `age`, no `rclone`, no Docker CLI). Adding those tools into the n8n container would couple its upgrade cadence with the backup tooling and would widen the privilege footprint of the container. Decision 10 introduces a separate `myfinance-backup-runner` container that exposes the HTTP API consumed by these workflows.

**Trade-off — same-VPS failure domain:** if the VPS itself is down (host outage, Docker crash, `apt-get upgrade --auto-remove` mishap, disk full), n8n + sidecar + Kuma all go down simultaneously and none of these in-cluster signals reach the operator. **Mitigation accepted for v1 (was M1 fix in v2 retroactively deferred — see Decision 7 "VPS-wide failure-domain trade-off"):** the operator notices via the parallel Gmail-ingest workflow's Telegram silence within hours. An off-VPS dead-man-switch (healthchecks.io) is a deferred follow-up.

**Alternatives considered:**

- GitHub Actions: rejected for privacy — dump exists in plaintext in CI-runner RAM even though only the encrypted artefact lands in storage.
- Cron on the VPS directly (no n8n): possible but discards the n8n credential store and the unified UI; harder to trigger pre-op snapshots without an SSH session.
- Cloudflare Workers + Cron Triggers: appealing for the "no infrastructure" angle but Workers can't run `pg_dump` (no native binaries, 30 s CPU cap, no persistent disk). Rejected.

### 6. Restore verification: ephemeral Docker Postgres on the VPS + read-only query suite

**Why:** A backup that nobody has ever restored is Schrödinger's backup. The verification job is what converts the snapshot from "we hope" to "we know".

**Algorithm (in `verify-restore.sh`):**

1. Resolve target: argument `--target <r2-path>` is honoured when chained from `backup-daily.sh` / `backup-preop.sh`; otherwise default = newest object under `daily/` via `rclone lsf --files-only daily/ | sort -r | head -n 1`.
2. `rclone copy` that object into `/var/lib/myfinance-verify/` — the **compose-declared tmpfs** (Decision 10), so plaintext lives only in RAM.
3. **Receive the age identity via stdin** from the parent Node process (`workers.js` writes the identity to the child's stdin pipe and closes it; the worker reads it into a bash variable). The worker writes the identity to `/var/lib/myfinance-verify/.identity` with `umask 0177` (mode `0600`) for the duration of `age -d -i`. An EXIT trap (installed before the file is written) does `shred -u /var/lib/myfinance-verify/.identity 2>/dev/null || rm -f /var/lib/myfinance-verify/.identity` so the file is wiped on every exit path. **B7 fix:** the previous draft proposed `age -d -i - < snapshot.tar.age > snapshot.tar` — that is broken because `< snapshot.tar.age` replaces stdin with the ciphertext file, so the age binary never sees the identity. The tmpfs-file approach uses a private temp file whose path appears in argv but whose content is in RAM only (tmpfs).
4. Decrypt: `age -d -i /var/lib/myfinance-verify/.identity snapshot.tar.age > snapshot.tar`. The trap deletes `.identity` immediately on exit (success or failure).
5. `tar -xf snapshot.tar` into `/var/lib/myfinance-verify/` (extracts `auth-users.dump`, `myfinance.dump`, `README.txt`).
6. Generate a unique container name: `VERIFY_CONTAINER="myfinance-verify-$(uuidgen | tr -d '-' | head -c 16)"` — **M16 fix** (previous draft used `$$-$RANDOM`; UUID slug avoids any chance of name collision when subprocess chains share `$$`).
7. `docker run -d --rm --name "$VERIFY_CONTAINER" --network n8n_net -v /var/lib/myfinance-verify:/backup:ro -e POSTGRES_PASSWORD=verify -e POSTGRES_DB=postgres postgres:17`. Install the EXIT trap (`docker stop "$VERIFY_CONTAINER" >/dev/null 2>&1 || true; rm -f /var/lib/myfinance-verify/.identity 2>/dev/null || true`) BEFORE the docker run actually succeeds, so a failure between docker-run and pg_isready still cleans up.
8. **DNS resolution wait loop (M17 fix):** Docker's embedded DNS registers the container name asynchronously. Before `pg_isready`, loop on `getent hosts "$VERIFY_CONTAINER"` until success (max 10 s) — this prevents the script from misclassifying a DNS-registration race as a connection failure and emitting a wrong "Postgres did not start" alert.
9. Wait for Postgres ready: `until pg_isready -h "$VERIFY_CONTAINER" -p 5432 -U postgres -t 5; do sleep 1; done` with a 60-s overall timeout.
10. **Pre-create the `auth` schema and stub `auth.users` (B2 fix):** before restoring myfinance, run `psql -h "$VERIFY_CONTAINER" -U postgres -d postgres -c "CREATE SCHEMA IF NOT EXISTS auth; CREATE TABLE IF NOT EXISTS auth.users (id uuid PRIMARY KEY);"`. **Why:** `pg_dump -t auth.users -Fc` (per Decision 1) captures the table's DDL *and* its rows, but the production `auth.users` table has FK relationships to other `auth.*` tables (`auth.identities`, `auth.mfa_factors`) and column types that depend on Supabase-installed extensions. Restoring it as-is against a vanilla `postgres:17` fails with `ERROR: schema "auth" does not exist` or with FK-target-missing errors. The fix is to *not* trust the dump's DDL for `auth.users` and instead pre-create a minimal stub (`auth.users(id uuid)`) sufficient to satisfy the `myfinance.*.user_id` foreign keys. The auxiliary dump is then restored `--data-only` against this stub, loading just the `id` column. This proves myfinance's FKs resolve against the auth ids in the backup without dragging in Supabase-specific auth machinery the verify ephemeral has no business running.
11. Restore in order — `export PGPASSWORD=verify`, then:
    - `pg_restore -h "$VERIFY_CONTAINER" -U postgres -d postgres --data-only --table=users -Fc /backup/auth-users.dump` (loads just `auth.users(id, …)` rows into the stub; `--data-only` skips the broken DDL).
    - `pg_restore -h "$VERIFY_CONTAINER" -U postgres -d postgres -Fc /backup/myfinance.dump` (full DDL + data; the FKs to `auth.users(id)` resolve against the stub).
12. Execute every query in `scripts/backup/verify-queries.sql` and compare against documented thresholds:
    - `SELECT count(*) FROM myfinance.transactions` ≥ 300 (transactions floor).
    - `SELECT count(*) FROM myfinance.accounts` ≥ 1 (accounts floor — **M8 fix**: was `WHERE active = true >= 3`; that probe was actually testing operator business state, not backup integrity, and would fire false alarms the day the operator closes a card).
    - `SELECT count(*) FROM myfinance.categories` ≥ 19 (categories floor).
    - `SELECT count(*) FROM auth.users` ≥ 1 (auth-stub got at least one row).
    - **`latest_transaction_age_days` REMOVED (B3 fix).** The previous draft included `SELECT EXTRACT(EPOCH FROM (now() - max(occurred_at)))/86400 FROM myfinance.transactions <= 7`. That conflates *backup integrity* (the restore worked) with *ingest health* (transactions are flowing). They are independent concerns: a vacation or a Christmas-week dip can produce a perfectly healthy backup with no recent transactions, and the previous probe would erroneously alert on it. The recency check belongs in a separate ingest-health monitor (not in scope for this change); the verify probes test backup correctness only.
13. Tear down: the EXIT trap stops the container (`--rm` removes it on stop) and wipes the tmpfs identity file. `docker ps -a` shows no leftover `myfinance-verify-*` container.
14. The compose-declared tmpfs (`/var/lib/myfinance-verify`) is owned by Docker — no manual `umount` is needed. Its contents are wiped on container restart or when the runner exits.
15. On any failure (decrypt, restore, probe): the worker writes the failing probe + log tail into the JSON result, the EXIT trap cleans up, and the worker exits non-zero. When the verify is chained from `backup-daily.sh` or `backup-preop.sh`, the parent invokes the **quarantine routing** (Decision 3 M15 fix) before alerting.

The probe thresholds live in `verify-queries.sql` so the operator can tune them as the dataset grows without editing the bash script. The probe suite header comments explicitly note that these are **smoke detectors, not alarms** — they catch "empty/near-empty restore" structural failure, not row-level corruption within thresholds.

### 7. Alerting: three independent channels (ntfy + Gmail + Kuma)

**Why three:** Each layer covers a failure mode the previous one cannot.

- **ntfy.sh + Gmail SMTP (explicit dispatch)** answer "the run failed and we know it" — both fire in parallel from the worker as long as the runner has outbound internet.
- **Uptime Kuma Push Monitor (in-cluster dead-man-switch)** answers "the n8n workflow never ran" — Kuma fires when the daily success heartbeat is missing for > 30 h, even if the worker never got far enough to call ntfy/Gmail.

**Channels:**

- **ntfy.sh**: HTTP Request node POSTs to `https://ntfy.sh/<topic>`. Topic name is an unguessable slug stored as n8n credential `MYFINANCE_BACKUP_NTFY_TOPIC` and surfaced as `MYFINANCE_BACKUP_NTFY_TOPIC` placeholder in `.env.example`. Title is `MyFinance backup FAILED` / `STALE` / `verify FAILED` / `quarantined`; body is the last 20 lines of the script's log. Push priority `high` on failure.
- **Gmail SMTP**: n8n Send Email node configured with a Gmail SMTP credential using a Gmail **App Password** (the operator generates it once at https://myaccount.google.com/apppasswords). Pinned to `host=smtp.gmail.com`, `port=587`, `secure=false` (STARTTLS upgrade), `user=aftorresl01@gmail.com`. From `aftorresl01@gmail.com` to `aftorresl01@gmail.com`. Subject mirrors the ntfy title. Body includes script name, exit code, full log (not just last 20 lines — email has space).
- **Uptime Kuma Push Monitor (in-cluster dead-man-switch):** the operator already runs a self-hosted Uptime Kuma instance on the same VPS for monitoring other services. The daily sidecar run sends one HTTP `GET https://<kuma-host>/api/push/<token>?status=up&msg=ok&ping=<elapsed_ms>` on success — Kuma's "push monitor" pattern (a passive dead-man-switch). Configured with heartbeat = 24 h and grace period = 6 h; if no ping arrives within 30 h Kuma fires its own alert through channels wired inside Kuma. The push URL (containing the token) is stored as n8n credential `MYFINANCE_BACKUP_KUMA_PUSH_URL` and surfaced as `.env.example` placeholder; the token is itself a secret.

**VPS-wide failure-domain trade-off (v3 retroactive scope cut, supersedes M1 healthchecks.io):** all three channels are co-located on the operator's VPS. A complete VPS outage silences all three simultaneously. **Mitigation accepted for v1:** the same VPS also hosts the n8n Gmail-ingest workflow that produces a Telegram message every time a new transaction arrives at the operator's bank. The operator notices the silence on that Telegram channel within hours (typical day = 2–5 transactions). An off-VPS dead-man-switch (healthchecks.io, UptimeRobot, BetterStack) is deferred — if the Telegram-silence signal proves insufficient in practice (e.g. a multi-day silence missed during travel), adding healthchecks.io is a 30-min follow-up change with one new n8n node and one new `.env` variable.

**Channels triggered when:**

| Event | ntfy | Gmail | Kuma |
|---|---|---|---|
| `backup-daily.sh` exits non-zero | ✓ | ✓ | (no success push) |
| `backup-preop.sh` exits non-zero | ✓ | ✓ | n/a (pre-op does not push Kuma) |
| `verify-restore.sh` exits non-zero (chained from daily) | ✓ | ✓ | (no success push, daily counted as failed) |
| Watchdog finds `last-success.json` > 30 h old | ✓ | ✓ | (n/a — Kuma is the cause-of-detection) |
| n8n ErrorHandler fires (workflow-engine error) | ✓ | ✓ | — |
| VPS-wide outage (n8n + sidecar + Kuma all down) | — | — | — (operator notices via Telegram-ingest silence) |
| `backup-daily.sh` succeeds | — | — | success push |

Successful daily runs upsert `r2://my-finance-view-backups/status/last-success.json`, append one line to `r2://…/status/status.log`, and push Kuma. No alert on success.

**Alternatives considered:**

- **healthchecks.io (off-VPS dead-man-switch — was M1 fix in v2):** RETROACTIVELY DEFERRED for v1 (see "VPS-wide failure-domain trade-off" above). Re-evaluate after 90 days of v1 operation; if a VPS outage was missed despite the Telegram-ingest signal, promote to a follow-up change.
- Telegram via the existing n8n flow: tight coupling to the Gmail ingest workflow which itself depends on Supabase being healthy; if Supabase is the failure mode the Telegram path may be impaired. Rejected as the primary failure-alert channel; the operator notices `silence` on it as the VPS-outage signal, which is a different role.
- ntfy alone: chosen by default in the early draft but the operator wanted email redundancy. Adopted both.

### 8. Process gate: documentation only — honest framing (B6 fix)

**Why the wording matters:** the earlier draft declared this change BREAKING and described the gate as "blocked unless a verified backup … exists". That overclaimed: there is **no code path anywhere in this change (Spring Boot, the n8n workflows, the sidecar, or the `/opsx:apply` skill) that refuses to run when the freshness file is stale.** MCP `apply_migration`, `execute_sql`, ad-hoc `psql`, and ad-hoc shell scripts all bypass any documented rule. Calling that "BREAKING" implies a safety the system does not provide.

**Operator decision (recorded 2026-05-13):** keep the gate as documentation-only, and rename "BREAKING — process only" to "process documentation". An automated check script (e.g. `scripts/check-supabase-backup-fresh.sh` wired into `/opsx:apply`) was offered and explicitly declined for v1 — the operator's reasoning is that the realistic risk path (their own fat-finger during a planned migration) is best addressed by the in-flight migration's `tasks.md` listing "verify pre-op snapshot exists" as task 0, not by a runtime hook that would also need maintenance.

**What this change actually provides:**

- An updated section in `docs/development-guide.md` titled "Backup before any Supabase write" that lists the covered operations (Flyway `migrate`/`baseline`/`repair`/`clean`, ad-hoc DDL/DML via `psql` or the Supabase SQL editor, MCP `apply_migration`, MCP `execute_sql`) and the freshness expectations (24 h daily / 60 min pre-op).
- An update to auto-memory `supabase-production-data` to add a `[[supabase-backup-policy]]` link and the explicit "24 h daily or 60 min pre-op" expectation.
- A short reusable checklist (committed under `openspec/templates/supabase-write-checklist.md`) that future OpenSpec changes touching Supabase remote can append to their `tasks.md` as task 0.

**What this change explicitly does NOT provide:**

- No build-time gate.
- No CI-time gate.
- No runtime gate inside `/opsx:apply`, the backend, or the sidecar.
- No MCP-side hook.

**Future evolution:** if/when a CI-side migration runner is introduced (e.g. Flyway in GitHub Actions), the runner SHALL be modified to refuse to execute when `status/last-success.json` is older than 24 h. That modification is scoped to whichever change introduces the runner, not to this one. Until then the gate's enforcement is honestly described as "operator discipline + checklist + the `adversarial-review` skill's flagging of missing snapshot evidence."

### 9. Manual on-demand snapshot: n8n UI trigger only, mandatory reason

**Why:** The pre-op snapshot is the most critical artefact in the whole policy because it is the one tied to a specific irreversible action. It must be (a) easy to take, (b) unmissable in cloud storage, and (c) labelled with the reason so post-incident review can reconstruct what happened.

**Trigger path (v3 retroactive — single path, no public webhook):**

- **From the n8n UI:** the operator opens the n8n web interface, navigates to `MyFinanceBackup-PreOp`, clicks **Execute Workflow**, fills the `reason` field in the test/input form (`flyway-baseline`, `manual-export-2026-05-26`, etc.), clicks Execute. n8n displays the live execution and the JSON result inline.

**Why no public webhook (v3 retroactive scope cut, supersedes Decision 9's Two-paths model and M3 Traefik IP allowlist):** the previous v2 design exposed `https://n8n.datachefnow.com/webhook/myfinance-backup-preop` to the public internet, hardened with a shared-secret header AND a Traefik IP allowlist. Adversary "public-internet attacker against a pre-op webhook" is now out of threat model (per `proposal.md ## Threat model`). Removing the webhook eliminates: (a) the entire `X-Webhook-Secret` lifecycle, (b) the Traefik dynamic-config commit `traefik/dynamic/myfinance-preop.yml`, (c) the IP-allowlist maintenance burden when the operator travels, (d) the input-validation regex hardening (B4 fix), and (e) the n8n Webhook node entirely. The cost is real but bounded: `/opsx:apply` automation can no longer drive a pre-op snapshot from outside the n8n UI. For v1 of a one-operator personal-finance backend, the operator always opens n8n by hand before a migration — this is not a meaningful loss of ergonomics. If non-interactive trigger ever becomes a hard requirement (e.g. a CI-side migration runner), adding the webhook later is a bounded follow-up.

The `MyFinanceBackup-PreOp` workflow:

1. **Validates `reason` against `^[A-Za-z0-9._+-]{3,60}$`** (still kept — protects the artefact filename from shell metachars even though the input is operator-typed). On rejection, the workflow stops with an error visible in the n8n execution log: `invalid reason; accepted regex ^[A-Za-z0-9._+-]{3,60}$; examples: flyway-baseline, v4.1-migration`.
2. **Refuses absent reason** with the same n8n execution-log error.
3. Calls `POST http://myfinance-backup-runner:8080/run/preop` with header `X-Runner-Secret: $MYFINANCE_BACKUP_RUNNER_SECRET` and JSON body `{"reason":"<slug>"}`. (The `X-Runner-Secret` is the internal n8n↔sidecar shared header — still required because it bounds what *another container on `n8n_net`* can do, even though there is no public surface.)
4. Names the artefact `YYYY-MM-DDTHH-MM-SSZ-<reason>.tar.age` and uploads to `pre-op/`.
5. **Post-upload SHA-256 re-verify (M12 fix).** After `rclone copy` reports success, the worker re-downloads the just-uploaded object from R2 into a separate tmpfs path and re-computes its SHA-256. The downloaded SHA-256 MUST match the SHA-256 computed pre-upload from the working file; on mismatch the artefact is moved to `quarantine/` and the request fails with HTTP 500 `{"error":"upload_corrupted","local_sha256":"…","r2_sha256":"…"}`. **Why:** the previous draft computed SHA-256 only locally and relied on `rclone check` parity. Both checks are necessary but not sufficient against a truncated multipart upload whose remote-side checksum matches the truncated bytes (rare but observed in real S3-compatible storage). The independent re-download + re-hash closes the gap.
6. **Then runs a full restore-verify** (Decision 6 algorithm — download fresh from R2, decrypt, restore into ephemeral `postgres:17`, run the probe suite).
7. **Only writes 2xx + `status/last-preop.json` on R2 if every step succeeds.** A pre-op snapshot that uploaded but failed restore-verify is moved to `quarantine/` (Decision 3 M15 fix), the operator is alerted, and the n8n execution shows HTTP 500 with the failing probe in the body. The operator MUST treat HTTP 500 as "no protection — do not proceed with the irreversible operation."
8. **Expected end-to-end runtime is 3–5 minutes** — the operator watches the live execution in n8n and proceeds with the migration only after the green tick.

### 10. Sidecar runner — `myfinance-backup-runner` container, Node + Express HTTP API

**Why:** Decisions 5 and 9 require that n8n trigger long-running operations (pg_dump → tar → age → rclone → restore-verify) that depend on Linux tooling not present in the `n8nio/n8n:latest` Alpine base image. The two rejected alternatives are (a) extending the n8n image — couples n8n's upgrade cadence with backup-tooling upgrades and widens the attack surface of the container that also hosts public webhooks; (b) `ssh user@host` from n8n to the VPS — requires SSH credentials inside the n8n container and bypasses Docker's isolation. The chosen path is a **sidecar container** dedicated to backup execution.

**Principle deviation — formalized by SPEC.md amendment (M9 + adversarial-review finding #2 fix):** `CLAUDE.md` and `docs/base-standards.md §2` mandate a "monolito modular por dominio. One Spring Boot deployable. Packages by bounded context" with microservices listed as a forbidden pattern. Introducing a Node + Express sidecar is, formally, a second service. The earlier draft self-exempted the sidecar inline; finding #2 (2026-05-13) correctly flagged that as a quiet rule violation rationalized inside the change itself, because `base-standards.md §2` explicitly says "introducing any of these requires explicit SPEC.md amendment." The fix is to amend SPEC.md, not to argue the exemption away inside `design.md`.

**Amendment landed in the same change set as this design** — see `SPEC.md` block "Excepción explícita — servicios de infraestructura" and `docs/base-standards.md §2` "Infrastructure carve-out". The microservices prohibition now applies to the application domain only (transactions, savings, billing, …); infrastructure services (n8n, this sidecar, Traefik, Uptime Kuma) live under `scripts/<infra-domain>/` and require explicit `design.md` justification per change. With the amendment in place, the sidecar is no longer a rule violation — it is the first formalized infrastructure-tier service under the carve-out.

**Justification mandated by the amendment:**

1. **The sidecar is NOT part of the application domain.** `base-standards.md §2`'s rule governs application code — the savings, transactions, and ingest bounded contexts. The sidecar is *infrastructure* (the operational analogue of `database/`, `monitoring/`, `traefik/`), not a new application service. Following the same principle that allows n8n itself to exist as a separate process, the backup runner sits alongside the application rather than inside it.
2. **The alternative (extend n8n) is worse.** Baking pg_dump/age/rclone/docker-cli into the n8n image couples backup-tool upgrade cadence to n8n upgrade cadence and gives the container hosting public webhooks the Docker socket. That is a strictly worse security and operational posture.
3. **The HTTP layer is intentionally minimal.** All backup logic stays in bash workers; the Node + Express layer authenticates, serialises with a mutex, spawns the worker, and streams its output. This keeps the deviation small: a future port to a different transport (Unix socket, named pipe, even a single binary) would touch only `runner/` files. The deviation is bounded.
4. **The operator (Andres) explicitly accepted this trade-off** when the design was proposed (operator decision 2026-05-13, recorded in `feedback_opsx_propose_ask_before_decide`). The acceptance is for v1; if/when the operator decides this is no longer worth maintaining, the entire `scripts/backup/runner/` subtree can be deleted and replaced with an alternative orchestration without affecting Spring Boot code.

Both M9 (earlier reviewer) and adversarial-review finding #2 (2026-05-13) are addressed: the architectural cost is real, formalized via the SPEC.md amendment (not via inline self-exemption), bounded to the `scripts/backup/` subtree, and does not propagate into the application domain.

**Container topology (on the operator's existing VPS):**

```
                  docker network: n8n_net (internal)
   ┌────────────────────────────────────────────────────────────┐
   │  ┌─────────┐                          ┌──────────────────┐ │
   │  │  n8n    │  HTTP POST X-Runner-     │  myfinance-      │ │
   │  │ vanilla │ ───Secret──────────────► │  backup-runner   │ │
   │  │         │  /run/daily              │  (Node+Express)  │ │
   │  └─────────┘  /run/preop              │                  │ │
   │       ▲       /run/verify             │  pg17 client     │ │
   │       │       GET /status, /healthz   │  age • rclone    │ │
   │       │                                │  docker CLI      │ │
   │       │                                └──────────────────┘ │
   │       │                                       │ │ │         │
   └───────┼───────────────────────────────────────┼─┼─┼─────────┘
           │                                       │ │ │
   docker network: traefik (external)              │ │ │
           │                                       │ │ │
   ┌───────┴───────┐                               │ │ │
   │   Traefik     │                               │ │ │
   │   (existing)  │                               │ │ │
   └───────────────┘                               │ │ │
           ▲                                       │ │ │
           │                                       ▼ ▼ ▼
        Internet               Supabase Pooler  /var/run/   R2 bucket
        (webhook)              (pg_dump)        docker.sock  (rclone)
                                                (verify ctr)
```

n8n keeps its existing membership in `n8n_net` and reachability through `traefik`. The runner joins **only** `n8n_net` — it does NOT expose ports through Traefik, so its HTTP API is reachable only from inside the Docker network (in practice, only from n8n). The pre-op webhook entrypoint remains on `n8n.datachefnow.com` (Traefik → n8n), and the n8n workflow node calls the runner internally.

**Runner image (`scripts/backup/Dockerfile.runner`):**

```Dockerfile
# Alpine 3.21 is the first release where the apk repo carries postgresql17-client.
# Pin this digest so a future node:22-alpine retag (e.g. base bumps to 3.22) cannot
# silently break the apk install. Bump deliberately when revisiting backups.
FROM node:22-alpine3.21
RUN apk add --no-cache \
      postgresql17-client \
      age \
      rclone \
      docker-cli \
      tar \
      gzip \
      curl \
      jq \
      bash \
      tini \
  && pg_dump --version | grep -E '^pg_dump \(PostgreSQL\) 17\.' \
  || (echo "pg_dump 17 not installed — abort build" && exit 1)
WORKDIR /app
COPY runner/package.json runner/package-lock.json ./
RUN npm ci --omit=dev
COPY runner/ ./
COPY workers/ /opt/myfinance-backup/workers/
COPY recipient.txt /opt/myfinance-backup/recipient.txt
RUN chmod +x /opt/myfinance-backup/workers/*.sh && chmod 0444 /opt/myfinance-backup/recipient.txt
ENTRYPOINT ["/sbin/tini","--"]
CMD ["node","server.js"]
```

The build itself fails fast if `pg_dump --version` is not 17.x — eliminates the silent "package missing → image builds → runtime breaks" path the adversarial review flagged. `recipient.txt` is read-only inside the image (mode `0444`) so a compromised runner cannot rewrite the recipient to one the attacker controls.

The PGDG Alpine package for Postgres 17 client is the version-matched client mandated by Decision 1.

**HTTP API contract:**

| Method | Path | Auth | Body | Success response | Notes |
|---|---|---|---|---|---|
| `GET` | `/healthz` | none | — | `200 {"status":"ok","version":"<sha>"}` | Liveness only. Returns 200 even when a run is in progress. |
| `GET` | `/status` | none | — | `200 {"lastSuccess":{...},"lastPreop":{...},"lastVerify":{...},"runInProgress":<bool>}` | Read from `/var/lib/myfinance-backup/status/*.json`. Used by `MyFinanceBackup-Watchdog`. |
| `POST` | `/run/daily` | `X-Runner-Secret` | — | `200 {"artefact":"daily/<file>","sha256":"...","durationMs":...}` | Synchronous. Sidecar handles weekly/monthly promotion + Kuma push internally. |
| `POST` | `/run/preop` | `X-Runner-Secret` | `{"reason":"<slug>"}` | `200 {"artefact":"pre-op/<file>","sha256":"...","verifyResult":{...}}` | Synchronous. Includes full restore-verify (Decision 9). Returns 500 + failing probe in body on verify failure. |
| `POST` | `/run/verify` | `X-Runner-Secret` | — | `200 {"artefactVerified":"daily/<file>","probes":[...]}` | Synchronous. Verifies the newest object in `daily/`. |

All non-`GET` endpoints require the header `X-Runner-Secret: $MYFINANCE_BACKUP_RUNNER_SECRET`. Missing/incorrect header returns HTTP 401 with no body. A second concurrent run-class request returns HTTP 409 — the runner serializes runs via an in-process mutex so two clobbering pg_dumps never run simultaneously.

**Server implementation (`scripts/backup/runner/server.js`):** thin Express layer that authenticates, enforces the mutex, spawns the relevant bash worker (`/opt/myfinance-backup/workers/*.sh`) with `child_process.spawn`, streams stdout/stderr into a per-run log file, and returns the worker's JSON exit payload. The HTTP layer is intentionally dumb; all backup logic stays in bash where it is portable, debuggable on the VPS, and aligned with the rest of the project's "scripts go in scripts/, business code goes in Java" convention.

**Mounted paths:**

- `/var/lib/myfinance-backup` → bind-mount on the VPS host. Persistent across container restarts. Holds `status/` JSON files, the `working/` directory for in-flight artefacts, and per-run log files under `logs/`.
- `/var/run/docker.sock` → bind-mount, read-write. Required so `verify-restore.sh` can spawn the ephemeral `postgres:17` container. Privilege scope is acknowledged in Risks; n8n itself does NOT mount this socket.
- `/var/lib/myfinance-verify` → **tmpfs declared at compose level** (`tmpfs: ["/var/lib/myfinance-verify:size=512m,mode=0700"]`), NOT a `mount -t tmpfs` call inside the container. Docker mounts it in the container namespace before the entrypoint runs, so no `CAP_SYS_ADMIN` is needed. The path is a **sibling** of `/var/lib/myfinance-backup` (not nested under it) to avoid Docker's nested-mount edge cases. Plaintext decrypted snapshots and `pg_restore` working files live ONLY here for the duration of a verify run. On the VPS host the same path exists with tmpfs backing, which the ephemeral `postgres:17` container bind-mounts read-only at `/backup` for the restore step.

**docker-compose extension (`scripts/backup/docker-compose.yml`):** to be merged with the existing n8n compose project via `docker compose -f n8n/docker-compose.yml -f scripts/backup/docker-compose.yml up -d`. Defines the `myfinance-backup-runner` service joined to `n8n_net`, mounts `/var/run/docker.sock`, mounts `/var/lib/myfinance-backup`, declares `restart: unless-stopped`, sets resource limits (256 MB RAM is enough — pg_dump streams into rclone), and reads secrets from environment variables sourced from the operator's `.env.local`.

**Security model:**

- `X-Runner-Secret` is a 32-char unguessable token shared between n8n and the runner. Stored in n8n as a credential; never logged by either side.
- The runner does NOT expose any port to the host network — only the in-cluster `n8n_net` reaches it. Reduces internet attack surface to zero.
- `docker.sock` access is the most privileged thing the runner holds; equivalent to root on the host. Mitigations: minimal image surface, no inbound internet exposure, and the worker scripts only ever spawn one image (`postgres:17`) with deterministic arguments. A future hardening pass could swap `docker.sock` for `docker-in-docker` or `sysbox`, deferred as a follow-up.
- Bash worker scripts run as a non-root user inside the runner; `docker` CLI is invoked via group membership rather than `sudo`.

**Alternatives considered:**

- **Single big bash script invoked via Execute Command in n8n:** rejected — see opening paragraph.
- **Python + Flask for the HTTP layer:** considered; rejected by operator preference for Node + Express to align mentally with the n8n stack. The HTTP layer is small enough that the language choice is not consequential for correctness; ergonomics dominate.
- **Long-polling job queue (BullMQ + Redis):** would let n8n fire-and-forget and poll status. Rejected as over-engineering for a one-operator system where the runs are infrequent and synchronous calls with a 5-min timeout in n8n work fine.

## Risks / Trade-offs

- **[VPS-wide outage → no backup AND no in-cluster alert]** → The VPS hosts n8n + sidecar + Kuma simultaneously, so a host outage silences all three alerting channels. **Mitigation accepted for v1:** the operator notices via the parallel Gmail-ingest workflow's Telegram silence within hours (typical day = 2–5 transactions arriving). An off-VPS dead-man-switch (healthchecks.io) was considered in v2 (M1 fix) and retroactively deferred in v3 (see Decision 7 trade-off). Re-evaluate after 90 days of v1 operation.
- **[Age identity on VPS during verify]** → The identity is read from n8n credentials, passed to the worker via stdin pipe, written to a tmpfs file with mode `0600` for the lifetime of `age -d -i`, then wiped by an EXIT trap (Decision 6 B7 fix). A VPS root compromise leaks the identity → attacker can decrypt all historical encrypted snapshots in R2. Mitigation: VPS hardening (SSH keys only, UFW, automatic security updates, fail2ban) — documented in `docs/development-guide.md` but enforcement is operator responsibility. Worst-case impact of VPS compromise: snapshots from before the rotation become readable to the attacker, BUT Supabase credentials are also on the VPS for ingest, so a VPS root compromise already implies live-DB access; the marginal historical-leak is small relative to the existing attack surface.
- **[VPS is a single trust zone — n8n + sidecar share credentials, finding #9 fix]** → The secrets in `.env.local` + n8n credentials are co-located on the same VPS. A VPS root compromise (acknowledged in the bullet above) leaks ALL of: `BACKUP_DB_PASSWORD`, `BACKUP_R2_*`, `MYFINANCE_BACKUP_AGE_IDENTITY`, `MYFINANCE_BACKUP_RUNNER_SECRET`, the three alert URLs, AND grants ability to write new "trusted" snapshots. **Trust-boundary clarification:** the design does NOT claim defense-in-depth between the n8n container and the sidecar container — they share `n8n_net`, both can read each other's credentials if one is compromised via Node module supply-chain (the runner image) or via n8n's own attack surface (custom-node code injection). Treat the entire VPS as a single trust zone. Mitigations are *outside* the runner↔n8n split: VPS hardening, scope-of-token (R2 token scoped to a single bucket, Gmail App Password revocable). A future hardening path is moving `MYFINANCE_BACKUP_AGE_IDENTITY` to a hardware-backed credential (Yubikey, on-demand SSH agent forwarding from the operator's PC) so that decryption requires operator-physical-presence — deferred as a follow-up. Documented here so a future reader does not over-trust the runner↔n8n boundary.
- **[Age identity loss — UNRECOVERABLE if BOTH paper AND PC identity gone simultaneously]** → Single-recipient design (Decision 2). Mitigation: documented annual disaster-drill in `docs/development-guide.md` — the operator manually decrypts one snapshot with the paper identity once a year, confirms the paper is readable, re-prints if smudged or water-damaged. The snapshot bundle's `README.txt` documents the decryption procedure. Dual-recipient is a future-change option if the single-paper failure mode proves uncomfortable.
- **[Cloudflare R2 account compromise / bucket deletion → worst-case RPO ~30 days, finding #4 fix]** → Recovery relies on the manual monthly external-disk archive. **Honest worst-case Recovery Point Objective (RPO): up to 30 days** (the operator's calendar-driven monthly archive cadence). Reviewer Q3 asked what happens if Cloudflare suspends the account unilaterally: same answer — fall back to the external-disk archive. The operator accepted this as the realistic worst case (the alternative — an automated continuous off-Cloudflare mirror — would double cost and operational complexity for a low-probability scenario). **Caveat re: flyway-migrations §7 unblock (finding #4):** the proposal's "Unblocks" line should not be read as "this change makes flyway-migrations safe under R2-loss" — under R2-loss, the safety net for a recent migration is the pre-op snapshot (90-day R2 retention) PLUS the most recent monthly external-disk archive (≤30 days old by the operator's cadence). A bad migration discovered 25 days later, AFTER the monthly archive has rolled, is irrecoverable. Operationally this means flyway-migrations §7 is "safe to run AS LONG AS R2 is alive" — the operator MUST treat any R2-outage day as a no-migration day. Documented in `docs/development-guide.md` as part of the "Backup before any Supabase write" section.
- **[`pg_dump` against Supabase free tier hits connection limits during daily ingest]** → Schedule places the daily run at 02:30 America/Bogota when ingest is quiet; `pg_dump` uses one connection. The Hikari pool ceiling of 5 (`backend-runtime` spec) leaves headroom.
- **[Supabase Pooler hostname migration]** → If Supabase migrates the project to a new pooler hostname (the `aws-0-` prefix is not stable across infra refreshes), the daily `pg_dump` fails and alerts. **Mitigation:** `backup-daily.sh` performs a `pg_isready -h $BACKUP_DB_HOST` precheck before pg_dump, with a clear error message pointing to "Supabase Dashboard → Connect → Session pooler" so the operator can update `.env.local` quickly. Acceptable as a quarterly-event manual fix.
- **[Restore verification false-positive — dump restores but is logically broken]** → The query suite is the line of defence and is honest about its limits: probes are **smoke detectors**, not row-level integrity alarms (`verify-queries.sql` header documents this). Risk of "passes verification but a specific row was corrupted within the threshold" exists for any backup system; mitigation is to expand the probe suite as new domain invariants emerge (e.g. when savings goals lands, add `count(*) FROM myfinance.savings_goals ≥ 0`). A future improvement is to compare row counts against the *previous* successful verify (requires persistent state on R2), deferred to a follow-up.
- **[Pre-op procedure skipped in a hurry]** → The policy is documentation-enforced, not code-enforced (B6 fix — Decision 8). Mitigation: every relevant `/opsx:apply` task block starts with "verify pre-op backup taken in last 60 min" as task 0; the adversarial-review skill flags missing snapshot evidence. The operator accepted this as v1; long-term path is the CI-side migration runner enforcement when that ships.
- **[Gmail App Password leaks via n8n compromise]** → The credential is scoped to mail-sending only, can be revoked instantly from the Google Account UI, and is independent of the operator's primary Google password. Documented as a rotate-on-suspicion item.
- **[n8n workflow JSON drifts from the version in git]** → Operator edits the workflow in the UI, never re-exports. Mitigation: install script (`scripts/backup/install-n8n-workflows.sh`) re-imports from `scripts/backup/n8n/*.json` on every run and warns if the live workflow differs.
- **[All three alert channels fail simultaneously — silent failure]** → If ntfy.sh, Gmail SMTP, AND Kuma all fail on the same dispatch, the operator gets nothing. This is unlikely (three independent providers). Mitigations: each channel has independent uptime, and a verify-failed pre-op MUST quarantine the artefact regardless of whether alerts delivered — so the operator at least sees the quarantine state next time they read `status/last-preop.json`. The operator must ensure Kuma has at least one functional downstream notification channel; this is documented in the operator runbook.

## Migration Plan

This change introduces new artefacts only; no existing code or data changes. Deployment is sequential and reversible up to the first scheduled run:

1. **Generate one age key pair (Decision 2)** on the Windows PC:
   - Run `age-keygen -o $env:USERPROFILE\.config\myfinance-backup\age-identity-primary.txt`. Extract the recipient public key into `scripts/backup/recipients/primary.txt` (committed).
   - Print the identity, seal in an envelope, file at a secure physical location (operator's documented choice).
2. **Cloudflare R2 setup**: Operator generates the R2 API token in the Cloudflare dashboard scoped to `my-finance-view-backups`, configures the **five** bucket lifecycle rules (30/90/365/**90**/365 days for `daily/`/`weekly/`/`monthly/`/**`pre-op/`**/**`quarantine/`**), and records the account ID.
3. **Alerting credentials**: Operator generates a Gmail App Password, an unguessable ntfy.sh topic slug, and the runner shared-secret.
4. **Uptime Kuma**: Operator creates the Push Monitor in the existing Kuma UI (`MyFinance Daily Backup`, heartbeat 24 h, grace 6 h) and captures the resulting push URL. Wires at least one downstream notification channel inside Kuma (Telegram / Discord / Gmail SMTP / ntfy) and smoke-tests it with Kuma's "Test" button.
5. **Build the runner image**: Operator clones the repo on the VPS (or pulls if already present) and runs `docker compose -f n8n/docker-compose.yml -f scripts/backup/docker-compose.yml build` to build the `myfinance-backup-runner` image locally on the VPS. The build itself asserts `pg_dump --version` reports 17.x and that the recipient file exists.
6. **Populate `.env.local`** on the VPS (NOT committed) with the values from steps 1–4 plus the MyFinanceView Supabase DB password, then runs `docker compose ... up -d myfinance-backup-runner`.
7. **Import n8n workflows**: Operator imports the **five** n8n workflows from `scripts/backup/n8n/*.json` via the n8n UI Import feature (`Daily`, `PreOp`, `Watchdog`, `ErrorHandler`, `DispatchAlert`), then registers the n8n credentials: `MYFINANCE_BACKUP_AGE_IDENTITY` (identity for verify), `MYFINANCE_BACKUP_RUNNER_SECRET`, `MYFINANCE_BACKUP_NTFY_TOPIC`, `MYFINANCE_BACKUP_GMAIL_APP_PASSWORD`, `MYFINANCE_BACKUP_KUMA_PUSH_URL`.
8. **Smoke test**: Operator triggers `MyFinanceBackup-PreOp` manually from the n8n UI with `reason="initial-bootstrap"`; verifies the artefact lands in `pre-op/` on R2 AND that the full restore-verify completed (execution log shows `verifyResult.probes` all green).
9. **Trigger one daily cycle manually**: Operator runs `MyFinanceBackup-Daily` manually and confirms (a) artefact in `daily/`, (b) `last-success.json` updated, (c) `last-verify.json` updated with green probes, (d) Kuma monitor shows "Up".
10. **Activate schedules**: Operator sets the Schedule Triggers to Active on `MyFinanceBackup-Daily` and `MyFinanceBackup-Watchdog`. Waits one full 24-hour cycle and confirms the daily auto-run lands in R2 and Kuma reflects the success.
11. **Calendar reminder for monthly archive**: Operator sets a recurring monthly calendar reminder (e.g. 1st of each month at 10:00 BOG) with the exact `rclone copy r2:my-finance-view-backups/monthly/ E:\myfinance-backups-archive\` command as the reminder body. No n8n workflow is added for this (M11 operator decision — see Decision 4).
12. **Annual disaster drill**: Operator schedules an annual reminder to (a) decrypt one snapshot with the paper identity, (b) confirm the paper is still readable, (c) re-print if smudged or water-damaged.
13. The change is archived (`/opsx:archive`); memory `project_supabase_production_data` is updated to mark the policy live, unblocking `flyway-migrations` §7.

**Rollback:** Deactivating the n8n workflows disables the pipeline. The encrypted artefacts already in R2 are dead weight but harmless. The memory rule reverts to "no policy yet — block all Supabase writes" until a replacement lands.

## Open Questions

- **Cloudflare R2 jurisdiction (default vs EU vs FedRAMP)?** Defaulting to "default" (global). If the operator decides the data should stay in EU for regulatory reasons later, the only change is the rclone remote config and an R2 bucket migration.
- **Promote monthly archive to n8n reminder if calendar slips?** Currently a manual operator habit with calendar reminder (Decision 4, operator decision M11). If in practice the monthly archive slips past 2 months, promoting to an n8n Schedule Trigger that emails/pushes the reminder is a 30-min follow-up change.
- **Reinstate healthchecks.io after 90 days of v1 operation?** Decision 7 deferred the off-VPS dead-man-switch; if the Telegram-ingest silence signal proves insufficient (e.g. a multi-day VPS outage was missed during travel), promote to a follow-up change. Re-evaluate explicitly at the 90-day mark.
- **Reinstate pre-op webhook if non-interactive trigger becomes a need?** Decision 9 dropped the public webhook. If a CI-side migration runner ever needs to drive a pre-op snapshot from outside the n8n UI, adding the webhook back (with the dropped HMAC + IP-allowlist hardening) is a bounded follow-up.
- **Reinstate dual-recipient age + second paper if circumstances change?** Decision 2 dropped the recovery paper. If the operator acquires a multi-property setup, starts traveling frequently, or develops hostile-jurisdiction concerns, dual-recipient is the right upgrade.

## Resolved Questions

- **Threat model declared explicitly:** RESOLVED (2026-05-26 v3 retroactive Gate A application) — see `proposal.md ## Threat model`. Adversary is opportunistic cloud-storage breach + Supabase incident + operator fat-finger + VPS process failure. Out of scope: local forensic adversary, off-VPS outage detection, public-internet attacker against a webhook, TLS MITM, nation-state.
- **External dead-man-switch:** ADOPTED in v2 (2026-05-13 operator decision, M1 fix) THEN RETROACTIVELY DEFERRED in v3 (2026-05-26 Gate C application). Replaced by acceptance that operator notices via Telegram-ingest silence; revisit at 90-day mark. See Decision 7 and Open Questions.
- **Pre-op webhook public exposure:** RESOLVED in v2 (2026-05-13 operator decision, M3 fix — Traefik IP allowlist on top of static shared secret) THEN RETROACTIVELY REMOVED in v3 (2026-05-26 Gate C application — webhook entirely dropped, n8n-UI trigger only). See Decision 9.
- **Recovery age identity custody:** RESOLVED in v2 (2026-05-13 operator decision, B1 fix — second sealed paper at a different physical location, no digital copy) THEN RETROACTIVELY REDUCED in v3 (2026-05-26 Gate C application — single-recipient + single paper). See Decision 2.
- **Pre-op restore-verify depth:** RESOLVED — pre-op runs full restore-verify before returning OK (Decision 9), AND additionally performs a post-upload SHA-256 re-verify by re-downloading from R2 (M12 fix). "Uploaded + checksum" was too weak a guarantee for the safety-net snapshot of an irreversible operation.
- **age stdin handling:** RESOLVED (B7 fix) — earlier "Resolved" claim was wrong; the proposed `age -d -i /dev/stdin < ciphertext` is broken because stdin redirection consumes the ciphertext, not the identity. The correct mechanism is tmpfs-file path (`/var/lib/myfinance-verify/.identity` mode 0600, trap-deleted), with the identity arriving via the runner's child stdin pipe and being written to the tmpfs file by the worker before invoking `age -d -i`.
- **Process gate enforcement:** RESOLVED (2026-05-13 operator decision, B6 fix) — documentation only. No check script, no auto-gate in `/opsx:apply`. The "BREAKING — process only" wording in the earlier draft is removed; the gate is honestly described as operator discipline + checklist + adversarial-review backstop. See Decision 8.
- **Reason slug regex:** RESOLVED (2026-05-13 operator decision, B4 fix) — `^[A-Za-z0-9._+-]{3,60}$`. Kept in v3 because operator-typed input still benefits from filename-safety check. See Decision 9.
- **Pre-op retention:** RESOLVED (2026-05-13 operator decision, B5 fix) — 90 days for `pre-op/` (was 30). See Decision 3.
- **Verify cadence:** RESOLVED (2026-05-13 operator decision, reviewer Q2) — daily (chained inside `MyFinanceBackup-Daily`) instead of weekly Sunday. See Decision 5.

## Rejected findings (Gate C retroactive triage — 2026-05-26)

The following adversarial-review findings from v2 were RETROACTIVELY REJECTED in v3 after `proposal.md ## Threat model` declared their target adversary out of scope. Per `docs/workflow.md` §3, each rejection lists the one-line reason.

- **#1 — Forensic recovery of recovery age identity from NTFS journal / VSS / disk slack on the Windows PC.** Out of threat model: "local forensic adversary with physical disk access". The `cipher /W`, `vssadmin delete shadows`, dedicated genroom-folder, and Tails live-USB alternative are all defenses against this adversary; all dropped together with the recovery identity itself.
- **#18 — Printer-safety guidance (avoid network printers, power-cycle, handwrite from screen).** Out of threat model: same forensic adversary. Operator prints the single identity once, files at home, accepts standard residential printer hygiene.
- **B1 — Dual-recipient age encryption (defends against simultaneous PC + single-paper loss).** Out of threat model: scenario requires a regional disaster (fire, flood, theft from one location) destroying both copies. For v1, the monthly external-disk archive (Decision 4) is the tertiary copy of last resort.
- **M1 — Off-VPS dead-man-switch via healthchecks.io (defends against whole-VPS outage going silent).** Out of threat model: whole-VPS outage detection is deferred because the parallel Gmail-ingest workflow on the same VPS produces a Telegram-silence signal the operator notices within hours. Revisit at 90 days.
- **M3 — Traefik IP allowlist on the pre-op webhook (defends against public-internet attacker + secret-leakage replay).** Out of threat model: the public webhook itself is now dropped (n8n-UI trigger only), so there is no internet-facing surface to allowlist.
- **B4 — Permissive reason-slug regex (defends against pre-op webhook callers using uppercase or punctuation slugs).** Half-rejected, half-kept: the regex is kept (the n8n UI workflow still validates the typed `reason` for filename safety), but the HTTP-400 response body with detailed `regex`/`got`/`example_accepted` fields is dropped — the n8n execution log shows the error inline; no external caller to coach.
