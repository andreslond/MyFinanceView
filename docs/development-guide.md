# Development Guide — MyFinanceView

> How to set up, run, and contribute. Specializes [base-standards.md](base-standards.md) and [backend-standards.md](backend-standards.md) for the day-to-day developer workflow.

---

## 1. Prerequisites

- **JDK 25** (OpenJDK or Temurin). Verify: `java --version`.
- **Maven 3.9+**. Verify: `mvn --version`.
- **Docker Desktop** (or compatible) for local Postgres + Testcontainers.
- **Git** with the project's `.gitconfig` user set.
- (Recommended) **IntelliJ IDEA Ultimate** for jOOQ and Spring Boot tooling.

## 2. Environment Variables

Copy `.env.example` to `.env.local` (gitignored) and fill in:

```bash
# Supabase (project akkoqdjmmozyqdfjkabg)
SUPABASE_URL=https://akkoqdjmmozyqdfjkabg.supabase.co
SUPABASE_ANON_KEY=...
SUPABASE_SERVICE_ROLE_KEY=...       # backend only — never to frontend
SUPABASE_JWT_SECRET=...              # for Spring Security JWT validation

# Direct Postgres (jOOQ codegen + Testcontainers seeding)
DB_HOST=db.akkoqdjmmozyqdfjkabg.supabase.co
DB_PORT=5432
DB_NAME=postgres
DB_SCHEMA=myfinance
DB_USER=...
DB_PASSWORD=...

# Internal webhook
WEBHOOK_SECRET=...                   # for POST /feedback/transaction

# Local Docker port (avoid 5432 collision)
LOCAL_DB_PORT=5433
```

## 3. Local Postgres (Docker Compose)

`docker/docker-compose.yml` mounts SQL files from `backend/database/` (init-db.sh + local/V000 + migrations/V001..V003) into the Postgres container.

Start: `docker compose -f docker/docker-compose.yml up -d`.
Stop: `docker compose -f docker/docker-compose.yml down`.

## 4. Database Migrations

Migrations live in `backend/database/migrations/` with naming `V{n}__{description}.sql` (Flyway style, even though Flyway is not yet wired). Current numbering:

- V001..V003 — baseline schema (applied to Supabase remote)
- V004..V008 — pending TASK-DB-01..05 (see [data-model.md §3](data-model.md))
- V009 — pending TASK-SG-DB-01 (savings goals)

**Apply order is strict** (FK dependencies). Apply locally first, smoke-test, then to remote Supabase via SQL Editor or `supabase db push` once we wire Flyway/Liquibase.

## 5. jOOQ Code Generation

All Maven commands run from `backend/` (where `pom.xml` and `mvnw` live).

After every migration:

```bash
cd backend
./mvnw jooq-codegen:generate
```

Configuration target: `backend/pom.xml` `<plugin>jooq-codegen-maven</plugin>` pointing at:
- the **local** Postgres (port 5433) for development, or
- the **remote** Supabase via `DB_*` env vars for accurate codegen against production state.

Generated classes go to `backend/target/generated-sources/jooq/` (gitignored).

## 6. Running the App

### Profile `local` (default for dev)
```bash
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```
Hits the local Docker Postgres. Server on `http://localhost:8080`.

### Profile `prod` (against Supabase)
Set env vars from §2 and:
```bash
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=prod
```

### Health check
```bash
curl http://localhost:8080/actuator/health
# {"status":"UP"}
```

## 7. Running Tests

All from `backend/`:

```bash
cd backend

# All tests
./mvnw test

# Just unit tests (fast)
./mvnw test -Dtest='*Test'

# Just contract tests
./mvnw test -Dtest='*ContractTest'

# Just integration tests (slow, Testcontainers)
./mvnw test -Dtest='*IntegrationTest'
```

Testcontainers spins up a real Postgres 17 with the same schema for each test class. Expect ~10–30 s startup per class on first run.

## 8. Spec-Driven Workflow

For **domain work** (`domain/**`), use the Uncle Bob harness. See [AGENTS.md](../AGENTS.md) (nav map) and [docs/uncle-bob/workflow.md](uncle-bob/workflow.md) for the full pipeline. Quick summary:

