## ADDED Requirements

### Requirement: Scheduled encrypted snapshot of the myfinance schema

The system SHALL produce a daily snapshot of the Supabase Postgres remote covering the `myfinance` schema and the entire `auth.users` table (finding #16 clarification — `pg_dump -t auth.users` captures the full table, not only FK-referenced rows; for the single-user system this is one row and the distinction is moot, but the spec wording is now honest), using `pg_dump --format=custom --compress=9`. The two dump files (`myfinance.dump`, `auth-users.dump`) plus a `README.txt` SHALL be bundled into a single `.tar` and encrypted at rest with `age` against ONE project-owned recipient (primary) in a single encryption pass before being written to any persistent location. The resulting artefact MUST be named `YYYY-MM-DD.tar.age` and uploaded to the `daily/` prefix of the primary cloud bucket on Cloudflare R2. The daily workflow SHALL synchronously chain into the Automated restore verification Requirement before returning success; a daily snapshot whose verification fails MUST be quarantined (see Quarantine routing Requirement) and reported as a failed run.

#### Scenario: Daily snapshot completes against Supabase remote

- **WHEN** the n8n workflow `MyFinanceBackup-Daily` runs at 02:30 America/Bogota with valid Supabase credentials, R2 credentials, and the primary age recipient configured
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

#### Scenario: VPS working copy is deleted after R2 upload is verified

- **WHEN** the daily script finishes uploading to R2, `rclone check` confirms parity between local and remote, AND the post-upload SHA-256 re-verify (re-downloaded from R2) matches the pre-upload SHA-256
- **THEN** the working file under `/var/lib/myfinance-backup/working/` is deleted before the script proceeds to verify-restore

### Requirement: Manual on-demand pre-operation snapshot

The system SHALL provide a manual procedure that produces a labelled snapshot before any write operation against Supabase remote. The procedure SHALL be invokable from the n8n UI by clicking **Execute Workflow** on the `MyFinanceBackup-PreOp` workflow with the `reason` value supplied by editing the workflow's `Set Reason` node. There is NO public webhook (v3 Gate C cut M3 — see `openspec/changes/supabase-backup-policy/proposal.md ## Threat model`); the operator's direct n8n UI access is the only entry point. The workflow MUST refuse to proceed when `reason` is absent or fails the regex `^[A-Za-z0-9._+-]{3,60}$` (B4 fix — previous strict lower-kebab regex rejected reasonable slugs like `Flyway-Baseline` and `v4.1-migration`). The artefact SHALL be named `YYYY-MM-DDTHH-MM-SSZ-<reason>.tar.age` (UTC timestamp) and uploaded to the `pre-op/` prefix of the R2 bucket. After upload, the procedure SHALL re-download the just-uploaded object from R2 and compare its SHA-256 to the pre-upload SHA-256; on mismatch the artefact MUST be moved to `quarantine/` and the call MUST fail with HTTP 500 (M12 fix). The procedure SHALL additionally run a full restore-verify against the just-uploaded artefact (same algorithm as the Automated restore verification Requirement) BEFORE returning success to the caller; if restore-verify fails the procedure MUST move the artefact to `quarantine/`, return HTTP 500 with the failing probe in the response body, MUST NOT update `status/last-preop.json` on R2, and MUST dispatch alerts via all configured channels.

#### Scenario: Pre-op n8n workflow rejects missing reason

- **WHEN** the operator clicks Execute Workflow on `MyFinanceBackup-PreOp` with the `Set Reason` node value blank
- **THEN** the `Validate Reason` Code node throws with an `invalid_reason` error, no HTTP request is made to the sidecar, and the n8n execution log shows the regex mismatch and a pointer to edit the `Set Reason` node and retry

#### Scenario: Pre-op n8n workflow rejects invalid reason slug with actionable error

- **WHEN** the operator sets the `reason` value to `Flyway Baseline!` (contains a space and a bang) and clicks Execute Workflow
- **THEN** the `Validate Reason` Code node throws an error containing the regex `^[A-Za-z0-9._+-]{3,60}$` and the offending input; the sidecar is NOT called; the operator can immediately correct the input from the same execution view (B4 fix — defense in depth retained even though the runner also validates)

#### Scenario: Pre-op accepts upper/lower/digits/dot/plus/hyphen/underscore

- **WHEN** the operator sets `reason` to `v4.1-migration_attempt2` and clicks Execute Workflow
- **THEN** validation passes, the HTTP request to the sidecar `/run/preop` succeeds, and the eventual artefact name contains the literal slug `v4.1-migration_attempt2`

#### Scenario: Pre-op snapshot uploads, re-verifies, AND passes restore-verify

- **WHEN** the pre-op workflow runs successfully with `reason="flyway-baseline"`, the post-upload R2 SHA-256 matches the pre-upload SHA-256, AND the full restore-verify completes green
- **THEN** a file matching `pre-op/<UTC-timestamp>-flyway-baseline.tar.age` exists in the R2 bucket, its SHA-256 is recorded in `status/last-preop.json` on R2 alongside the probe results, and the sidecar response body is `{"artefact":"pre-op/<filename>","sha256":"<hash>","verifyResult":{"probes":[...]}}` with HTTP 200

#### Scenario: Pre-op fails loudly when post-upload SHA-256 mismatches (M12 fix)

- **WHEN** the pre-op pipeline computes one SHA-256 locally before upload AND a different SHA-256 from a re-download of the just-uploaded object on R2
- **THEN** the artefact is moved (server-side) from `pre-op/` to `quarantine/<timestamp>-pre-op-<original-name>`, `status/last-preop.json` is NOT updated, the sidecar response status is 500 with body `{"error":"upload_corrupted","local_sha256":"…","r2_sha256":"…","quarantined_to":"quarantine/…"}`, and all three alert legs engage (ntfy explicit fire + Gmail explicit fire + Kuma absence-of-success-push)

#### Scenario: Pre-op fails loudly when restore-verify rejects the snapshot

- **WHEN** the pre-op artefact uploads successfully, the SHA-256 re-verify matches, but the subsequent restore-verify finds `myfinance.transactions` row count below the configured threshold
- **THEN** the artefact is moved (server-side) from `pre-op/` to `quarantine/<timestamp>-pre-op-<original-name>` (M15 fix), the sidecar response status is 500, the response body contains `{"error":"verify_failed","probe":{...},"quarantined_to":"quarantine/…"}`, `status/last-preop.json` is NOT updated, and explicit ntfy + Gmail alerts are dispatched

### Requirement: Encryption with a single project-owned age recipient, single-paper custody (v3 Gate C cut B1)

The system SHALL encrypt every snapshot with `age` using ONE recipient public key in a single encryption pass (`age -r "$(cat recipients/primary.txt)" -o snapshot.tar.age snapshot.tar`). The recipient file SHALL be committed to the repository at `scripts/backup/recipients/primary.txt` and baked into the runner image at build time. The matching identity (private key) MUST NOT be committed to the repository under any circumstance. Identity custody SHALL be:

- **Primary identity** — stored at (a) operator's Windows workstation `%USERPROFILE%\.config\myfinance-backup\age-identity-primary.txt` with NTFS ACLs restricted to the user account, AND (b) printed paper copy in a sealed envelope at physical location A (operator's documented choice — home safe / firebox / locked drawer).

