## Why

Supabase remote currently holds three months of irreplaceable production transaction data for the single user, ingested from Gmail+n8n — there is no staging, no replica, no documented backup, and the Supabase Free tier's dashboard backups are not directly downloadable. The next `/opsx:apply` against this database is the one-time Flyway baseline (`flyway-migrations` §7), and every future schema migration after V004+ is also a write. Without a verified backup and a tested restore path, a single fat-fingered `flyway:migrate` against the wrong URL — or a Supabase incident — would erase the entire dataset with no recovery option. This change must land before §7 of `flyway-migrations` is unblocked.

## Threat model

This section defines what this change defends against and — critically — what it does NOT. Adversarial-review findings that target out-of-scope adversaries are auto-rejected per `docs/workflow.md` Gate C policy.

**Adversary in scope:**
- **Opportunistic cloud-storage breach.** An attacker who obtains read access to the R2 bucket (leaked token, Cloudflare account compromise, S3-misconfiguration class bugs) MUST NOT be able to read the snapshot plaintext.
- **Supabase incident.** Account suspension, accidental delete, regional outage, or compromise that destroys or exposes the remote DB.
- **Operator fat-finger.** A wrong `flyway:migrate`, ad-hoc `psql` against prod, or MCP `apply_migration` to the wrong project erases or corrupts data.
- **VPS process failure.** n8n, the sidecar, or Kuma silently dies and the daily snapshot stops running without anyone noticing.

**Adversary explicitly OUT of scope (v1):**
- **Local forensic adversary with physical disk access** to the operator's Windows workstation or to printers in the operator's home/office. Findings about NTFS journal recovery, Volume Shadow Copies, `cipher /W`, Tails live-USB key generation, network-printer cache scrubbing, handwriting identities from screen, etc. are auto-rejected.
- **Whole-VPS outage detection from outside the VPS.** An off-VPS dead-man-switch (healthchecks.io, UptimeRobot, BetterStack) is deferred — if Kuma + the parallel Gmail-ingest workflow going silent prove insufficient in practice, a 30-min follow-up change adds it.
- **Public-internet attacker against a pre-op webhook.** The pre-op snapshot is operator-only and triggered from the n8n UI; there is no public webhook to defend. Findings about HMAC replay protection, IP allowlists, regex hardening of webhook payloads, etc. are auto-rejected.
- **Adversary controlling a TLS-trusted MITM** between the VPS and Supabase / R2 / ntfy. Standard TLS+pinned-Pooler-host is the floor; nothing in this change attempts certificate pinning, mutual TLS, or out-of-band verification of remote peers.
- **Nation-state-level adversary.** No hardware tokens (Yubikey), no air-gapped key generation, no operational security beyond "keep the paper in a locked drawer at home".

**Assets being protected:**
- The three-month (and growing) `myfinance` schema dataset — financial transactions, accounts, categories, balances.
- The single `auth.users` row that owns those records (so foreign keys restore correctly).

**Assets explicitly NOT protected by this change:**
- Other Supabase `auth.*` machinery (identities, mfa_factors, sessions). If the operator ever uses federated login, that state is rebuilt by re-authenticating, not restored from these backups.
- Supabase Storage buckets (not in use yet).
- The `public` schema (Chatwoot leftovers, scheduled for drop in TASK-DT-02).

## What Changes