```bash
# 1. Refine the story (optional)
/enrich-us {notion-url or text}

# 2. Harness pipeline — run as craftsman_lead, spawn subagents:
#    spec_partner  → project-spec.md
#    gherkin_author → features/<name>.feature
#    ⏸ approve .feature in chat
#    tdd_craftsman → RED/GREEN/REFACTOR
#    judge         → APPROVED or REJECTED
#    mutation_tester → PIT 100%

# 3. Commit + PR
/commit

# 4. Sync docs
/update-docs
```

For **non-domain work** (controllers, jOOQ repos, migrations, infra, auth), use `backend-developer` directly — no harness pipeline.

## 9. Branching & Commits

- Branch name: `task/{TASK-ID}-short-description` (e.g. `task/BE-04-get-accounts`).
- Commits in English, imperative, ≤ 70 chars subject. Body explains WHY.
- Co-author line on AI-assisted commits: `Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>`.
- One commit per task by default. Multi-commit only when truly distinct concerns.
- Never force-push to main. Never `--amend` a pushed commit.

## 10. Common Tasks

### Generate a new domain module skeleton
Manually:
```
backend/src/main/java/com/myfinanceview/domain/{name}/
  {Name}Service.java
  {Name}.java                    ← domain type if needed (Record)
backend/src/main/java/com/myfinanceview/db/repository/{Name}Repository.java
backend/src/main/java/com/myfinanceview/db/jooq/Jooq{Name}Repository.java
backend/src/main/java/com/myfinanceview/api/controller/{Name}Controller.java
backend/src/main/java/com/myfinanceview/api/dto/{Name}DTO.java
backend/src/test/java/com/myfinanceview/...   ← parallel test packages
```

### Re-run codegen after a schema change
```bash
cd backend
./mvnw jooq-codegen:generate
```

### Reset local DB
```bash
docker compose -f docker/docker-compose.yml down -v
docker compose -f docker/docker-compose.yml up -d
```
The `-v` flag drops volumes; migrations in `backend/database/migrations/` re-run on start.

## 11. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `JWT signature invalid` | Wrong `SUPABASE_JWT_SECRET` | Re-pull from Supabase dashboard |
| jOOQ codegen 0 tables | Wrong `DB_SCHEMA` | Set `DB_SCHEMA=myfinance` |
| Testcontainers timeout on start | Docker daemon not running | Start Docker Desktop |
| 401 on every endpoint | JWT missing/expired | Re-login via Supabase auth |
| Connection pool exhausted | Hikari max < concurrency | Bump max or audit for leaks |

## 12. Backup & Disaster Recovery

Source of truth: [`archive/openspec-legacy/specs/database-backups/spec.md`](../archive/openspec-legacy/specs/database-backups/spec.md) (archived spec, historical); operator runbook lives at [`scripts/backup/README.md`](../scripts/backup/README.md).

### 12.1 Daily cadence

The backup sidecar (`myfinance-backup-runner`, Node + Express on the VPS, same network as n8n) runs every day at 02:30 America/Bogota driven by the `MyFinanceBackup-Daily` n8n workflow:

1. `pg_dump` the `myfinance` schema + the `auth.users` table from the Supabase Session Pooler (IPv4 path).
2. Bundle the dumps into a single `.tar` and encrypt with `age` against the primary recipient (committed at `scripts/backup/recipients/primary.txt`). Single-recipient design — operator owns the matching identity on their Windows PC AND on one printed paper at physical location A.
3. Upload `daily/YYYY-MM-DD.tar.age` to Cloudflare R2 bucket `my-finance-view-backups`.
4. Post-upload SHA-256 re-verify (re-download from R2 + recompute) catches multipart-truncation false-positives.
5. On Sundays, promote the daily artefact to `weekly/YYYY-Www.tar.age` via server-side `rclone copyto`. On day-of-month=1, promote to `monthly/YYYY-MM.tar.age`.
6. Chained restore-verify (reviewer Q2 fix): download the just-uploaded artefact, decrypt, restore into an ephemeral `postgres:17` container, run the SQL probe suite. **Quarantine any artefact that fails verify** (server-side move to `quarantine/<timestamp>-<source-prefix>-<filename>`).
7. Upsert `status/last-success.json` + `status/last-verify.json` on R2 with the SHA-256, ISO-8601 UTC timestamp, duration, and probe results.
8. On any failure, the worker calls `dispatch_alert` which fires **ntfy.sh push** + **Resend transactional email** in parallel (v1 alerting; see §12.3 below).