**Catastrophic key-loss path acknowledged and accepted:** if BOTH the PC identity AND the paper at location A are simultaneously lost (e.g. a fire that destroys both, or a hardware failure followed by the operator discovering the envelope is missing), every existing encrypted snapshot is permanently unreadable. This is the only unrecoverable path under the single-recipient design and is documented in `scripts/backup/README.md §2.5.6`. The earlier v2 design mitigated this with a second recipient (recovery identity at physical location B) but this was retroactively cut under v3 because the operator's threat model excludes the local-forensic adversary that would have justified the dual-paper operational cost — see `openspec/changes/supabase-backup-policy/proposal.md ## Threat model` and `design.md` Decision 2 (revised).

The n8n VPS SHALL hold ONLY the primary identity, and only as the n8n credential `MYFINANCE_BACKUP_AGE_IDENTITY`. The identity SHALL reach the bash verify worker via the runner's child `stdin` pipe (NOT via environment variable), be written to `/var/lib/myfinance-verify/.identity` with mode `0600` inside the compose-declared tmpfs, used by `age -d -i`, and unconditionally wiped by an EXIT trap before the worker terminates. The repository `.gitignore` MUST block any file matching `age-identity*`, `*.identity`, and (best-effort, filename-only) age-identity content.

#### Scenario: The primary recipient file is present and references a valid age public key

- **WHEN** the developer inspects `scripts/backup/recipients/primary.txt`
- **THEN** the file exists, contains exactly one line starting with `age1`, and is the only file under `scripts/backup/recipients/` (no `recovery.txt` is present — v3 single-recipient design)

