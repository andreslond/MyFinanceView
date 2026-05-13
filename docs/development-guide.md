# Development Guide — MyFinanceView

> How to set up, run, and contribute. Specializes [base-standards.md](base-standards.md) and [backend-standards.md](backend-standards.md) for the day-to-day developer workflow.

---

## 1. Prerequisites

- **JDK 25** (OpenJDK or Temurin). Verify: `java --version`.
- **Maven 3.9+**. Verify: `mvn --version`.
- **Docker Desktop** (or compatible) for local Postgres + Testcontainers.
- **Node.js 20.19+** for OpenSpec CLI. Verify: `node --version`.
- **OpenSpec CLI**: `npm install -g @fission-ai/openspec@latest`.
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

`docker/docker-compose.yml` mounts only the V000 local stubs and the init script. Flyway
(via Spring Boot startup) applies V001..Vn — not the Docker container.

Start: `docker compose -f docker/docker-compose.yml up -d`.
Stop: `docker compose -f docker/docker-compose.yml down`.

## 4. Database Migrations

Production migrations (`V001..Vn`) live at `src/main/resources/db/migration/` and ship
inside the application jar. Flyway applies them automatically on Spring Boot startup.
The local-only compatibility stubs (`V000__local_supabase_stubs.sql`) remain in
`database/local/` — they are applied by the Docker init orchestrator and Testcontainers
`@BeforeAll` hooks, **never** by Flyway.

Current numbering:

- V001..V003 — baseline schema (applied to Supabase remote manually; baselined via Flyway once `supabase-backup-policy` change is archived)
- V004..V008 — pending TASK-DB-01..05 (see [data-model.md §3](data-model.md))
- V009 — pending TASK-SG-DB-01 (savings goals)

**Apply order is strict** (FK dependencies).

**Applied migrations are immutable.** Never edit a versioned file after it has been applied to any environment. Corrections ship as a new `V<n+1>`.

### Running migrations locally

Spring Boot applies all classpath migrations at startup automatically:

```bash
# Start local Postgres (V000 applied by Docker init orchestrator on first run)
docker compose -f docker/docker-compose.yml up -d

# Spring Boot auto-applies Flyway on startup
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
# Expected log: "Successfully applied N migrations to schema "myfinance""
```

After that, confirm Flyway history:
```bash
psql -h localhost -p 5433 -U myfinance -d myfinance_local \
  -c "SELECT version, description, success FROM myfinance.flyway_schema_history ORDER BY installed_rank"
```

To reset local DB (e.g. after switching branches that change migrations):
```bash
docker compose -f docker/docker-compose.yml down -v
docker compose -f docker/docker-compose.yml up -d
# Then run the app to re-apply Flyway
```

### Supabase remote operations

> **STOP — Read before executing any command below.**
>
> Supabase remote holds 3 months of real, irreplaceable production transactions (single
> environment, no staging). Before running **any** `flyway:migrate` or `flyway:baseline`
> against Supabase:
> 1. Run a verified `pg_dump` of the `myfinance` schema.
> 2. Restore the dump into a local test Postgres and confirm row counts match.
> 3. Save the dump outside the repository (e.g. encrypted cloud storage).
>
> See memory `supabase-production-data` (2026-05-13) and the pending
> `supabase-backup-policy` OpenSpec change — **do not proceed until that change is archived**.

One-time baseline (after backup gate is satisfied, V001–V003 already applied manually):

```bash
# 1. Confirm no flyway_schema_history table exists yet
./mvnw -P db-migrate flyway:info \
  -Dflyway.url=jdbc:postgresql://db.akkoqdjmmozyqdfjkabg.supabase.co:5432/postgres \
  -Dflyway.user=... -Dflyway.password=... \
  -Dflyway.schemas=myfinance -Dflyway.defaultSchema=myfinance

# 2. Record the baseline (inserts one row — does NOT touch existing data)
./mvnw -P db-migrate flyway:baseline \
  -Dflyway.url=jdbc:postgresql://db.akkoqdjmmozyqdfjkabg.supabase.co:5432/postgres \
  -Dflyway.user=... -Dflyway.password=... \
  -Dflyway.schemas=myfinance -Dflyway.defaultSchema=myfinance \
  -Dflyway.baselineVersion=3 \
  -Dflyway.baselineDescription="Existing schema V001-V003 applied manually"

# 3. Verify: must show one row — version=3, type=BASELINE, success=true
./mvnw -P db-migrate flyway:info \
  -Dflyway.url=... -Dflyway.user=... -Dflyway.password=... \
  -Dflyway.schemas=myfinance -Dflyway.defaultSchema=myfinance
```