- Add a scheduled `pg_dump` job (custom format, compressed) that snapshots the `myfinance` schema plus the entire `auth.users` table from the Supabase Postgres remote. **Clarification (finding #16):** the dump uses `pg_dump -t auth.users` which captures the full table, NOT only FK-referenced rows. For a single-user system this is one row and the distinction is academic; if the project later acquires federated-login users or other auth state, ALL `auth.users` rows are included in every daily snapshot. Operator is aware of and accepts this privacy posture; documented in `scripts/backup/README.md §2.5.6`.
- Encrypt every snapshot at rest with **`age` against a single recipient** (printed identity paper stored in a sealed envelope at a secure physical location; the matching identity file on the operator's Windows workstation under user-restricted NTFS ACLs). Store the encrypted artefact off-machine and off-Supabase (primary: cloud object storage; secondary: external disk rotated monthly). **Dual-recipient with a recovery paper at a second physical location was considered and deferred** — see `design.md` Decision 2 for the trade-off; rationale lives in the out-of-scope adversary list under `## Threat model` above.
- Run an automated restore-verification job that runs **immediately after every daily snapshot** (chained, not on a separate weekly schedule): pull the just-uploaded snapshot, restore it into an ephemeral Postgres (Docker), run a fixed set of read queries (row counts per table), and fail loudly if any query fails or returns implausible values. The same chain runs after every pre-op snapshot too.
- Define a retention policy: daily snapshots kept 30 days, weekly snapshots kept 90 days, monthly snapshots kept 12 months, **pre-op snapshots kept 90 days** (long enough to catch slow-discovery migration bugs), plus a `quarantine/` prefix with 365-day retention for snapshots that uploaded successfully but failed restore-verify.
- Document a manual on-demand backup procedure for pre-operation snapshots — operator triggers `MyFinanceBackup-PreOp` **from the n8n UI** (Execute Workflow with `reason` field filled) before `flyway:migrate`, `flyway:baseline`, or any ad-hoc write to Supabase. **No public webhook** in v1: zero internet-facing attack surface, zero IP allowlist to maintain when traveling. If non-interactive trigger (e.g. from `/opsx:apply` automation) ever becomes a need, adding it later is a bounded follow-up.
- Wire alerting through **three independent channels**: ntfy.sh push + Gmail SMTP email for explicit failure dispatch from inside the VPS + self-hosted Uptime Kuma Push Monitor as in-cluster dead-man-switch. An off-VPS dead-man-switch (e.g. healthchecks.io) is deferred — the parallel Gmail-ingest workflow on the same VPS provides a secondary signal that the operator notices within hours when the VPS is down.
- **Process documentation (NOT enforced in code)**: from this change forward, the documented rule is that any write operation against Supabase remote should be preceded by either a verified daily snapshot within the last 24 h or a pre-op snapshot within the last 60 min. The rule lives in `docs/development-guide.md` and auto-memory `supabase-production-data`; there is NO build-time, CI-time, or runtime gate. The previous draft labelled this "BREAKING — process only" — that wording overclaimed because nothing actually enforces it; the spec now reflects the honest reality (a documentation gate honoured by operator discipline, the `/opsx:apply` checklist, and the `adversarial-review` skill).

## Capabilities

### New Capabilities
- `database-backups`: Scheduled, encrypted, off-site snapshots of the Supabase Postgres database, with automated restore verification, retention policy, alerting, and a documented manual on-demand procedure that operators run before any write operation against the remote.

### Modified Capabilities
<!-- None. `backend-runtime` is unaffected (this runs out-of-band of the Spring Boot app).
     `database-migrations` (introduced by the in-flight `flyway-migrations` change) does NOT
     change its requirements — but its §7 task is operationally gated on this change's
     archival. The gate is documented in `flyway-migrations/tasks.md`, not enforced via
     a spec-level requirement, so no delta spec is needed here. -->

## Impact

- **Code**: New scripts and runner under `scripts/backup/` (Node + Express HTTP sidecar, bash workers `backup-daily.sh` / `backup-preop.sh` / `verify-restore.sh` / `alert.sh`, `Dockerfile.runner`, `docker-compose.yml`, four n8n workflow JSONs, integration smoke-test `test-smoke.sh` + fixtures under `test-fixtures/`). No Spring Boot application code changes — the runner is an out-of-band sibling of `database/`, not a Java module.
- **SPEC.md amendment (finding #2 fix):** `SPEC.md` "Estilo arquitectónico" and `docs/base-standards.md §2` get an "Infrastructure carve-out" amendment formalizing that infrastructure services (n8n, this sidecar, Traefik, Uptime Kuma) live as separate processes under `scripts/<infra-domain>/` outside the application-domain microservices prohibition. The sidecar introduced by this change is the first formalized infrastructure-tier service under the carve-out.
- **Scheduling**: n8n workflows on the operator's existing VPS (no GitHub Actions). One Schedule Trigger runs the daily snapshot + chained restore-verify; one watchdog Schedule Trigger reads R2 status JSON daily; the pre-op snapshot is triggered manually from the n8n UI by the operator (no webhook, no external trigger).
- **Secrets**: Adds the following out-of-band secrets (split between n8n credentials and the sidecar `.env.local` on the VPS — never committed): `BACKUP_DB_PASSWORD`, `BACKUP_R2_ACCESS_KEY_ID`, `BACKUP_R2_SECRET_ACCESS_KEY`, `BACKUP_R2_ACCOUNT_ID`, `MYFINANCE_BACKUP_AGE_IDENTITY` (verify-only — only needed for restore drill), `MYFINANCE_BACKUP_NTFY_TOPIC`, `MYFINANCE_BACKUP_GMAIL_APP_PASSWORD`, `MYFINANCE_BACKUP_KUMA_PUSH_URL`, `MYFINANCE_BACKUP_RUNNER_SECRET` (n8n ↔ sidecar shared header). The single age recipient file (public key) IS committed at `scripts/backup/recipients/primary.txt`; the matching identity is NEVER committed.
- **Storage**: Cloudflare R2 bucket `my-finance-view-backups` (operator already owns it). Bucket-side lifecycle rules enforce the retention policy: `daily/` 30d, `weekly/` 90d, `monthly/` 365d, **`pre-op/` 90d**, **`quarantine/` 365d**. Estimated cost: < $0.10/month — single-user dataset (< 100 MB projected) sits inside R2's 10 GB free tier with room to spare.
- **Docs**: New "Backup & disaster recovery" section in `docs/development-guide.md` replacing the temporary "Supabase remote operations" warning box added by `flyway-migrations` §6.2. New `openspec/specs/database-backups/spec.md`. Cross-reference from `SPEC.md §12` and from `docs/data-model.md §3`.
- **Notion**: New TASK-DB-BACKUP épica or equivalent entry on the project page.
- **Unblocks**: `flyway-migrations` §7 (Supabase baseline) — **operator-discipline-gated** by this change being archived AND a recent verified backup existing. The gate is documentation-only (see Decision 8 and the "Process gate is documentation-only" Requirement — nothing in code refuses to run `flyway:migrate` when `status/last-success.json` is stale). The honest framing: when the operator opens `/opsx:apply` for `flyway-migrations §7`, task 0 of that change's `tasks.md` SHALL be "verify pre-op snapshot taken within the last 60 minutes" (using `openspec/templates/supabase-write-checklist.md`), and the operator+adversarial-review backstop catches missing-snapshot evidence — that is the actual unblock mechanism, not a runtime hook. The earlier wording "gated on this change being archived" overclaimed automated enforcement (finding #6 fix).
- **Dependencies**: `postgresql-client-17` (version-matched to Supabase Postgres 17.6), `age` (encryption), `rclone` (R2 transport with `provider = Cloudflare`), Docker CLI (to spawn the ephemeral verify Postgres). All installed inside the `myfinance-backup-runner` image via apk; the VPS itself only needs Docker. Spring app deps unchanged.
- **Risks accepted in design**: VPS-single-failure-domain (no off-VPS dead-man-switch in v1 — Kuma + parallel Gmail-ingest silence are the operator's signal that the VPS is down; off-VPS pinger is a follow-up if Kuma proves insufficient), age identity on VPS during verify runs (in-memory only, never written to persistent disk), pre-op snapshot manually triggered from n8n UI (no public webhook in v1 — no internet attack surface, accepted that automation can't drive it from outside the n8n UI), monthly external-disk archive remaining a manual operator habit (calendar reminder, accepted slip risk).