#### Scenario: Repository does not contain any age identity

- **WHEN** `git grep "AGE-SECRET-KEY-"` is executed against the working tree and history
- **THEN** no matches are found

#### Scenario: Primary identity decrypts any snapshot

- **WHEN** an arbitrary snapshot from R2 is decrypted with the primary identity via `age -d --identity primary-identity.txt < snapshot.tar.age > snapshot.tar`
- **THEN** decryption succeeds end-to-end and the resulting `.tar` extracts cleanly to the three expected files (`myfinance.dump`, `auth-users.dump`, `README.txt`)

#### Scenario: Verify-restore reads primary identity from stdin, never persists it to non-tmpfs

- **WHEN** the `verify-restore.sh` script runs to completion (success or failure)
- **THEN** no file matching `*identity*` or `*.identity` exists under `/var/lib/myfinance-backup/` or any other persistent (non-tmpfs) disk path on the VPS after the script exits; the identity existed only inside `/var/lib/myfinance-verify/.identity` (compose-declared tmpfs, mode 0600) for the duration of the `age -d -i` invocation, and was wiped by the EXIT trap

#### Scenario: Identity reaches the worker via stdin and a tmpfs file, never via environment (B7 fix)

- **WHEN** the Node runner spawns `verify-restore.sh` (directly or chained from `backup-preop.sh` / `backup-daily.sh`)
- **THEN** the child process's `/proc/<pid>/environ` contains NO key named `MYFINANCE_BACKUP_AGE_IDENTITY`; the runner spawns the worker with the identity scrubbed from the child env, writes the identity bytes onto the child's stdin pipe, and the worker reads stdin into a bash variable then writes that variable to `/var/lib/myfinance-verify/.identity` with `umask 0177` so the file is created mode `0600`. The previous draft's `age -d -i /dev/stdin < snapshot.tar.age` invocation is REJECTED by this Scenario because the `< ciphertext` redirection replaces stdin and starves age of the identity — the spec mandates the tmpfs-file path for `-i`

### Requirement: Primary cloud destination on Cloudflare R2

The system SHALL upload every snapshot to a primary cloud bucket hosted on Cloudflare R2 with bucket name `my-finance-view-backups`. The bucket SHALL be accessed via `rclone` using an S3-compatible remote named `r2` configured with provider `Cloudflare`. R2 credentials and the Cloudflare account ID MUST be stored as n8n credentials under the names `BACKUP_R2_ACCESS_KEY_ID`, `BACKUP_R2_SECRET_ACCESS_KEY`, and `BACKUP_R2_ACCOUNT_ID`, and surfaced as placeholders in `.env.example`. The credentials MUST NOT be committed to the repository. The runner image SHALL materialize an rclone remote definition at container start time from these env vars (entrypoint shim populates `RCLONE_CONFIG_R2_*` from `BACKUP_R2_*`) so that `rclone` commands inside the container resolve `r2:` without requiring a host-side `rclone.conf` bind-mount.

#### Scenario: rclone remote is defined and reachable from inside the runner container

- **WHEN** the operator runs `docker exec myfinance-backup-runner rclone listremotes` on the VPS after `docker compose up`
- **THEN** the output contains `r2:` AND `docker exec myfinance-backup-runner rclone lsd r2:` returns 0 within 5 seconds (reaches the bucket); the underlying endpoint matches `https://<account_id>.r2.cloudflarestorage.com`

#### Scenario: rclone is configured with provider = Cloudflare for server-side copy