**Retention (v1, operator decision 2026-06-01):** R2 has ONE bucket-side lifecycle rule — `myfinance-daily-30d` deleting `daily/` objects after 30 days. The `weekly/`, `monthly/`, `pre-op/`, `quarantine/` prefixes accumulate without expiry; the operator spot-checks `pre-op/` size monthly per the §10.5 calendar reminder.

### 12.2 Backup before any Supabase write

Any write operation against the Supabase remote `myfinance` schema or `auth.users` table **should be preceded** by either:

- a successful **daily snapshot** within the previous 24 hours (check `status/last-success.json` on R2), or
- a successful **pre-op snapshot** taken within the previous 60 minutes for the specific operation about to run.

This expectation covers:

- Flyway `migrate`, `baseline`, `repair`, `clean` against the Supabase remote.
- Ad-hoc DDL or DML via `psql` or the Supabase SQL editor.
- MCP `apply_migration` and MCP `execute_sql` against the MyFinanceView project.
- Any ad-hoc shell script that opens a connection with write privileges.

**The gate is documentation-only.** There is no CI check, no runtime hook, no MCP-side enforcement — the rule is honoured by operator discipline and by the `adversarial-review` skill flagging missing snapshot evidence on a change proposal. The earlier draft of this section labelled the rule "BREAKING — process only"; that wording overclaimed and has been removed (B6 fix in the `supabase-backup-policy` design.md).

### 12.3 Pre-op procedure

To trigger an on-demand snapshot before a migration:

1. Open the n8n UI at `https://n8n.datachefnow.com`.
2. Navigate to the `MyFinanceBackup-PreOp` workflow.
3. Click **Execute Workflow**, edit the workflow inputs to set `reason` to a filename-safe slug matching `^[A-Za-z0-9._+-]{3,60}$` (examples: `flyway-baseline`, `v4.1-migration`, `manual-export-2026-06-15`).
4. Click Execute. The execution runs synchronously (~3–5 min) and the sidecar's JSON response surfaces in the n8n execution log.
5. **Only proceed with the irreversible operation if the response is HTTP 200 and `verifyResult.probes` are green.** An HTTP 500 with a `verify_failed` or `upload_corrupted` body means the snapshot is in `quarantine/` and provides no protection.

**v1 (operator decision 2026-06-01):** there is no public webhook — the n8n UI is the only entry point. Automation that needs to drive a pre-op from outside the n8n UI is a bounded follow-up.

### 12.4 Alerting (v1)

Two parallel channels (ntfy.sh push + Resend transactional email) reach the operator through TWO independent code paths that share the same destinations:

1. **`workers/alert.sh`** (inside the runner container) fires on worker-side failures. Inline `dispatch_alert` calls cover the SHA-mismatch quarantine path and the verify-failed quarantine path; the script-level `ERR` trap (round-4 review M2 fix) catches every other non-zero exit (pg_isready / pg_dump / tar / age / rclone / status upsert) and dispatches with the line number + the last 20 log lines. The function fires `curl` against ntfy and Resend in parallel using PID-tracked `wait` (round-4 review B1 fix) so the at-least-one-succeeds contract is honoured.
2. **`scripts/backup/n8n/MyFinanceBackup-DispatchAlert.json`** (an n8n sub-workflow) fires on n8n-side failures via the `MyFinanceBackup-ErrorHandler` workflow's Error Trigger. This catches workflow-engine errors that never reach the worker — e.g. the runner returning a 5xx the bash script could not catch, or the n8n Schedule Trigger failing to fire. The workflow fans out to the same two HTTP nodes: an n8n HTTP Request to `https://ntfy.sh/<topic>` and an n8n HTTP Request to `https://api.resend.com/emails` authenticated with an `HTTP Header Auth` credential (`Authorization: Bearer <API_KEY>` — n8n auto-injects).

Configuration storage (see `tasks.md` §8.4 for the operator setup steps):

