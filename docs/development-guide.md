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

## 12. MVP Frontend Deploy (Vercel)

The frontend MVP from `openspec/changes/mvp-frontend-readonly/` is deployed to Vercel as a static SPA. It talks to Supabase directly via `supabase-js`; the backend Java is not yet in the deploy path.

### Production URL

`https://frontend-delta-murex-29.vercel.app`

(There is also a canonical deployment-id URL printed by `vercel deploy --prod`; the alias above is the stable shareable one.)

### Vercel project

- Owner: `andrestor2-gmailcoms-projects`
- Project: `frontend` (Vite framework auto-detected; root `frontend/`)
- Linked locally via `frontend/.vercel/project.json` (gitignored)

### Required env vars (production)

| Variable name | Source |
|---|---|
| `VITE_SUPABASE_URL` | Supabase dashboard → Settings → API → Project URL, or MCP `get_project_url(akkoqdjmmozyqdfjkabg)` |
| `VITE_SUPABASE_ANON_KEY` | Supabase dashboard → Settings → API → "anon public" (legacy JWT), or MCP `get_publishable_keys(akkoqdjmmozyqdfjkabg)` |

**Never commit the values.** The names live in `frontend/.env.example`; real values live in `frontend/.env.local` (gitignored) for local dev and in Vercel's project env config for production.

### Build command (chained CI gate)

Vercel runs the entire local script suite — `npm run typecheck && npm run lint && npm run test && npm run build` — as the build command. If any step fails, the deploy fails. Defined in `frontend/vercel.json`.

### Redeploy

```bash
cd frontend
vercel deploy --prod          # uses the linked project + stored env vars
```

For preview deploys (PRs, branches), drop `--prod`.

### Adding or rotating env vars

```bash
echo -n "value" | vercel env add VITE_NAME production
# or interactively (CLI prompts and asks for environments):
vercel env add VITE_NAME
```

After changing an env var, redeploy is required to bake the new value into the bundle (Vite inlines `import.meta.env.VITE_*` at build time).

### Smoke test

```bash
curl -I https://frontend-delta-murex-29.vercel.app/                # expect HTTP/2 200
curl -sS https://frontend-delta-murex-29.vercel.app/transactions   # SPA rewrite serves index.html, HTTP 200
```

### Renaming the project

The auto-generated project name `frontend` is generic. To rename to `myfinance-view`, do it in the Vercel dashboard (Project Settings → General → Project Name) or by running `vercel project rm frontend && vercel link --yes --project myfinance-view` from `frontend/`.

### Local dev against production Supabase

```bash
cd frontend
npm install                  # once
npm run dev                  # http://localhost:5173
```

`.env.local` must exist with the same two `VITE_*` vars. The dev server proxies nothing — Supabase is reached directly from the browser.

---

## Running the backend MVP locally (post backend-mvp-readonly)

1. Copy `.env.example` → `.env.local` and fill the five MVP backend vars:
   - `SUPABASE_JWT_JWKS_URI` (default points at the project's public JWKS endpoint — no secret).
   - `SUPABASE_JWT_ISSUER` (canonical issuer URL).
   - `SUPABASE_SERVICE_ROLE_KEY` (from Supabase dashboard → API → service_role).
   - `SUPABASE_DB_URL` (session-pooler JDBC URL — already populated as default).
   - `APP_CORS_ALLOWED_ORIGINS` (CSV; leave blank for local — `localhost:5173` is auto-allowed in `local` profile, `https://*.vercel.app` always).
2. Start Docker Desktop (Testcontainers + local docker-compose Postgres both need it).
3. Run the backend with the `local` profile:
   ```bash
   cd backend
   SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
   # Or: mvnd spring-boot:run  (much faster; see CLAUDE.md)
   ```
   The app listens on `:8080`. Hit `http://localhost:8080/actuator/health` to confirm `{"status":"UP"}`.
4. To call protected endpoints, mint a JWT via Supabase Auth (e.g. sign in from the frontend) and pass it as `Authorization: Bearer <jwt>`. The backend validates ES256 signatures against the public JWKS — no shared secret needed.
5. For tests: `mvnd test` (uses Testcontainers — no manual DB setup). `~/.testcontainers.properties` has `testcontainers.reuse.enable=true` so the Postgres container persists across runs.

**What's exposed in MVP:** 4 endpoints — `GET /api/v1/{transactions,accounts,categories}` and `PATCH /api/v1/transactions/{id}/category`. Only `/actuator/health` is unauthenticated; every other path under `/actuator/**` returns 404 by design (D11).
