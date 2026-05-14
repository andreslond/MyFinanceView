## ADDED Requirements

### Requirement: Scheduled encrypted snapshot of the myfinance schema

The system SHALL produce a daily snapshot of the Supabase Postgres remote covering the `myfinance` schema and the entire `auth.users` table (finding #16 clarification — `pg_dump -t auth.users` captures the full table, not only FK-referenced rows; for the single-user system this is one row and the distinction is moot, but the spec wording is now honest), using `pg_dump --format=custom --compress=9`. The two dump files (`myfinance.dump`, `auth-users.dump`) plus a `README.txt` SHALL be bundled into a single `.tar` and encrypted at rest with `age` against TWO project-owned recipients (primary + recovery) in a single encryption pass before being written to any persistent location. The resulting artefact MUST be named `YYYY-MM-DD.tar.age` and uploaded to the `daily/` prefix of the primary cloud bucket on Cloudflare R2. The daily workflow SHALL synchronously chain into the Automated restore verification Requirement before returning success; a daily snapshot whose verification fails MUST be quarantined (see Quarantine routing Requirement) and reported as a failed run.

#### Scenario: Daily snapshot completes against Supabase remote

- **WHEN** the n8n workflow `MyFinanceBackup-Daily` runs at 02:30 America/Bogota with valid Supabase credentials, R2 credentials, and both age recipients configured
- **THEN** the workflow completes successfully, a file matching `daily/YYYY-MM-DD.tar.age` exists in the `my-finance-view-backups` R2 bucket, `status/last-success.json` on R2 records the artefact path, size, SHA-256, and ISO-8601 UTC timestamp, AND `status/last-verify.json` on R2 records green probes against the just-uploaded snapshot (daily verify chain, reviewer Q2 fix)

#### Scenario: Snapshot bundles myfinance dump + auth.users data dump

- **WHEN** a daily snapshot is downloaded, decrypted, and inspected with `tar -tf` followed by `pg_restore --list` against each enclosed `.dump`
- **THEN** the tar contains exactly three files (`myfinance.dump`, `auth-users.dump`, `README.txt`); the `myfinance.dump` listing contains entries from the `myfinance` schema for every existing table; the `auth-users.dump` listing contains data section entries for `auth.users(id, …)`. The verify-restore procedure pre-creates a minimal `auth.users(id uuid PRIMARY KEY)` stub before `pg_restore --data-only` of the auxiliary dump (B2 fix — previous draft assumed `pg_dump -t auth.users -Fc` would also emit `CREATE SCHEMA auth`, which it does NOT; the stub is therefore created explicitly by verify-restore.sh).

#### Scenario: Restore order is auth-stub-first, myfinance-second, on vanilla postgres:17

- **WHEN** `verify-restore.sh` invokes the restore sequence against an ephemeral `postgres:17` container with no prior schema
- **THEN** the script first runs `psql -c "CREATE SCHEMA IF NOT EXISTS auth; CREATE TABLE IF NOT EXISTS auth.users (id uuid PRIMARY KEY);"`, then `pg_restore --data-only --table=users -Fc auth-users.dump` to load the id column rows, then `pg_restore -Fc myfinance.dump` whose foreign keys to `auth.users(id)` now resolve. The resulting database contains both `auth.users` rows and `myfinance.transactions` rows with no orphan rows and no broken FKs.

#### Scenario: Snapshot does not include the public schema

- **WHEN** a daily snapshot is downloaded, decrypted, and inspected with `pg_restore --list`
- **THEN** no entries from the `public` schema are present

#### Scenario: Snapshot is encrypted at rest

- **WHEN** any persisted snapshot file is examined on R2 (any prefix) or on the VPS working directory
- **THEN** the filename ends in `.age` and the first bytes of the file match the `age` format magic `age-encryption.org/v1`

#### Scenario: Snapshot is encrypted against both primary and recovery recipients

- **WHEN** an encrypted snapshot from R2 is inspected with `age -d --identity recovery-paper.txt < snapshot.tar.age` using the recovery identity alone (no primary identity available)
- **THEN** decryption succeeds end-to-end and the decrypted `.tar` is byte-identical to the snapshot produced by decryption with the primary identity alone (B1 fix — either custody chain alone is sufficient to recover historic snapshots)

#### Scenario: VPS working copy is deleted after R2 upload is verified

- **WHEN** the daily script finishes uploading to R2, `rclone check` confirms parity between local and remote, AND the post-upload SHA-256 re-verify (re-downloaded from R2) matches the pre-upload SHA-256
- **THEN** the working file under `/var/lib/myfinance-backup/working/` is deleted before the script proceeds to verify-restore

### Requirement: Manual on-demand pre-operation snapshot

The system SHALL provide a manual procedure that produces a labelled snapshot before any write operation against Supabase remote. The procedure SHALL be invokable via two paths: (a) an HTTP POST to the n8n webhook `/webhook/myfinance-backup-preop` carrying a JSON body `{"reason":"<slug>"}` and an `X-Webhook-Secret` header (subject to the Traefik IP allowlist — see Pre-op webhook source-IP allowlist Requirement), and (b) manual execution of the `MyFinanceBackup-PreOp` workflow from the n8n UI with `reason` supplied via test data. Both paths MUST refuse to proceed when `reason` is absent or fails the regex `^[A-Za-z0-9._+-]{3,60}$` (B4 fix — previous strict lower-kebab regex rejected reasonable slugs like `Flyway-Baseline` and `v4.1-migration`). The artefact SHALL be named `YYYY-MM-DDTHH-MM-SSZ-<reason>.tar.age` (UTC timestamp) and uploaded to the `pre-op/` prefix of the R2 bucket. After upload, the procedure SHALL re-download the just-uploaded object from R2 and compare its SHA-256 to the pre-upload SHA-256; on mismatch the artefact MUST be moved to `quarantine/` and the call MUST fail with HTTP 500 (M12 fix). The procedure SHALL additionally run a full restore-verify against the just-uploaded artefact (same algorithm as the Automated restore verification Requirement) BEFORE returning success to the caller; if restore-verify fails the procedure MUST move the artefact to `quarantine/`, return HTTP 500 with the failing probe in the response body, MUST NOT update `status/last-preop.json` on R2, and MUST dispatch alerts via all four configured channels.

#### Scenario: Pre-op webhook rejects missing reason

- **WHEN** a `POST` to `/webhook/myfinance-backup-preop` is made with a valid secret, from an allowlisted IP, but body `{}`
- **THEN** the response status is 400, no snapshot is produced, and the response body is `{"error":"reason_required","example_accepted":"flyway-baseline"}`

#### Scenario: Pre-op webhook rejects invalid reason slug with actionable error

- **WHEN** a `POST` to `/webhook/myfinance-backup-preop` is made with body `{"reason":"Flyway Baseline!"}` (contains a space and a bang)
- **THEN** the response status is 400 and the response body is `{"error":"invalid_reason","regex":"^[A-Za-z0-9._+-]{3,60}$","got":"Flyway Baseline!","example_accepted":"flyway-baseline","example_accepted_2":"v4.1-migration"}` — including a concrete accepted example so a panicked operator can immediately correct the input (B4 fix — addresses reviewer's "useful error" Blocker requirement)

#### Scenario: Pre-op webhook accepts upper/lower/digits/dot/plus/hyphen/underscore

- **WHEN** a `POST` to `/webhook/myfinance-backup-preop` is made with body `{"reason":"v4.1-migration_attempt2"}` and a valid secret from an allowlisted IP
- **THEN** the request is accepted, the snapshot pipeline starts, and the eventual artefact name contains the literal slug `v4.1-migration_attempt2`

#### Scenario: Pre-op webhook rejects missing or wrong secret

- **WHEN** a `POST` to `/webhook/myfinance-backup-preop` is made from an allowlisted IP without `X-Webhook-Secret` or with an incorrect value
- **THEN** the response status is 401 and no snapshot is produced

#### Scenario: Pre-op webhook rejects requests from non-allowlisted IPs (M3 fix)

- **WHEN** a `POST` to `/webhook/myfinance-backup-preop` is made from an IP address NOT in the Traefik IP allowlist, regardless of whether the secret is correct
- **THEN** Traefik returns HTTP 403 before the request reaches n8n; no n8n worker is occupied, no Supabase pg_dump is triggered, and the operator-side webhook DoS exposure is eliminated

#### Scenario: Pre-op snapshot uploads, re-verifies, AND passes restore-verify

- **WHEN** the pre-op workflow runs successfully with `reason="flyway-baseline"`, the post-upload R2 SHA-256 matches the pre-upload SHA-256, AND the full restore-verify completes green
- **THEN** a file matching `pre-op/<UTC-timestamp>-flyway-baseline.tar.age` exists in the R2 bucket, its SHA-256 is recorded in `status/last-preop.json` on R2 alongside the probe results, and the webhook response body is `{"artefact":"pre-op/<filename>","sha256":"<hash>","verifyResult":{"probes":[...]}}` with HTTP 200

#### Scenario: Pre-op fails loudly when post-upload SHA-256 mismatches (M12 fix)

- **WHEN** the pre-op pipeline computes one SHA-256 locally before upload AND a different SHA-256 from a re-download of the just-uploaded object on R2
- **THEN** the artefact is moved (server-side) from `pre-op/` to `quarantine/<timestamp>-pre-op-<original-name>`, `status/last-preop.json` is NOT updated, the webhook response status is 500 with body `{"error":"upload_corrupted","local_sha256":"…","r2_sha256":"…","quarantined_to":"quarantine/…"}`, and all four alert channels (ntfy + Gmail + Kuma-non-push-as-implicit + healthchecks.io-non-ping-as-implicit) fire

#### Scenario: Pre-op fails loudly when restore-verify rejects the snapshot

- **WHEN** the pre-op artefact uploads successfully, the SHA-256 re-verify matches, but the subsequent restore-verify finds `myfinance.transactions` row count below the configured threshold
- **THEN** the artefact is moved (server-side) from `pre-op/` to `quarantine/<timestamp>-pre-op-<original-name>` (M15 fix), the webhook response status is 500, the response body contains `{"error":"verify_failed","probe":{...},"quarantined_to":"quarantine/…"}`, `status/last-preop.json` is NOT updated, and explicit ntfy + Gmail alerts are dispatched

### Requirement: Encryption with two project-owned age recipients, dual-paper custody

The system SHALL encrypt every snapshot with `age` using TWO recipient public keys (primary + recovery) in a single multi-recipient encryption pass (`age -r "$(cat recipients/primary.txt)" -r "$(cat recipients/recovery.txt)" -o snapshot.tar.age snapshot.tar`). Both recipient files SHALL be committed to the repository at `scripts/backup/recipients/primary.txt` and `scripts/backup/recipients/recovery.txt`, both baked into the runner image at build time. The matching identities (private keys) MUST NOT be committed to the repository under any circumstance. Identity custody SHALL be:

- **Primary identity** — stored at (a) operator's Windows workstation `%USERPROFILE%\.config\myfinance-backup\age-identity-primary.txt` with NTFS ACLs restricted to the user account, AND (b) printed paper copy in a sealed envelope at physical location A.
- **Recovery identity** — stored at (a) printed paper copy in a sealed envelope at physical location B, geographically separated from location A. There SHALL be NO digital copy of the recovery identity at any time after generation; the recovery identity exists only on paper.

The n8n VPS SHALL hold ONLY the primary identity, and only as the n8n credential `MYFINANCE_BACKUP_AGE_IDENTITY`. The identity SHALL reach the bash verify worker via the runner's child `stdin` pipe (NOT via environment variable), be written to `/var/lib/myfinance-verify/.identity` with mode `0600` inside the compose-declared tmpfs, used by `age -d -i`, and unconditionally wiped by an EXIT trap before the worker terminates. The recovery identity SHALL NEVER appear on the VPS. The repository `.gitignore` MUST block any file matching `age-identity*`, `*.identity`, and any content beginning with `AGE-SECRET-KEY-`.

#### Scenario: Both recipient files are present and reference valid age public keys

- **WHEN** the developer inspects `scripts/backup/recipients/primary.txt` and `scripts/backup/recipients/recovery.txt`
- **THEN** both files exist, each contains exactly one line starting with `age1`, AND the two keys are different (primary ≠ recovery — guards against an accidental copy-paste of the same key into both files)

#### Scenario: Repository does not contain any age identity

- **WHEN** `git grep "AGE-SECRET-KEY-"` is executed against the working tree and history
- **THEN** no matches are found

#### Scenario: Either recipient alone can decrypt any snapshot

- **WHEN** an arbitrary snapshot from R2 is decrypted twice — once with only the primary identity, once with only the recovery identity — into separate temporary files
- **THEN** both decryptions succeed and the two resulting `.tar` files are byte-identical (B1 fix — proves the multi-recipient encryption is correctly set up; each custody chain is independently sufficient for recovery)

#### Scenario: Verify-restore reads primary identity from credential, never persists it to non-tmpfs

- **WHEN** the `verify-restore.sh` script runs to completion (success or failure)
- **THEN** no file matching `*identity*` or `*.identity` exists under `/var/lib/myfinance-backup/` or any other persistent (non-tmpfs) disk path on the VPS after the script exits; the identity existed only inside `/var/lib/myfinance-verify/.identity` (compose-declared tmpfs, mode 0600) for the duration of the `age -d -i` invocation, and was wiped by the EXIT trap

#### Scenario: Identity reaches the worker via stdin and a tmpfs file, never via environment (B7 fix)

- **WHEN** the Node runner spawns `verify-restore.sh` (directly or chained from `backup-preop.sh` / `backup-daily.sh`)
- **THEN** the child process's `/proc/<pid>/environ` contains NO key named `MYFINANCE_BACKUP_AGE_IDENTITY`; the runner spawns the worker with the identity scrubbed from the child env, writes the identity bytes onto the child's stdin pipe, and the worker reads stdin into a bash variable then writes that variable to `/var/lib/myfinance-verify/.identity` with `umask 0177` so the file is created mode `0600`. The previous draft's `age -d -i /dev/stdin < snapshot.tar.age` invocation is REJECTED by this Scenario because the `< ciphertext` redirection replaces stdin and starves age of the identity — the spec mandates the tmpfs-file path for `-i`

#### Scenario: Recovery identity never appears on the VPS

- **WHEN** the operator audits the n8n credentials list AND the runner container's mounted volumes AND `/var/lib/myfinance-backup/`, `/var/lib/myfinance-verify/` after a verify run
- **THEN** no occurrence of the recovery identity is found anywhere on the VPS. Recovery operations are performed off-VPS, on a clean machine, by the operator typing the paper identity by hand

### Requirement: Primary cloud destination on Cloudflare R2

The system SHALL upload every snapshot to a primary cloud bucket hosted on Cloudflare R2 with bucket name `my-finance-view-backups`. The bucket SHALL be accessed via `rclone` using an S3-compatible remote named `r2` configured with provider `Cloudflare`. R2 credentials and the Cloudflare account ID MUST be stored as n8n credentials under the names `BACKUP_R2_ACCESS_KEY_ID`, `BACKUP_R2_SECRET_ACCESS_KEY`, and `BACKUP_R2_ACCOUNT_ID`, and surfaced as placeholders in `.env.example`. The credentials MUST NOT be committed to the repository.

#### Scenario: rclone remote is defined and points to Cloudflare R2

- **WHEN** the operator runs `rclone listremotes` on the VPS
- **THEN** the output contains `r2:` and the underlying endpoint matches `https://<account_id>.r2.cloudflarestorage.com` (or the equivalent jurisdiction-qualified endpoint)

#### Scenario: rclone is configured with provider = Cloudflare for server-side copy

- **WHEN** the operator inspects the rclone config (`rclone config show r2`) on the VPS
- **THEN** the `[r2]` section contains exactly `provider = Cloudflare` — without this line, `rclone copyto` between R2 prefixes (used for daily→weekly/monthly promotion) falls back to download+reupload, which incurs egress and breaks the zero-egress cost model the change relies on

#### Scenario: Sunday daily-to-weekly promotion uses server-side copy

- **WHEN** the daily workflow runs on a Sunday and executes `rclone -vv copyto r2:my-finance-view-backups/daily/<file> r2:my-finance-view-backups/weekly/<file>`
- **THEN** the resulting promotion does NOT generate egress traffic at the runner's network interface (measured by comparing `rx_bytes`/`tx_bytes` of the runner container before/after the copy — server-side copy means data flows entirely inside R2, no bytes traverse the runner). M6 fix: the previous Scenario asserted a specific rclone log substring (`server-side copy`/`Server side copies are enabled`) which is brittle across rclone versions; the network-byte assertion captures the actual cost concern (no egress) and is version-stable

#### Scenario: R2 API token is scoped to the backup bucket only

- **WHEN** the operator inspects the R2 API token used by the backup workflows
- **THEN** the token's permissions are `Object Read & Write` and its bucket scope is `my-finance-view-backups`, with no broader scope

### Requirement: Retention policy enforced bucket-side via R2 lifecycle rules

The system SHALL define R2 Object Lifecycle Policies on the `my-finance-view-backups` bucket so that the workflow code is not the source of truth for retention. The required lifecycle is: `daily/` retains 30 days, `weekly/` retains 90 days, `monthly/` retains 365 days, **`pre-op/` retains 90 days** (B5 fix — was 30, raised to cover slow-discovery migration bugs), **`quarantine/` retains 365 days** (M15 fix — verify-failed artefacts retained long enough for forensic inspection). Promotion of daily artefacts into `weekly/` and `monthly/` SHALL be performed by the daily workflow on Sundays and on the first day of each month respectively, by server-side copy via `rclone copyto`.

#### Scenario: Lifecycle rules match the documented retention

- **WHEN** the operator inspects the bucket lifecycle configuration in the Cloudflare R2 dashboard or via API
- **THEN** there is one rule per prefix `daily/`, `weekly/`, `monthly/`, `pre-op/`, `quarantine/` and the configured day counts equal 30, 90, 365, **90**, **365** respectively

#### Scenario: Sunday daily run promotes a weekly copy

- **WHEN** the daily n8n workflow runs on a Sunday and the upload step succeeds
- **THEN** a server-side `rclone copyto` of the just-uploaded `daily/YYYY-MM-DD.tar.age` creates `weekly/YYYY-Www.tar.age` where the ISO week matches the date

#### Scenario: First-of-month daily run promotes a monthly copy

- **WHEN** the daily n8n workflow runs on the first day of a calendar month and the upload step succeeds
- **THEN** a server-side `rclone copyto` creates `monthly/YYYY-MM.tar.age`

#### Scenario: R2 transient outage during status upsert is fatal to the run (finding #14 fix)

- **WHEN** `backup-daily.sh` completes pg_dump + tar + age + upload + sha256 re-verify + chained verify GREEN, and then `status/last-success.json` upsert (step 4.3.10) returns a non-2xx from R2 (e.g. transient 5xx, network timeout)
- **THEN** the worker MUST treat the status-upsert failure as a fatal step: log the failure, dispatch the ntfy + Gmail alert, AND exit non-zero. The Kuma success push (step 4.3.12) and healthchecks.io success ping (step 4.3.13) MUST NOT be sent on the failure path. Rationale: a green verify with a failed status-write produces an artefact in R2 that the watchdog cannot discover the next morning — the watchdog reads `status/last-success.json` and finds it stale, triggering a false-positive "STALE" alert. Treating the upsert as fatal collapses the failure into the existing alert path (operator sees one explicit alert, no surprise "STALE" the next day). The dead-man-switches (Kuma + healthchecks.io) correctly do NOT receive the success heartbeat, so they will also fire after grace — multiple signals, all consistent

#### Scenario: Same-day re-run of daily workflow is idempotent

- **WHEN** the daily workflow runs successfully on a Sunday (or 1st-of-month) AND is then re-triggered manually the same day before midnight (e.g. operator forced a re-run after fixing an unrelated issue)
- **THEN** the resulting `daily/YYYY-MM-DD.tar.age` and `weekly/YYYY-Www.tar.age` (and `monthly/YYYY-MM.tar.age` if applicable) objects are overwritten in place by `rclone copyto` — no orphan files, no duplicated objects with timestamp suffixes, and the post-state of R2 is observationally identical to a fresh run on that date (M15 fix — promotion is idempotent)

### Requirement: Automated restore verification chained after every snapshot

The system SHALL provide an automated restore-verification workflow that runs **immediately after every daily snapshot AND every pre-op snapshot** (chained in-process inside the runner, not on an independent schedule — reviewer Q2 fix). The workflow MUST: (a) accept an explicit `--target <r2-path>` argument (used for chained pre-op verification of the just-uploaded artefact), defaulting to the newest object under `daily/` for standalone runs; (b) download the target into a `tmpfs`-backed working directory on the VPS; (c) write the primary age identity (received via stdin from the runner) to a tmpfs file with mode `0600` and decrypt with `age -d -i <path>`; (d) pre-create `auth` schema and stub `auth.users(id uuid PRIMARY KEY)` on the ephemeral Postgres (B2 fix); (e) start an ephemeral `postgres:17` Docker container with a UUID-derived name (M16 fix); (f) wait for Docker DNS resolution AND `pg_isready` (M17 fix); (g) restore the auxiliary `auth-users.dump` as `--data-only` followed by `myfinance.dump` full restore; (h) execute the SQL probe suite (does NOT include any time-since-last-transaction probe — B3 fix); (i) tear the container down regardless of outcome; (j) wipe the tmpfs identity file via EXIT trap; (k) exit non-zero if any step fails or any probe returns an out-of-range value.

#### Scenario: Daily-chained verification succeeds against a healthy snapshot

- **WHEN** `backup-daily.sh` completes upload + sha256 re-verify and chains to `verify-restore.sh --target daily/<just-uploaded>`
- **THEN** verify-restore exits 0, the ephemeral container is removed, the tmpfs identity file is wiped, and `status/last-verify.json` on R2 records the verified artefact path, UTC timestamp, and all probe results

#### Scenario: Verification fails when transactions row count is implausible

- **WHEN** the verification runs against a snapshot where `myfinance.transactions` returns fewer than 300 rows
- **THEN** the script exits non-zero, `status/last-verify.json` records the failing probe, the artefact is moved to `quarantine/`, and all four alert channels engage (ntfy + Gmail explicit fire; Kuma + healthchecks.io receive no success heartbeat for the daily run)

#### Scenario: Verification probe suite does NOT include latest_transaction_age_days (B3 fix)

- **WHEN** the developer inspects `scripts/backup/verify-queries.sql`
- **THEN** the file does NOT contain any query of the form `EXTRACT(EPOCH FROM (now() - max(occurred_at)))` against `myfinance.transactions`. That signal — "transactions have been ingested recently" — is operator-business-state, not backup-integrity, and conflating the two would produce false alerts during quiet weeks / vacations and would block pre-op snapshots during those periods. Recency is a separate ingest-health concern, deliberately out of scope for this Requirement

#### Scenario: Verification pre-creates auth schema and stub before restore (B2 fix)

- **WHEN** `verify-restore.sh` reaches the restore step against the fresh ephemeral `postgres:17` (which has no `auth` schema)
- **THEN** the script first executes `psql -c "CREATE SCHEMA IF NOT EXISTS auth; CREATE TABLE IF NOT EXISTS auth.users (id uuid PRIMARY KEY);"` and exits non-zero if that DDL fails; ONLY THEN runs `pg_restore --data-only --table=users -Fc auth-users.dump` followed by `pg_restore -Fc myfinance.dump`. The DDL of the production `auth.users` (which references Supabase-only `auth.*` tables) is INTENTIONALLY discarded — myfinance only needs the `id` column for FK satisfaction

#### Scenario: Verification cleans up the ephemeral container on failure

- **WHEN** any step of the verification fails after the container has started
- **THEN** the container is stopped and removed before the script exits (via a `trap` handler), and `docker ps -a` on the VPS shows no leftover container named `myfinance-verify-*`

#### Scenario: Container name uses UUID to avoid collisions (M16 fix)

- **WHEN** two verify runs are started simultaneously (rare — only possible when a pre-op-chained verify overlaps with a standalone `/run/verify` HTTP call, since the runner mutex covers HTTP entrypoints only)
- **THEN** the two ephemeral container names are different (UUID-derived suffix, NOT `$RANDOM` which has only 32768 distinct values and can collide); `docker run --name "$VERIFY_CONTAINER"` never fails with "name already in use"

#### Scenario: DNS resolution wait precedes pg_isready (M17 fix)

- **WHEN** `verify-restore.sh` spawns the ephemeral postgres and immediately needs to reach it by container name across `n8n_net`
- **THEN** the worker FIRST loops on `getent hosts "$VERIFY_CONTAINER"` (max 10 s) until success, THEN starts the `pg_isready` poll. A Docker DNS registration race that would have produced a misleading "Postgres didn't start" alert is correctly classified as "DNS not ready yet" and waited out

#### Scenario: Decrypted plaintext never persists to a non-tmpfs volume

- **WHEN** a verify run completes (success or failure) and the operator inspects the runner's persistent bind-mount `/var/lib/myfinance-backup/`
- **THEN** no `snapshot.tar`, no `*.dump`, no `*.identity`, and no `age-identity*` file is present; the only place the plaintext existed during the run was the compose-declared tmpfs at `/var/lib/myfinance-verify/`, which is wiped by the EXIT trap and is non-persistent across container restarts by definition

#### Scenario: Ephemeral postgres receives the dump via a shared bind-mount and shared network

- **WHEN** `verify-restore.sh` spawns the ephemeral `postgres:17` container
- **THEN** the spawn command MUST include `--network n8n_net` so the runner can reach it by container name, AND `-v /var/lib/myfinance-verify:/backup:ro` so `pg_restore` can read the decrypted dumps; `pg_isready` from the runner against the container's hostname returns 0 within 60 seconds (after the DNS wait above)

### Requirement: Quarantine routing for verify-failed artefacts (M15 fix)

The system SHALL maintain a separate R2 prefix `quarantine/` for snapshots that uploaded successfully (or were promoted successfully) but failed restore-verify. When any verify run rejects an artefact, the parent worker SHALL perform a server-side `rclone moveto` of the artefact from its original prefix (`daily/`, `pre-op/`, `weekly/`, `monthly/`) into `quarantine/<UTC-timestamp>-<source-prefix>-<original-filename>`. Status JSON files MUST point at the quarantine location after the move. The artefact MUST NOT remain under a "good" prefix where a future operator might mistake it for a valid snapshot.

#### Scenario: Verify-failed pre-op is moved to quarantine

- **WHEN** a pre-op snapshot uploads successfully to `pre-op/2026-05-13T15-00-00Z-flyway-baseline.tar.age` and the chained verify fails (e.g. transactions row count below threshold)
- **THEN** the artefact is moved (server-side) to `quarantine/2026-05-13T15-04-12Z-pre-op-2026-05-13T15-00-00Z-flyway-baseline.tar.age` BEFORE the pre-op endpoint returns; `pre-op/` no longer contains the failing artefact; `status/last-preop.json` is NOT updated; the response body contains `quarantined_to` pointing at the new location

#### Scenario: Verify-failed daily is moved to quarantine

- **WHEN** a daily snapshot uploads, sha256 re-verify passes, but the chained restore-verify fails
- **THEN** the artefact is moved server-side from `daily/` to `quarantine/<timestamp>-daily-<original-name>`; `status/last-success.json` is NOT updated (the daily run is recorded as failed); `status/last-verify.json` is updated with the failing probe; alerts fire

#### Scenario: Quarantine retention is 365 days

- **WHEN** the operator inspects the bucket lifecycle configuration for the `quarantine/` prefix
- **THEN** the configured day count is 365 — long enough for forensic inspection without growing the bucket unbounded

#### Scenario: Same-day quarantine-then-success is visible in status (finding #10 fix)

- **WHEN** a daily run produces a quarantined artefact (upload OK but verify failed → moved to `quarantine/`) AND a later same-day re-run succeeds with verify green and overwrites `daily/YYYY-MM-DD.tar.age` in place
- **THEN** `status/last-success.json` for the day MUST include the keys `previous_failed_attempts: <int >= 1>` and `quarantined_artefacts: ["quarantine/<timestamp>-daily-<file>", …]` listing the quarantined sibling(s). A reader who later inspects only `status/last-success.json` MUST be able to discover that the day had at least one failed attempt before the green success — without these fields, "green green green" in the status log would hide that something was wrong earlier in the day and the operator would never investigate the quarantined artefact

### Requirement: Four-channel alerting (ntfy.sh + Gmail SMTP + Uptime Kuma + healthchecks.io)

The system SHALL dispatch alerts on every failure path through FOUR independent channels:

1. **Explicit-dispatch from inside the VPS** — ntfy.sh push + Gmail SMTP email, both fired in parallel by the worker as long as the runner has outbound internet. Triggered when (a) the daily backup script exits non-zero, (b) the restore-verify script exits non-zero, (c) the watchdog detects stale `status/last-success.json` or `status/last-verify.json`, or (d) the n8n `MyFinanceBackup-ErrorHandler` fires.
2. **In-cluster dead-man-switch** — Uptime Kuma Push Monitor. The daily worker pings the Kuma push URL on success; the absence of a ping (heartbeat 24 h + grace 6 h = 30 h) triggers Kuma to fire its own alerts via channels wired inside Kuma. Catches the case where the workflow itself never ran.
3. **Off-VPS dead-man-switch (M1 fix)** — healthchecks.io free tier. The daily worker additionally pings the healthchecks.io check URL on success; the absence of a ping triggers healthchecks.io to fire alerts (email mandatory, optional Slack/Discord) — from infrastructure the operator does not own. Catches the case where the entire VPS is down (which would also silence Kuma).

The ntfy topic SHALL be stored as n8n credential `MYFINANCE_BACKUP_NTFY_TOPIC`. The Gmail App Password SHALL be stored as n8n credential `MYFINANCE_BACKUP_GMAIL_APP_PASSWORD`. The Kuma push URL SHALL be stored as `MYFINANCE_BACKUP_KUMA_PUSH_URL`. The healthchecks.io check URL SHALL be stored as `MYFINANCE_BACKUP_HEALTHCHECKS_URL`. None of these URLs/tokens MAY be committed to the repository (each carries an unguessable secret). Successful runs SHALL NOT dispatch any explicit-channel alert; they push Kuma and ping healthchecks.io.

#### Scenario: Failed daily run dispatches both channels

- **WHEN** `backup-supabase.sh` exits non-zero inside the n8n workflow
- **THEN** a single `POST` to `https://ntfy.sh/<topic>` is made with title `MyFinance backup FAILED` and a body containing the last 20 lines of the log, AND a single email is sent via Gmail SMTP from `aftorresl01@gmail.com` to `aftorresl01@gmail.com` with the same subject and the full log

#### Scenario: Watchdog fires when daily run is stale

- **WHEN** the `MyFinanceBackup-Watchdog` workflow calls `GET /status` on the runner and the response indicates `lastSuccess` is null OR `(now - lastSuccess.timestamp) > 30 hours` (the runner's `/status` reads R2 `status/last-success.json` as the canonical source — M14 fix)
- **THEN** the workflow invokes the shared Dispatch Alert sub-workflow with title `MyFinance backup STALE`, dispatching both an ntfy `POST` and an email

#### Scenario: Successful run does not alert

- **WHEN** `backup-supabase.sh` exits 0
- **THEN** no `POST` to `ntfy.sh` is made, no email is sent, and `status/status.log` on R2 gains exactly one new line recording the success

#### Scenario: ntfy outage does not block the email

- **WHEN** a failure occurs and the `ntfy.sh` POST fails (e.g. HTTP 5xx or network timeout)
- **THEN** the email is still dispatched, the n8n workflow records both attempts, and the overall workflow status reflects the alert delivery state

#### Scenario: Explicit channels failed — Kuma and healthchecks.io still fire on the dead-man path

- **WHEN** a backup run fails AND both the ntfy POST and the Gmail SMTP send fail in the same alert dispatch
- **THEN** the worker still exits non-zero (the daily run is recorded as failed); no Kuma success push is emitted; no healthchecks.io success ping is emitted; Kuma's 30 h grace elapses and fires its own alert via channels wired inside Kuma; healthchecks.io's grace elapses and fires its own alert via its operator-configured integrations. Either dead-man channel reaches the operator independently of ntfy/Gmail and independently of the VPS staying up

#### Scenario: VPS-wide outage — only healthchecks.io fires (M1 fix)

- **WHEN** the entire VPS hosting n8n + the sidecar runner + Uptime Kuma goes offline (e.g. Hetzner outage, kernel panic, disk full, unattended-upgrades reboot loop)
- **THEN** no ntfy POST, no Gmail send, no Kuma success push, and no healthchecks.io success ping happen. Kuma is offline so its grace-period alert never fires either. **healthchecks.io detects the missing ping from its off-VPS vantage point and fires the operator-configured alert** — this is the only channel that survives a VPS-wide outage, justifying the M1 fix

#### Scenario: Successful daily run pushes Kuma AND pings healthchecks.io

- **WHEN** `backup-daily.sh` completes all steps including chained restore-verify with exit 0
- **THEN** the worker issues exactly one `GET <MYFINANCE_BACKUP_KUMA_PUSH_URL>?status=up&msg=ok&ping=<elapsed_ms>` AND exactly one `GET <MYFINANCE_BACKUP_HEALTHCHECKS_URL>` (or `<healthchecks_url>/<elapsed_seconds>` for elapsed-time encoding). Both pings are treated as fire-and-forget (non-2xx responses are logged but do not fail the run, since the backup itself already succeeded by this point)

#### Scenario: SMTP credential is pinned to Gmail STARTTLS

- **WHEN** the operator inspects the n8n SMTP credential used by the alerting sub-workflow
- **THEN** the configured fields are exactly `host=smtp.gmail.com`, `port=587`, `secure=false` (STARTTLS upgrade), `user=aftorresl01@gmail.com`, `password=<MYFINANCE_BACKUP_GMAIL_APP_PASSWORD>` (M13 fix — under-specified credential shape is closed by pinning these four fields)

### Requirement: Process gate is documentation-only (B6 fix — not BREAKING, not enforced)

This Requirement codifies the gate that all Supabase writes are expected to wait on this backup policy. **Enforcement is non-technical and this is an accepted operator decision for v1 (2026-05-13).** Specifically, the system SHALL document, in `docs/development-guide.md`, that any write operation against Supabase remote (Flyway `migrate`/`baseline`/`repair`/`clean`, ad-hoc DDL or DML via `psql` or the Supabase SQL editor, MCP `apply_migration`, MCP `execute_sql`) is expected to be preceded by either (a) a successful pre-op snapshot taken within the previous 60 minutes, or (b) a successful daily snapshot recorded in `status/last-success.json` within the previous 24 hours. The OpenSpec auto-memory `project_supabase_production_data.md` MUST be updated to cross-link to `supabase-backup-policy` and to state this expectation explicitly.

There is NO build-time, CI-time, or runtime check that prevents a Supabase write when the freshness file is stale. The earlier draft labelled this Requirement BREAKING — that wording is REMOVED. The gate is honoured by operator discipline, by the `/opsx:apply` workflow checklist, by `openspec/templates/supabase-write-checklist.md` (which future changes touching Supabase append to their `tasks.md` as task 0), and by the `adversarial-review` skill flagging missing snapshot evidence. If/when a CI-side migration runner is introduced (a future change scope, not this one), the runner SHALL refuse to run when `status/last-success.json` is older than 24 h — at that point this Requirement evolves from "documentation-only" to "code-enforced". Until then the SHALL is honest about its scope (documents exist) rather than implying a check that does not exist.

#### Scenario: Development guide documents the expectation, NOT a BREAKING gate

- **WHEN** a reader opens `docs/development-guide.md`
- **THEN** the document contains a section titled "Backup before any Supabase write" that lists the operations covered (Flyway migrate/baseline/repair/clean, ad-hoc psql DDL/DML, MCP apply_migration, MCP execute_sql) and the freshness windows (24 h daily, 60 min pre-op). The section uses the words "expected" / "should" / "operator discipline", NOT "blocked" / "refuses" / "enforced" — the documentation accurately reflects that nothing in code actively prevents a stale-state write

#### Scenario: Memory cross-links to this policy (documented expectation, NOT spec-validated — finding #15 fix)

- **WHEN** a reader opens the auto-memory file `project_supabase_production_data.md`
- **THEN** the body **is expected to** contain a `[[supabase-backup-policy]]` link and the explicit "24 h daily or 60 min pre-op" freshness expectation, framed as operator discipline rather than as an enforced gate. **Note (finding #15):** auto-memory is user-scoped (lives at `~/.claude/projects/<project-slug>/memory/`), NOT project-scoped or checked into the repo. `openspec validate supabase-backup-policy --strict` cannot verify this Scenario. It is therefore a documented expectation listed in tasks 11.5 and 12.5 (operator action during `/opsx:archive`), and the redundancy in `docs/development-guide.md` (which IS in the repo and IS validatable) is the authoritative project-side record. This Scenario remains in the spec for human readability — a future maintainer should know that the cross-link belongs in memory — but it is intentionally non-binding for tooling

#### Scenario: Reusable checklist template is committed

- **WHEN** a developer authors a new OpenSpec change that touches Supabase remote
- **THEN** they can copy `openspec/templates/supabase-write-checklist.md` as task 0 of their `tasks.md`; the template lists the freshness check (read `status/last-success.json` from R2, confirm < 24 h old, or run a pre-op via the webhook)

### Requirement: Recurring disaster-drill cadence (finding #5)

A backup policy whose restore procedure has only been exercised once at bootstrap decays over time — papers smudge, ink fades, envelopes get misfiled, the operator changes residence, and the recovery path silently rots. To prevent this, the system SHALL require a documented **disaster drill at least every 12 months**, the result of which is recorded so the freshness of the recovery path can be observed.

Each drill MUST cover: (a) retrieving the recovery paper from physical location B and visually confirming it is still legible; (b) on a clean machine (NOT the operator's primary PC, NOT the VPS — Tails / Ubuntu live USB or a fresh container is acceptable), typing the recovery identity by hand and decrypting one historical snapshot end-to-end; (c) confirming the primary paper at physical location A is also intact (visual inspection + optionally a second decrypt drill with the primary identity); (d) re-printing either paper if smudged, water-damaged, or otherwise degraded.

The drill outcome SHALL be recorded by writing a JSON blob to `r2://my-finance-view-backups/status/last-drill.json` containing `{"timestamp":"<ISO-8601 UTC>","papers_intact":{"primary":<bool>,"recovery":<bool>},"reprinted":["primary"|"recovery"|null],"snapshot_used":"<r2-path>","operator_initial":"<AT>"}`. The watchdog SHALL include a stale-drill probe: if `last-drill.json` is missing OR its timestamp is older than 400 days (12-month cadence + 35-day grace), the watchdog SHALL dispatch a `MyFinance backup drill OVERDUE` alert via the shared Dispatch Alert sub-workflow. The overdue alert is a low-severity reminder, NOT a daily-run failure — the daily pipeline continues to run, but the operator is reminded that the recovery path has not been exercised within the cadence.

#### Scenario: status/last-drill.json is upserted after each drill

- **WHEN** the operator completes a drill and uploads the JSON blob via `rclone copy` or `aws s3 cp` to `r2://my-finance-view-backups/status/last-drill.json`
- **THEN** the file is present at that path with the required keys (`timestamp`, `papers_intact.primary`, `papers_intact.recovery`, `reprinted`, `snapshot_used`, `operator_initial`); `papers_intact.primary` and `papers_intact.recovery` are both `true` on a healthy drill

#### Scenario: Watchdog flags an overdue drill

- **WHEN** the `MyFinanceBackup-Watchdog` workflow runs and `r2://my-finance-view-backups/status/last-drill.json` is missing OR its `timestamp` is older than 400 days from the current UTC time
- **THEN** the watchdog invokes the shared Dispatch Alert sub-workflow with title `MyFinance backup drill OVERDUE` and a body pointing at `scripts/backup/README.md §2.5.7`; the alert fires on every watchdog tick until a fresh drill JSON is uploaded — the operator can dismiss it by completing the drill, not by suppressing the alert

#### Scenario: Drill reveals a degraded paper and re-print is recorded

- **WHEN** a drill finds the recovery paper smudged or partially illegible AND the operator re-prints it (preserving the same recipient public key, since the underlying identity is unchanged)
- **THEN** `last-drill.json.reprinted` includes `"recovery"`, `papers_intact.recovery` is `true` after re-print, and `scripts/backup/README.md §2.5.5` (key-rotation section) is consulted only if the operator additionally decided to rotate the recipient — which is a separate, heavier operation (re-encrypt the bucket) outside this Requirement's scope

### Requirement: Acknowledged coverage gaps (finding #7)

The backup pipeline of this change SHALL document explicitly what is NOT captured by daily snapshots, so an operator restoring from these snapshots into a fresh Supabase project does not silently lose state they assumed was protected. The acknowledged gaps and their compensating documentation are:

1. **RLS policies on `myfinance.*`** — `pg_dump -n myfinance -Fc` DOES capture row-level security policies as part of the schema dump (verifiable by `pg_restore --list | grep POLICY`). The watchdog SHALL include a one-time documented check during the initial smoke-test that confirms restored RLS policies match the production count (`SELECT count(*) FROM pg_policies WHERE schemaname='myfinance'`).
2. **Supabase Auth state beyond `auth.users(id)` rows** — Supabase Vault secrets, `auth.identities`, `auth.mfa_factors`, `auth.sessions`, OAuth provider config, JWT signing keys: NOT backed up. Recovery into a fresh project means re-establishing auth from scratch (single user, low cost). Documented in `scripts/backup/README.md §2.5.6`.
3. **n8n workflow definitions** — committed in `scripts/backup/n8n/*.json` but only as last-export snapshots; if the operator edits a workflow in the UI without re-exporting, those edits are lost on VPS rebuild. Compensating control: `scripts/backup/install-n8n-workflows.sh` warns on drift (already in Design §10 Risks).
4. **Edge Functions** — not used by this project yet. Listed for future-coverage acknowledgement.
5. **Storage buckets** — not used by this project yet. Listed for future-coverage acknowledgement.
6. **VPS-side state** — `.env.local`, Traefik dynamic config (`traefik/dynamic/myfinance-preop.yml`), rclone config, Docker volumes for n8n and Uptime Kuma: NOT in this backup. The Traefik config IS in git (per Decision 9). The other VPS-side state is the operator's responsibility to re-create from documentation in `scripts/backup/README.md §2.5.3`.
7. **External account credentials** — Cloudflare R2 API token, Gmail App Password, healthchecks.io check URL, Kuma push URL, ntfy topic, webhook secrets, runner shared secret: stored in a password manager and in n8n credentials; NOT in this backup. Rotation procedure in `scripts/backup/README.md §2.5.5`.

#### Scenario: Coverage gaps are documented in the operator runbook

- **WHEN** the operator opens `scripts/backup/README.md §2.5.6` ("Disaster scenarios")
- **THEN** the section explicitly lists items 1–7 above as NOT covered by the snapshot, and points to compensating controls for each (the runbook, the install script, the rotation procedure, the live operator decision)

#### Scenario: Initial smoke-test confirms restored RLS policy count

- **WHEN** the operator runs the bootstrap smoke test (task 9.6) and inspects the verify ephemeral container after `pg_restore` completes
- **THEN** `SELECT count(*) FROM pg_policies WHERE schemaname='myfinance'` against the ephemeral returns the same count the operator captured from production before the test (recorded in `scripts/backup/README.md §2.5.4`). A mismatch indicates the dump silently dropped RLS policies and blocks change activation

### Requirement: Pre-op webhook source-IP allowlist (M3 fix)

The system SHALL restrict the pre-op webhook route at the Traefik layer to a documented IP allowlist, so that requests from non-allowlisted source IPs are rejected with HTTP 403 by Traefik before reaching n8n. The allowlist SHALL be committed to the repository at `traefik/dynamic/myfinance-preop.yml` and consumed by Traefik's file provider (auto-reload). The allowlist MUST include the operator's current home IP (CIDR `/32` — refresh when ISP rotates) AND any other IPs the operator regularly originates pre-op calls from (mobile-hotspot egress range, VPN egress, etc.). The static webhook secret (`MYFINANCE_PREOP_WEBHOOK_SECRET`) remains layered on top of the allowlist: an attacker who has the secret but is not on the allowlist gets HTTP 403; an attacker on the allowlist without the secret gets HTTP 401.

#### Scenario: Traefik dynamic config is committed and references the allowlist middleware

- **WHEN** the developer inspects `traefik/dynamic/myfinance-preop.yml`
- **THEN** the file declares an IP-allowlist middleware AND attaches it to the `myfinance-backup-preop` route on the `n8n.datachefnow.com` host; the IPs are concrete CIDR entries, not a wildcard

#### Scenario: Request from non-allowlisted IP gets 403 before n8n

- **WHEN** a `POST` to `https://n8n.datachefnow.com/webhook/myfinance-backup-preop` originates from an IP outside the allowlist, even with a correct `X-Webhook-Secret` header
- **THEN** the response is HTTP 403 from Traefik directly; n8n logs show no record of the request reaching it; no n8n worker is occupied; no Supabase pg_dump is triggered

#### Scenario: Request from allowlisted IP without secret gets 401 from n8n

- **WHEN** a `POST` to the webhook from an allowlisted IP is made without `X-Webhook-Secret` or with a wrong value
- **THEN** Traefik forwards the request to n8n; n8n's webhook validation returns 401; no snapshot is produced. This confirms the two-layer model: allowlist gates "who can talk", secret gates "what they can do"

### Requirement: Repository layout for the sidecar runner, scripts, and workflows

The system SHALL place all backup-related artefacts under `scripts/backup/` with this layout:

- `Dockerfile.runner` — image definition for the `myfinance-backup-runner` sidecar (Node 22 Alpine + `postgresql17-client` + `age` + `rclone` + `docker-cli` + `tar` + `curl` + `jq` + `tini`).
- `docker-compose.yml` — compose extension that defines the `myfinance-backup-runner` service, joins it to `n8n_net`, bind-mounts `/var/lib/myfinance-backup` and `/var/run/docker.sock`, declares the tmpfs at `/var/lib/myfinance-verify`, and reads its environment from the operator's `.env.local` on the VPS.
- `runner/` — Node + Express HTTP server: `package.json`, `package-lock.json`, `server.js`, `auth.js` (shared-secret middleware), `mutex.js` (in-process run serialization), `workers.js` (spawn + stream bash workers + scrubs the age identity from child env and writes it to the child's stdin pipe).
- `workers/backup-daily.sh` — bash worker invoked by `POST /run/daily`. Performs `pg_dump`, tar, age (two recipients), rclone upload, **post-upload SHA-256 re-verify**, weekly/monthly server-side promotion, **chained restore-verify (reviewer Q2)**, quarantine-routing on failure, `status/last-success.json` upsert, Uptime Kuma push, and **healthchecks.io ping (M1 fix)**.
- `workers/backup-preop.sh` — bash worker invoked by `POST /run/preop`. Performs the same upload pipeline including post-upload SHA-256 re-verify, then invokes the verify worker against the just-uploaded artefact; on any failure, moves the artefact to `quarantine/` server-side.
- `workers/verify-restore.sh` — bash worker invoked by `POST /run/verify` and chained from `backup-preop.sh` / `backup-daily.sh`. Reads the primary age identity from stdin, writes it to a tmpfs file (mode 0600, trap-deleted), spawns ephemeral `postgres:17` with UUID-derived name, waits for Docker DNS + `pg_isready`, pre-creates `auth` schema + stub `auth.users(id uuid)`, restores in order (auth-data, then myfinance), runs probe SQL (NO `latest_transaction_age_days`), tears down, exits non-zero on any failure.
- `workers/alert.sh` — shared library sourced by other workers; emits ntfy push + Gmail SMTP email on failure (both fire in parallel).
- `verify-queries.sql` — the probe SQL suite with documented thresholds and a header comment explaining the smoke-detector-not-alarm semantics.
- `recipients/primary.txt` and `recipients/recovery.txt` — the TWO age recipient public keys bound to this project (B1 fix — was single `recipient.txt`).
- `n8n/MyFinanceBackup-Daily.json`, `n8n/MyFinanceBackup-PreOp.json`, `n8n/MyFinanceBackup-Watchdog.json`, `n8n/MyFinanceBackup-ErrorHandler.json`, `n8n/MyFinanceBackup-DispatchAlert.json` — **five** exported n8n workflows for version control and import (reviewer Q2 fix — the separate weekly `MyFinanceBackup-VerifyRestore` workflow is REMOVED; verify is now chained inside the daily run). `MyFinanceBackup-DispatchAlert` is the shared sub-workflow that fans out alerts to ntfy + Gmail; the other four workflows invoke it via Execute Workflow nodes.
- `README.md` — operator runbook (install, recover, rotate age keys, smoke test, annual disaster drill).

All shell scripts MUST be `bash` compatible (Linux), MUST start with `set -euo pipefail`, and MUST be executable in the repository.

#### Scenario: Required files exist in the repository

- **WHEN** the developer lists `scripts/backup/`
- **THEN** every required file listed above is present, the Dockerfile builds without error, and the n8n workflow JSON files are valid JSON

#### Scenario: Shell workers use strict error handling

- **WHEN** the developer inspects the first non-shebang line of each `.sh` worker under `scripts/backup/workers/`
- **THEN** the line is exactly `set -euo pipefail`

#### Scenario: Shell workers are executable in the repository

- **WHEN** the developer runs `git ls-files --stage scripts/backup/workers/*.sh`
- **THEN** every listed file has mode `100755`

#### Scenario: Node runner has pinned dependencies

- **WHEN** the developer inspects `scripts/backup/runner/`
- **THEN** both `package.json` and `package-lock.json` exist, the lockfile is committed, and `npm ci` reproduces the dependency tree without contacting registries beyond what `package-lock.json` resolves

#### Scenario: Built runner image carries BOTH age recipients and pg_dump 17

- **WHEN** the `myfinance-backup-runner` image has been built and a fresh container is started from it
- **THEN** `docker run --rm myfinance-backup-runner test -f /opt/myfinance-backup/recipients/primary.txt` exits 0; `docker run --rm myfinance-backup-runner test -f /opt/myfinance-backup/recipients/recovery.txt` exits 0; the first 4 bytes of each file are `age1`; the two recipient keys are different; AND `docker run --rm myfinance-backup-runner pg_dump --version` outputs a line matching `^pg_dump \(PostgreSQL\) 17\.` (M9 — version-matched to Supabase Postgres 17.6)

### Requirement: Sidecar runner HTTP contract

The system SHALL expose a `myfinance-backup-runner` container on the Docker network `n8n_net` that listens on TCP port 8080 and provides an HTTP API consumed by the n8n backup workflows. The endpoints SHALL be:

- `GET /healthz` — liveness only. No authentication. Returns `200` with body `{"status":"ok","version":"<git-sha-or-tag>"}` whenever the process is running, including during an in-flight run.
- `GET /status` — read-only status. No authentication. Returns `200` with body `{"lastSuccess":{...}|null,"lastPreop":{...}|null,"lastVerify":{...}|null,"runInProgress":<bool>}` derived from **R2 (`r2:my-finance-view-backups/status/{last-success,last-preop,last-verify}.json`)** which is the canonical source of truth — NOT from the runner's local cache. If R2 is unreachable the endpoint returns `503 {"error":"r2_unreachable"}` so the Watchdog treats stale-data as a workflow error rather than a false-OK.
- `POST /run/daily` — triggers the daily backup pipeline (pg_dump → tar → age (two recipients) → rclone upload → post-upload SHA-256 re-verify → weekly/monthly promote → chained restore-verify → Kuma push → healthchecks.io ping). Synchronous. Typical duration 3–6 min.
- `POST /run/preop` — body `{"reason":"<slug>"}` where the slug matches `^[A-Za-z0-9._+-]{3,60}$`. Triggers the upload pipeline (with post-upload SHA-256 re-verify) AND the full restore-verify chain. Synchronous. Typical duration 3–5 min.
- `POST /run/verify` — triggers a standalone restore-verify of the newest object under `daily/`, OR of a specific target if `{"target":"<r2-path>"}` is supplied in the body. Synchronous. Available for operator-initiated ad-hoc verification; no longer triggered by a scheduled n8n workflow (reviewer Q2 fix — verify is now chained inside the daily run).

All `POST` endpoints SHALL require the header `X-Runner-Secret` whose value equals the runner-side environment variable `MYFINANCE_BACKUP_RUNNER_SECRET`. The runner SHALL serialize run-class requests (`/run/*`) via an in-process mutex so a second concurrent run-class request returns HTTP 409 without starting a second run. The runner SHALL NOT publish its port to the host network — only the in-cluster `n8n_net` reaches it.

#### Scenario: Liveness endpoint is open and always-on

- **WHEN** any client on `n8n_net` issues `GET /healthz` against the runner
- **THEN** the response is `200` with `Content-Type: application/json` and a body containing the `status` and `version` keys, regardless of whether a run is in flight

#### Scenario: Run endpoints reject missing or wrong shared secret

- **WHEN** a `POST /run/daily` is made without the `X-Runner-Secret` header or with an incorrect value
- **THEN** the response status is `401`, the response body is empty, and no backup run is started

#### Scenario: Concurrent run-class requests are rejected

- **WHEN** a `POST /run/daily` is in progress and a second `POST /run/daily` (or `/run/preop`, `/run/verify`) arrives with a valid secret
- **THEN** the second request returns `409` with body `{"error":"run_in_progress"}`, and the first run continues unaffected

#### Scenario: Pre-op chains internal verify without re-acquiring the mutex

- **WHEN** a `POST /run/preop` is in flight and the worker process internally invokes `verify-restore.sh` as a subprocess (NOT via a fresh HTTP call to `/run/verify`)
- **THEN** the chained verify runs to completion without returning 409; the mutex covers HTTP entrypoints only — in-process subprocess chaining is permitted by design (B5 fix). A concurrent external `POST /run/verify` arriving while pre-op is verifying still gets 409, because that one IS an HTTP entrypoint.

#### Scenario: Runner is unreachable from outside its Docker network

- **WHEN** the operator runs `curl http://<vps-public-ip>:8080/healthz` from outside the VPS
- **THEN** the connection is refused or times out, because the runner publishes no host port and Traefik is not configured to route to it

#### Scenario: Status endpoint returns runner state without authentication

- **WHEN** the `MyFinanceBackup-Watchdog` workflow issues `GET /status` on its schedule
- **THEN** the response is `200` with the last-success timestamp readable from `lastSuccess.timestamp`, allowing the watchdog to compute freshness without holding a credential

### Requirement: Uptime Kuma Push Monitor (in-cluster dead-man-switch)

The system SHALL emit a success heartbeat to a self-hosted Uptime Kuma Push Monitor on every completed daily run. The push URL SHALL be stored as the n8n credential `MYFINANCE_BACKUP_KUMA_PUSH_URL`, surfaced as a placeholder in `.env.example`, and MUST NOT be committed to the repository (it carries an unguessable token equivalent to a secret). The Kuma monitor SHALL be configured with heartbeat interval = 24 hours and grace period = 6 hours, so a missing push for more than 30 hours triggers a Kuma-side alert through whichever channels the operator has wired inside Kuma. **The Kuma monitor MUST have AT LEAST ONE downstream notification channel wired before this change activates (finding #8 fix — symmetric with the healthchecks.io requirement that mandates an integration);** permissible channels include Telegram, Discord, Gmail SMTP, ntfy.sh, or any generic webhook supported by Kuma. A Kuma monitor without a wired channel detects the missed heartbeat in its UI but pages no one — that silent-Kuma failure mode is precisely what this Requirement forbids.

The Kuma push is the **in-cluster** dead-man-switch leg of the four-channel alerting (alongside ntfy.sh, Gmail SMTP, and healthchecks.io). It specifically covers the scenario where the n8n workflow never ran but the VPS itself is alive. A failure of the daily run SHALL NOT push to Kuma (the absence of the ping is what makes Kuma alert); explicit ntfy + Gmail alerts still fire from the worker as long as the runner can reach the internet.

#### Scenario: Successful daily run pushes to Kuma

- **WHEN** `workers/backup-daily.sh` completes all steps (pg_dump, encrypt, upload, sha256 re-verify, promote, chained verify) with exit 0
- **THEN** the worker issues exactly one `GET <MYFINANCE_BACKUP_KUMA_PUSH_URL>?status=up&msg=ok&ping=<elapsed_ms>` request, and the response is treated as fire-and-forget (a non-2xx Kuma response is logged but does not fail the run, since the backup itself already succeeded)

#### Scenario: Failed daily run does NOT push success to Kuma

- **WHEN** any step of `workers/backup-daily.sh` fails and the script exits non-zero
- **THEN** no `status=up` push is sent to Kuma; the absence of the ping causes the Kuma monitor to fire its own alert once the grace period elapses

#### Scenario: Kuma push URL is not committed to the repository

- **WHEN** `git grep -E "https?://[^/]+/api/push/"` is executed against the repository working tree and history
- **THEN** no matches exist outside `.env.example` (which holds only the placeholder, no token)

#### Scenario: Kuma monitor has at least one downstream notification channel wired (finding #8 fix)

- **WHEN** the operator opens the Kuma `MyFinance Daily Backup` Push Monitor's *Notifications* tab BEFORE setting any Schedule Trigger to Active (task 10.1)
- **THEN** at least one notification channel is attached AND the operator has clicked Kuma's "Test" button against that channel and observed the test message arrive at its destination (Telegram message received, Discord message received, ntfy message received, email received, etc.). A monitor with zero wired channels — or with channels whose test never delivered — fails this Scenario and blocks change activation. This closes the silent-Kuma failure mode where a missed heartbeat would log in the Kuma UI but never page the operator

### Requirement: healthchecks.io off-VPS dead-man-switch (M1 fix)

The system SHALL ping a healthchecks.io check URL on every completed daily run. The check URL SHALL be stored as the n8n credential / runner env var `MYFINANCE_BACKUP_HEALTHCHECKS_URL`, surfaced as a placeholder in `.env.example`, and MUST NOT be committed to the repository (it is an unguessable URL containing a token equivalent to a secret). The healthchecks.io check SHALL be configured with schedule `@daily` (or the operator's chosen explicit cron) AND a grace period of 6 hours, so a missing ping for more than ~30 hours triggers an alert via the operator's healthchecks.io account integrations (email mandatory; optional Slack/Discord/SMS).

The healthchecks.io ping is the **off-VPS** dead-man-switch leg of the four-channel alerting, specifically covering the scenario where the entire VPS (n8n + sidecar + Kuma) is down. The healthchecks.io service runs on infrastructure the operator does not own, so a complete VPS outage cannot suppress it — the absence of the ping triggers the alert from outside the operator's failure domain. A failure of the daily run SHALL NOT ping healthchecks.io (the absence is what makes healthchecks.io alert).

#### Scenario: Successful daily run pings healthchecks.io

- **WHEN** `workers/backup-daily.sh` completes all steps with exit 0
- **THEN** the worker issues exactly one `GET <MYFINANCE_BACKUP_HEALTHCHECKS_URL>` (HEAD or POST also accepted by healthchecks.io; GET is the documented default in the runbook) and treats the response as fire-and-forget — a non-2xx healthchecks.io response is logged but does not fail the run

#### Scenario: Failed daily run does NOT ping healthchecks.io

- **WHEN** any step of `workers/backup-daily.sh` fails and the script exits non-zero
- **THEN** no ping is sent to healthchecks.io; the absence of the ping causes healthchecks.io to fire its own alert once the grace period elapses

#### Scenario: healthchecks.io check URL is not committed to the repository

- **WHEN** `git grep -E "https?://hc-ping\.com/"` is executed against the repository working tree and history
- **THEN** no matches exist outside `.env.example` (which holds only the placeholder URL form, no token)

#### Scenario: Healthchecks.io integration delivers operator-side notification

- **WHEN** the operator's healthchecks.io account configuration is inspected
- **THEN** at least one integration is enabled for the `myfinance-backup-daily` check — the integration target MUST be reachable independent of the operator's VPS (e.g. an email account hosted at a different provider, a Slack webhook, etc.) so that a VPS-wide outage AND a Kuma silence do not also silence this alert path