- **n8n credentials:** `MYFINANCE_BACKUP_RESEND_API_KEY` (HTTP Header Auth credential named `MyFinance Backup Resend (HTTP Header Auth)`), `MYFINANCE_BACKUP_AGE_IDENTITY` (Generic credential), `MYFINANCE_BACKUP_RUNNER_SECRET` (Generic credential).
- **n8n container environment variables:** `MYFINANCE_BACKUP_NTFY_TOPIC`, `MYFINANCE_BACKUP_ALERT_FROM` (= `alerts@datachefnow.com`), `MYFINANCE_BACKUP_ALERT_TO` (= operator inbox). Exposed via the n8n compose `environment:` block / `env_file`. The DispatchAlert workflow reads them as `$env.X`. These three values are non-secret config (topic is unguessable but stable; from/to do not rotate) — storing them in `$env.*` keeps the DispatchAlert workflow JSON portable.
- **Runner container environment variables (sidecar `.env.local`):** `MYFINANCE_BACKUP_NTFY_TOPIC`, `MYFINANCE_BACKUP_RESEND_API_KEY`, `MYFINANCE_BACKUP_ALERT_FROM`, `MYFINANCE_BACKUP_ALERT_TO`. Read by `alert.sh` via plain `$VAR` bash interpolation.

**Failure modes uncovered in v1 (accepted by the operator):**

- Silent Schedule-Trigger non-fire of `MyFinanceBackup-Daily` while the VPS is up (no in-cluster Watchdog in v1).
- Whole-VPS outage (silences both alert channels). Mitigated by the operator's external uptime monitor on `n8n.datachefnow.com`.

Both Uptime Kuma (in-cluster dead-man-switch) and healthchecks.io (off-VPS) were considered and deferred; reinstating either is a bounded follow-up if a silent non-fire is observed.

### 12.5 Recovery procedure (paper identity drill)

If the Windows PC identity file is lost or destroyed:

1. Retrieve the paper from the sealed envelope at physical location A.
2. On a clean machine (NOT the primary PC if you suspect it is compromised), type the identity into a local file: `identity.txt`.
3. Download one snapshot from R2: `rclone copy r2:my-finance-view-backups/daily/<latest>.tar.age .`
4. Decrypt: `age -d -i identity.txt < <snapshot>.tar.age > snapshot.tar`
5. Extract: `tar -xf snapshot.tar` → produces `auth-users.dump`, `myfinance.dump`, `README.txt`.
6. Restore into a fresh Postgres: see `scripts/backup/README.md §2.5.4 Operator runbook` for the full restore script (pre-creates `auth.users(id uuid PRIMARY KEY)` stub before restoring; `pg_restore --data-only` on the auth dump; full `pg_restore` on the myfinance dump).
7. Wipe the typed identity file and the decrypted plaintext: `shred -u identity.txt snapshot.tar`.
8. If the paper is degraded (smudged, water-damaged), re-print from the still-valid PC identity and re-file at location A. Do an annual recurrence drill (§2.5.7 of the backup runbook); the cadence cue in v1 is a calendar reminder on January 15th (Watchdog OVERDUE alert was dropped together with Uptime Kuma).

**Catastrophic key-loss path:** if BOTH the PC identity AND the paper are simultaneously lost, every existing encrypted snapshot is permanently unreadable. Documented and accepted under the single-recipient design (`supabase-backup-policy/proposal.md ## Threat model`).

### 12.6 Key rotation

Annual cadence (or immediately after suspected compromise). See `scripts/backup/README.md §2.5.5` for the full table. Secrets to rotate:

- **age primary identity** — rebuild + redeploy runner image with new `recipients/primary.txt`.
- **R2 token** — generate new token in Cloudflare, update `.env.local` + rclone config.
- **Resend API key** — revoke + regenerate on the verified `datachefnow.com` domain.
- **ntfy topic** — generate new slug + re-subscribe phone.
- **Runner shared secret** — update env var + n8n credential.

**v1 cuts (no longer rotated):** Gmail App Password (replaced by Resend), Uptime Kuma push URL (Kuma deferred), healthchecks.io URL (off-VPS dead-man-switch deferred).