- **WHEN** the operator inspects the effective rclone config inside the container (`docker exec myfinance-backup-runner rclone config show r2`)
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
- **THEN** the worker MUST treat the status-upsert failure as a fatal step: log the failure, dispatch the ntfy + Gmail alert, AND exit non-zero. The Kuma success push MUST NOT be sent on the failure path. Rationale: a green verify with a failed status-write produces an artefact in R2 that the watchdog cannot discover the next morning — the watchdog reads `status/last-success.json` and finds it stale, triggering a false-positive "STALE" alert. Treating the upsert as fatal collapses the failure into the existing alert path (operator sees one explicit alert, no surprise "STALE" the next day). The in-cluster dead-man-switch (Kuma) correctly does NOT receive the success heartbeat, so it will also fire after grace — multiple signals, all consistent.

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
- **THEN** the script exits non-zero, `status/last-verify.json` records the failing probe, the artefact is moved to `quarantine/`, and both alert legs engage (ntfy + Gmail explicit fire; Kuma receives no success heartbeat for the daily run)

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
- **THEN** `status/last-success.json` for the day MUST include the keys `previousFailedAttempts: <int >= 1>` and `quarantinedArtefacts: ["quarantine/<timestamp>-daily-<file>", …]` listing the quarantined sibling(s). A reader who later inspects only `status/last-success.json` MUST be able to discover that the day had at least one failed attempt before the green success — without these fields, "green green green" in the status log would hide that something was wrong earlier in the day and the operator would never investigate the quarantined artefact. **Key naming convention (M4 fix from replant adversarial review, scope clarified for finding N2):** all JSON keys in (a) `status/*.json` files AND (b) sidecar success-response bodies use camelCase (`previousFailedAttempts`, `quarantinedArtefacts`, `lastSuccess`, `lastVerify`, `lastPreop`, `lastDrill`, `paperIntact`, `snapshotUsed`, `operatorInitial`, `verifyResult`, `allPassed`) — chosen for consistency with JavaScript convention used by the runner's `/status` endpoint. Sidecar ERROR-response bodies retain snake_case for legacy keys (`local_sha256`, `r2_sha256`, `quarantined_to`, `verify_failed`, `upload_corrupted`) — code at `scripts/backup/workers/backup-preop.sh:118,149` already writes these and the existing Scenarios at the Manual on-demand pre-operation snapshot Requirement reference them by name; a future bounded normalization change MAY sweep these to camelCase, but Phase 1 ships with the existing convention. Any NEW error field added by future work SHOULD use camelCase.

### Requirement: Three-leg alerting (ntfy.sh + Gmail SMTP + Uptime Kuma)

The system SHALL dispatch alerts on every failure path through THREE independent channels (v3 Gate C cut M1 retracted the original fourth leg — healthchecks.io off-VPS pinger — see `openspec/changes/supabase-backup-policy/proposal.md ## Threat model`):

1. **Explicit-dispatch from inside the VPS** — ntfy.sh push + Gmail SMTP email, both fired in parallel by the worker as long as the runner has outbound internet. Triggered when (a) the daily backup script exits non-zero, (b) the restore-verify script exits non-zero, (c) the watchdog detects stale `status/last-success.json` or `status/last-verify.json`, or (d) the n8n `MyFinanceBackup-ErrorHandler` fires.
2. **In-cluster dead-man-switch** — Uptime Kuma Push Monitor. The daily worker pings the Kuma push URL on success; the absence of a ping (heartbeat 24 h + grace 6 h = 30 h) triggers Kuma to fire its own alerts via channels wired inside Kuma. Catches the case where the workflow itself never ran.

The ntfy topic SHALL be stored as n8n credential `MYFINANCE_BACKUP_NTFY_TOPIC`. The Gmail App Password SHALL be stored as n8n credential `MYFINANCE_BACKUP_GMAIL_APP_PASSWORD`. The Kuma push URL SHALL be stored as `MYFINANCE_BACKUP_KUMA_PUSH_URL`. None of these URLs/tokens MAY be committed to the repository (each carries an unguessable secret). Successful runs SHALL NOT dispatch any explicit-channel alert; they push Kuma only.

**Whole-VPS outage compensating signal (v3 Gate C cut M1 rationale):** the parallel Gmail-ingest workflow that already runs on the same VPS for normal MyFinanceView operation acts as a secondary signal for whole-VPS outage — if Gmail ingestion stops, the operator notices within hours from missing transaction notifications. This is weaker than a dedicated off-VPS pinger but deemed sufficient for the operator's threat model. If Kuma alone proves insufficient in practice (e.g. an outage where Gmail ingest is also paused), a bounded follow-up change MAY reinstate healthchecks.io.

#### Scenario: Failed daily run dispatches both explicit legs

- **WHEN** `workers/backup-daily.sh` exits non-zero inside the n8n workflow
- **THEN** a single `POST` to `https://ntfy.sh/<topic>` is made with title `MyFinance backup FAILED` and a body containing the last 20 lines of the log, AND a single email is sent via Gmail SMTP from `aftorresl01@gmail.com` to `aftorresl01@gmail.com` with the same subject and the full log

#### Scenario: Watchdog fires when daily run is stale