Recovery if history table is corrupted:
```bash
./mvnw -P db-migrate flyway:repair \
  -Dflyway.url=... -Dflyway.user=... -Dflyway.password=... \
  -Dflyway.schemas=myfinance -Dflyway.defaultSchema=myfinance
```

## 5. jOOQ Code Generation

After every migration:

```bash
mvn jooq-codegen:generate
```

Configuration target: `pom.xml` `<plugin>jooq-codegen-maven</plugin>` pointing at:
- the **local** Postgres (port 5433) for development, or
- the **remote** Supabase via `DB_*` env vars for accurate codegen against production state.

Generated classes go to `target/generated-sources/jooq/` (gitignored).

## 6. Running the App

### Profile `local` (default for dev)
```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```
Hits the local Docker Postgres. Server on `http://localhost:8080`.

### Profile `prod` (against Supabase)
Set env vars from §2 and:
```bash
mvn spring-boot:run -Dspring-boot.run.profiles=prod
```

### Health check
```bash
curl http://localhost:8080/actuator/health
# {"status":"UP"}
```

## 7. Running Tests

```bash
# All tests
mvn test

# Just unit tests (fast)
mvn test -Dtest='*Test'

# Just contract tests
mvn test -Dtest='*ContractTest'

# Just integration tests (slow, Testcontainers)
mvn test -Dtest='*IntegrationTest'
```

Testcontainers spins up a real Postgres 17 with the same schema for each test class. Expect ~10–30 s startup per class on first run.

## 8. Spec-Driven Workflow

Per change, follow [base-standards.md §3](base-standards.md):

```bash
# 1. Refine the user story (Notion URL or paste)
/enrich-us {notion-url or text}

# 2. Propose: generates openspec/changes/<id>/{proposal,design,specs,tasks}.md
/opsx:propose "GET /accounts endpoint"

# 3. Implement, TDD per task in tasks.md
/opsx:apply

# 4. Adversarial review
# Either skill: /adversarial-review
# Or subagent: Agent(subagent_type: "adversarial-reviewer", ...)

# 5. Commit + PR
/commit

# 6. Archive the change
/opsx:archive

# 7. Sync delta specs into canonical specs
/openspec-sync-specs

# 8. Sync docs
/update-docs
```

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
src/main/java/com/myfinanceview/domain/{name}/
  {Name}Service.java
  {Name}.java                    ← domain type if needed (Record)
src/main/java/com/myfinanceview/db/repository/{Name}Repository.java
src/main/java/com/myfinanceview/db/jooq/Jooq{Name}Repository.java
src/main/java/com/myfinanceview/api/controller/{Name}Controller.java
src/main/java/com/myfinanceview/api/dto/{Name}DTO.java
src/test/java/com/myfinanceview/...   ← parallel test packages
```

### Re-run codegen after a schema change
```bash
mvn jooq-codegen:generate
```

### Reset local DB
```bash
docker compose -f docker/docker-compose.yml down -v
docker compose -f docker/docker-compose.yml up -d
# Then boot the app to re-apply Flyway (see §4 Running migrations locally)
```
The `-v` flag drops volumes. The init orchestrator re-applies V000 on container start;
Spring Boot startup then re-applies V001..Vn via Flyway.

## 11. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `JWT signature invalid` | Wrong `SUPABASE_JWT_SECRET` | Re-pull from Supabase dashboard |
| jOOQ codegen 0 tables | Wrong `DB_SCHEMA` | Set `DB_SCHEMA=myfinance` |
| Testcontainers timeout on start | Docker daemon not running | Start Docker Desktop |
| 401 on every endpoint | JWT missing/expired | Re-login via Supabase auth |
| Connection pool exhausted | Hikari max < concurrency | Bump max or audit for leaks |
