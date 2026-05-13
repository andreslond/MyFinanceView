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

`docker/docker-compose.yml`:
```yaml
services:
  postgres:
    image: postgres:17
    environment:
      POSTGRES_DB: myfinance_local
      POSTGRES_USER: myfinance
      POSTGRES_PASSWORD: localpassword
    ports:
      - "5433:5432"
    volumes:
      - ./db/migrations:/docker-entrypoint-initdb.d
```

Start: `docker compose -f docker/docker-compose.yml up -d`.
Stop: `docker compose -f docker/docker-compose.yml down`.

## 4. Database Migrations

Migrations live in `database/migrations/` with naming `V{n}__{description}.sql` (Flyway style, even though Flyway is not yet wired). Current numbering:

- V001..V003 — baseline schema (applied to Supabase remote)
- V004..V008 — pending TASK-DB-01..05 (see [data-model.md §3](data-model.md))
- V009 — pending TASK-SG-DB-01 (savings goals)

**Apply order is strict** (FK dependencies). Apply locally first, smoke-test, then to remote Supabase via SQL Editor or `supabase db push` once we wire Flyway/Liquibase.

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
```
The `-v` flag drops volumes; migrations in `database/migrations/` re-run on start.

## 11. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `JWT signature invalid` | Wrong `SUPABASE_JWT_SECRET` | Re-pull from Supabase dashboard |
| jOOQ codegen 0 tables | Wrong `DB_SCHEMA` | Set `DB_SCHEMA=myfinance` |
| Testcontainers timeout on start | Docker daemon not running | Start Docker Desktop |
| 401 on every endpoint | JWT missing/expired | Re-login via Supabase auth |
| Connection pool exhausted | Hikari max < concurrency | Bump max or audit for leaks |