- **WHEN** the `MyFinanceBackup-Watchdog` workflow calls `GET /status` on the runner and the response indicates `lastSuccess` is null OR `(now - lastSuccess.timestamp) > 30 hours` (the runner's `/status` reads R2 `status/last-success.json` as the canonical source — M14 fix)
- **THEN** the workflow invokes the shared Dispatch Alert sub-workflow with title `MyFinance backup STALE`, dispatching both an ntfy `POST` and an email

#### Scenario: Successful run does not alert

- **WHEN** `workers/backup-daily.sh` exits 0
- **THEN** no `POST` to `ntfy.sh` is made, no email is sent, and `status/status.log` on R2 gains exactly one new line recording the success

#### Scenario: ntfy outage does not block the email

- **WHEN** a failure occurs and the `ntfy.sh` POST fails (e.g. HTTP 5xx or network timeout)
- **THEN** the email is still dispatched, the n8n workflow records both attempts, and the overall workflow status reflects the alert delivery state

#### Scenario: Explicit channels failed — Kuma still fires on the dead-man path

- **WHEN** a backup run fails AND both the ntfy POST and the Gmail SMTP send fail in the same alert dispatch
- **THEN** the worker still exits non-zero (the daily run is recorded as failed); no Kuma success push is emitted; Kuma's 30 h grace elapses and fires its own alert via channels wired inside Kuma. The dead-man channel reaches the operator independently of ntfy/Gmail

#### Scenario: Successful daily run pushes Kuma

- **WHEN** `backup-daily.sh` completes all steps including chained restore-verify with exit 0
- **THEN** the worker issues exactly one `GET <MYFINANCE_BACKUP_KUMA_PUSH_URL>?status=up&msg=ok&ping=<elapsed_ms>`. The push is fire-and-forget (a non-2xx Kuma response is logged but does not fail the run, since the backup itself already succeeded by this point)

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
- **THEN** they can copy `openspec/templates/supabase-write-checklist.md` as task 0 of their `tasks.md`; the template lists the freshness check (read `status/last-success.json` from R2, confirm < 24 h old, or invoke the PreOp workflow from the n8n UI and re-check `status/last-preop.json`)

### Requirement: Recurring disaster-drill cadence (finding #5)

A backup policy whose restore procedure has only been exercised once at bootstrap decays over time — the paper smudges, ink fades, the envelope gets misfiled, the operator changes residence, and the recovery path silently rots. To prevent this, the system SHALL require a documented **disaster drill at least every 12 months**, the result of which is recorded so the freshness of the recovery path can be observed.

Each drill MUST cover: (a) retrieving the primary paper from physical location A and visually confirming it is still legible; (b) on a clean machine (e.g. a fresh Docker container, a recently-formatted laptop, or the operator's PC after confirming the on-disk identity is the same as the paper), typing the primary identity by hand and decrypting one historical snapshot end-to-end as proof of the recovery path; (c) re-printing the paper if smudged, water-damaged, or otherwise degraded.

The drill outcome SHALL be recorded by writing a JSON blob to `r2://my-finance-view-backups/status/last-drill.json` containing `{"timestamp":"<ISO-8601 UTC>","paperIntact":<bool>,"reprinted":<bool>,"snapshotUsed":"<r2-path>","operatorInitial":"<AT>"}`. The watchdog SHALL include a stale-drill probe: if `last-drill.json` is missing OR its timestamp is older than 400 days (12-month cadence + 35-day grace), the watchdog SHALL dispatch a `MyFinance backup drill OVERDUE` alert via the shared Dispatch Alert sub-workflow. The overdue alert is a low-severity reminder, NOT a daily-run failure — the daily pipeline continues to run, but the operator is reminded that the recovery path has not been exercised within the cadence.

#### Scenario: status/last-drill.json is upserted after each drill

- **WHEN** the operator completes a drill and uploads the JSON blob via `rclone copy` to `r2://my-finance-view-backups/status/last-drill.json`
- **THEN** the file is present at that path with the required keys (`timestamp`, `paperIntact`, `reprinted`, `snapshotUsed`, `operatorInitial`); `paperIntact` is `true` on a healthy drill

#### Scenario: Watchdog flags an overdue drill

- **WHEN** the `MyFinanceBackup-Watchdog` workflow runs and `r2://my-finance-view-backups/status/last-drill.json` is missing OR its `timestamp` is older than 400 days from the current UTC time
- **THEN** the watchdog invokes the shared Dispatch Alert sub-workflow with title `MyFinance backup drill OVERDUE` and a body pointing at `scripts/backup/README.md §2.5.7`; the alert fires on every watchdog tick until a fresh drill JSON is uploaded — the operator can dismiss it by completing the drill, not by suppressing the alert

#### Scenario: Drill reveals a degraded paper and re-print is recorded

- **WHEN** a drill finds the paper smudged or partially illegible AND the operator re-prints it (preserving the same recipient public key, since the underlying identity is unchanged)
- **THEN** `last-drill.json.reprinted` is `true`, `paperIntact` is `true` after re-print, and `scripts/backup/README.md §2.5.5` (key-rotation section) is consulted only if the operator additionally decided to rotate the recipient — which is a separate, heavier operation (re-encrypt the bucket) outside this Requirement's scope

### Requirement: Acknowledged coverage gaps (finding #7)

The backup pipeline of this change SHALL document explicitly what is NOT captured by daily snapshots, so an operator restoring from these snapshots into a fresh Supabase project does not silently lose state they assumed was protected. The acknowledged gaps and their compensating documentation are:

1. **RLS policies on `myfinance.*`** — `pg_dump -n myfinance -Fc` DOES capture row-level security policies as part of the schema dump (verifiable by `pg_restore --list | grep POLICY`). The watchdog SHALL include a one-time documented check during the initial smoke-test that confirms restored RLS policies match the production count (`SELECT count(*) FROM pg_policies WHERE schemaname='myfinance'`).
2. **Supabase Auth state beyond `auth.users(id)` rows** — Supabase Vault secrets, `auth.identities`, `auth.mfa_factors`, `auth.sessions`, OAuth provider config, JWT signing keys: NOT backed up. Recovery into a fresh project means re-establishing auth from scratch (single user, low cost). Documented in `scripts/backup/README.md §2.5.6`.
3. **n8n workflow definitions** — committed in `scripts/backup/n8n/*.json` but only as last-export snapshots; if the operator edits a workflow in the UI without re-exporting, those edits are lost on VPS rebuild. Compensating control: re-export workflows from n8n UI before any commit that touches `scripts/backup/n8n/`. **Note:** Phase 2 (`plans/2026-05-31-supabase-backup-multi-tenant-design.md`) plans to add n8n state itself as a backed-up "project" using a dedicated `workers/backup-n8n.sh`, which closes this gap operationally; until Phase 2 lands the operator habit is the only mitigation.
4. **Edge Functions** — not used by this project yet. Listed for future-coverage acknowledgement.
5. **Storage buckets** — not used by this project yet. Listed for future-coverage acknowledgement.
6. **VPS-side state** — `.env.local`, rclone config (entrypoint shim materializes it from env), Docker volumes for n8n and Uptime Kuma: NOT in this backup. The operator's responsibility to re-create from documentation in `scripts/backup/README.md §2.5.3`. **Traefik dynamic config does NOT exist in this change** (v3 Gate C cut M3 removed the public pre-op webhook, which was the only Traefik dynamic file this change would have introduced); the static n8n Traefik route is the operator's responsibility outside this repo and is unchanged.
7. **External account credentials** — Cloudflare R2 API token, Gmail App Password, Kuma push URL, ntfy topic, runner shared secret: stored in a password manager and in n8n credentials; NOT in this backup. Rotation procedure in `scripts/backup/README.md §2.5.5`.

#### Scenario: Coverage gaps are documented in the operator runbook

- **WHEN** the operator opens `scripts/backup/README.md §2.5.6` ("Disaster scenarios")
- **THEN** the section explicitly lists items 1–7 above as NOT covered by the snapshot, and points to compensating controls for each (the runbook, the rotation procedure, the live operator decision)

#### Scenario: Initial smoke-test confirms restored RLS policy count

- **WHEN** the operator runs the bootstrap smoke test (task 9.6) and inspects the verify ephemeral container after `pg_restore` completes
- **THEN** `SELECT count(*) FROM pg_policies WHERE schemaname='myfinance'` against the ephemeral returns the same count the operator captured from production before the test (recorded in `scripts/backup/README.md §2.5.4`). A mismatch indicates the dump silently dropped RLS policies and blocks change activation

### Requirement: Repository layout for the sidecar runner, scripts, and workflows

The system SHALL place all backup-related artefacts under `scripts/backup/` with this layout:

- `Dockerfile.runner` — image definition for the `myfinance-backup-runner` sidecar (Node 22 Alpine + `postgresql17-client` + `age` + `rclone` + `docker-cli` + `tar` + `curl` + `jq` + `tini` + `util-linux` for `uuidgen`). The Dockerfile MUST also install an entrypoint shim that materializes the rclone remote definition `[r2]` from the runtime `BACKUP_R2_*` env vars (populates `RCLONE_CONFIG_R2_TYPE`, `RCLONE_CONFIG_R2_PROVIDER`, `RCLONE_CONFIG_R2_ACCESS_KEY_ID`, `RCLONE_CONFIG_R2_SECRET_ACCESS_KEY`, `RCLONE_CONFIG_R2_ENDPOINT`) so `rclone` resolves `r2:` without requiring a host-side `rclone.conf` bind-mount.
- `docker-compose.yml` — compose extension that defines the `myfinance-backup-runner` service, joins it to `n8n_net`, bind-mounts `/var/lib/myfinance-backup` and `/var/run/docker.sock`, declares the tmpfs at `/var/lib/myfinance-verify`, and reads its environment from the operator's `.env.local` on the VPS.
- `runner/` — Node + Express HTTP server: `package.json`, `package-lock.json`, `server.js`, `auth.js` (shared-secret middleware), `mutex.js` (in-process run serialization), `workers.js` (spawn + stream bash workers + scrubs the age identity from child env and writes it to the child's stdin pipe), `docker-entrypoint.sh` (rclone config shim — see Dockerfile.runner).
- `workers/backup-daily.sh` — bash worker invoked by `POST /run/daily`. Performs `pg_dump`, tar, age (single recipient), rclone upload, **post-upload SHA-256 re-verify**, weekly/monthly server-side promotion, **chained restore-verify (reviewer Q2)**, quarantine-routing on failure, `status/last-success.json` upsert, and Uptime Kuma push.
- `workers/backup-preop.sh` — bash worker invoked by `POST /run/preop`. Performs the same upload pipeline including post-upload SHA-256 re-verify, then invokes the verify worker against the just-uploaded artefact; on any failure, moves the artefact to `quarantine/` server-side.
- `workers/verify-restore.sh` — bash worker invoked by `POST /run/verify` and chained from `backup-preop.sh` / `backup-daily.sh`. Reads the primary age identity from stdin, writes it to a tmpfs file (mode 0600, trap-deleted), spawns ephemeral `postgres:17` with UUID-derived name, waits for Docker DNS + `pg_isready`, pre-creates `auth` schema + stub `auth.users(id uuid)`, restores in order (auth-data, then myfinance), runs probe SQL (NO `latest_transaction_age_days`), tears down, exits non-zero on any failure.
- `workers/alert.sh` — shared library sourced by other workers; emits ntfy push + Gmail SMTP email on failure (both fire in parallel).
- `verify-queries.sql` — the probe SQL suite with documented thresholds and a header comment explaining the smoke-detector-not-alarm semantics.
- `recipients/primary.txt` — the single age recipient public key bound to this project (v3 Gate C cut B1 — the v2 `recipients/recovery.txt` is intentionally absent).
- `n8n/MyFinanceBackup-Daily.json`, `n8n/MyFinanceBackup-PreOp.json`, `n8n/MyFinanceBackup-Watchdog.json`, `n8n/MyFinanceBackup-ErrorHandler.json`, `n8n/MyFinanceBackup-DispatchAlert.json` — **five** exported n8n workflows for version control and import (reviewer Q2 fix — the separate weekly `MyFinanceBackup-VerifyRestore` workflow is REMOVED; verify is now chained inside the daily run). `MyFinanceBackup-DispatchAlert` is the shared sub-workflow that fans out alerts to ntfy + Gmail; the other four workflows invoke it via Execute Workflow nodes. `MyFinanceBackup-PreOp` is manual-trigger only (v3 Gate C cut M3 — no public webhook).
- `README.md` — operator runbook (install, recover, rotate age key, smoke test, annual disaster drill).

All shell scripts MUST be `bash` compatible (Linux), MUST start with `set -euo pipefail` (within the first few non-shebang lines, after `_common.sh` sourcing), and MUST be executable in the repository.

#### Scenario: Required files exist in the repository

- **WHEN** the developer lists `scripts/backup/`
- **THEN** every required file listed above is present, the Dockerfile builds without error, and the n8n workflow JSON files are valid JSON

#### Scenario: Recovery recipient is NOT present (v3 single-recipient design)

- **WHEN** the developer lists `scripts/backup/recipients/`
- **THEN** only `primary.txt` is present; `recovery.txt` is intentionally absent. The replant adversarial review (2026-06-01) confirms this absence as a Blocker-fixing condition for v3 archive

#### Scenario: Shell workers use strict error handling

- **WHEN** the developer inspects each `.sh` worker under `scripts/backup/workers/`
- **THEN** the script includes `set -euo pipefail` within the first 10 lines (allowed to appear after a brief comment block + `_common.sh` sourcing block — the previous spec wording requiring this on "the first non-shebang line" was unnecessarily strict; the intent is that fail-fast IS enabled before any pipeline step executes, and the current layout satisfies that)

#### Scenario: Shell workers are executable in the repository

- **WHEN** the developer runs `git ls-files --stage scripts/backup/workers/*.sh`
- **THEN** every listed file has mode `100755`

#### Scenario: Node runner has pinned dependencies

- **WHEN** the developer inspects `scripts/backup/runner/`
- **THEN** both `package.json` and `package-lock.json` exist, the lockfile is committed, and `npm ci` reproduces the dependency tree without contacting registries beyond what `package-lock.json` resolves

#### Scenario: Built runner image carries the primary recipient and pg_dump 17

- **WHEN** the `myfinance-backup-runner` image has been built and a fresh container is started from it
- **THEN** `docker run --rm myfinance-backup-runner test -f /opt/myfinance-backup/recipients/primary.txt` exits 0; the first 4 bytes of the file are `age1`; `docker run --rm myfinance-backup-runner test -f /opt/myfinance-backup/recipients/recovery.txt` exits NON-zero (file intentionally absent); AND `docker run --rm myfinance-backup-runner pg_dump --version` outputs a line matching `^pg_dump \(PostgreSQL\) 17\.` (M9 — version-matched to Supabase Postgres 17.6)

### Requirement: Sidecar runner HTTP contract

The system SHALL expose a `myfinance-backup-runner` container on the Docker network `n8n_net` that listens on TCP port 8080 and provides an HTTP API consumed by the n8n backup workflows. The endpoints SHALL be:

- `GET /healthz` — liveness only. No authentication. Returns `200` with body `{"status":"ok","version":"<git-sha-or-tag>"}` whenever the process is running, including during an in-flight run.
- `GET /status` — read-only status. No authentication. Returns `200` with body `{"lastSuccess":{...}|null,"lastPreop":{...}|null,"lastVerify":{...}|null,"lastDrill":{...}|null}` derived from **R2 (`r2:my-finance-view-backups/status/{last-success,last-preop,last-verify,last-drill}.json`)** which is the canonical source of truth — NOT from the runner's local cache. The `lastDrill` key surfaces `status/last-drill.json` for the disaster-drill watchdog (finding #5 fix); when the file is missing the runner returns `lastDrill: null` rather than 404. If R2 is unreachable the endpoint returns `503 {"error":"r2_unreachable"}` so the Watchdog treats stale-data as a workflow error rather than a false-OK.
- `POST /run/daily` — triggers the daily backup pipeline (pg_dump → tar → age (single recipient) → rclone upload → post-upload SHA-256 re-verify → weekly/monthly promote → chained restore-verify → Kuma push). Synchronous. Typical duration 3–6 min.
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

The system SHALL emit a success heartbeat to a self-hosted Uptime Kuma Push Monitor on every completed daily run. The push URL SHALL be stored as the n8n credential `MYFINANCE_BACKUP_KUMA_PUSH_URL`, surfaced as a placeholder in `.env.example`, and MUST NOT be committed to the repository (it carries an unguessable token equivalent to a secret). The Kuma monitor SHALL be configured with heartbeat interval = 24 hours and grace period = 6 hours, so a missing push for more than 30 hours triggers a Kuma-side alert through whichever channels the operator has wired inside Kuma. **The Kuma monitor MUST have AT LEAST ONE downstream notification channel wired before this change activates (finding #8 fix);** permissible channels include Telegram, Discord, Gmail SMTP, ntfy.sh, or any generic webhook supported by Kuma. A Kuma monitor without a wired channel detects the missed heartbeat in its UI but pages no one — that silent-Kuma failure mode is precisely what this Requirement forbids.

The Kuma push is the **in-cluster dead-man-switch** leg of the three-leg alerting (alongside ntfy.sh and Gmail SMTP). It specifically covers the scenario where the n8n workflow never ran but the VPS itself is alive. A failure of the daily run SHALL NOT push to Kuma (the absence of the ping is what makes Kuma alert); explicit ntfy + Gmail alerts still fire from the worker as long as the runner can reach the internet. The whole-VPS-outage case is NOT covered by Kuma alone (Kuma is on the same VPS); the operator accepts that compensating signal under v3 comes from the parallel Gmail-ingest workflow noticing the same outage (see Three-leg alerting Requirement rationale).

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
