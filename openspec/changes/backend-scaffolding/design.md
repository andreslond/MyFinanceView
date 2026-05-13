## Context

The MyFinanceView repository today contains documentation ([SPEC.md](../../../SPEC.md), [docs/](../../../docs/), [plans/](../../../plans/)), three SQL migrations (`database/migrations/V001..V003`), and OpenSpec scaffolding — **but zero Java sources, zero build files, and no local infrastructure**. Every subsequent backend task (TASK-BE-02..06 in [SPEC.md §7](../../../SPEC.md)) presumes a buildable Spring Boot project with a connection to Postgres and the modular-by-domain package layout already in place. This change is purely scaffolding: it stands up the runtime contract without introducing any business logic.

**Project constraints** that shape this design:

- **Stack is fixed** by [docs/backend-standards.md §1](../../../docs/backend-standards.md): Java 25, Spring Boot 3.4+, jOOQ 3.19+, Maven, Testcontainers, REST-assured. Lombok, JPA/Hibernate, WebFlux, Spring Cloud, and message brokers are explicitly forbidden.
- **Architecture is fixed** by [docs/base-standards.md §2](../../../docs/base-standards.md): modular monolith by domain. Clean Architecture by layers and hexagonal maximalism are rejected.
- **Testing rule** ([docs/base-standards.md §5](../../../docs/base-standards.md)): no mocking the database; integration tests use Testcontainers Postgres 17.
- **Single developer**: Andres Torres. Predictability beats flexibility. Decisions made once, documented once.

Stakeholders: solo developer + AI assistants (Claude Code agents `backend-developer`, `adversarial-reviewer`). No production users yet.

## Goals / Non-Goals

**Goals:**

- A `./mvnw verify` from a clean checkout exits 0 (with Docker running for Testcontainers).
- `./mvnw spring-boot:run -Dspring-boot.run.profiles=local` starts the app and `curl localhost:8080/actuator/health` returns 200 `{"status":"UP"}`.
- The package skeleton from [docs/backend-standards.md §2](../../../docs/backend-standards.md) is enforced from line one — including the empty domain packages — so the next TDD slice has no place to "accidentally" land code in the wrong package.
- Local Docker Postgres mirrors the remote Supabase `myfinance` schema (V001..V003) so Testcontainers tests and dev runs see identical structure.
- CI runs `./mvnw verify` on JDK 25 Temurin and reports green on PR.
- The four TDD-first tests are RED before any production code is written (per [docs/base-standards.md §3](../../../docs/base-standards.md)).

**Non-Goals:**

- **No JWT / Spring Security**. Endpoints are all public for this change — only `/actuator/health` and `/actuator/info` exist anyway. JWT lands in TASK-BE-03.
- **No jOOQ codegen execution**. Plugin is declared, gated by profile, not run. TASK-BE-02 will turn it on.
- **No business endpoints**. No controllers under `api/controller/` except whatever Actuator provides.
- **No Flyway / Liquibase wiring**. Migrations remain applied via Docker init scripts locally and via Supabase SQL Editor remotely. A separate task will introduce Flyway with baseline V003.
- **No springdoc / Swagger UI**. The OpenAPI YAML in `docs/api-spec.yml` remains the source of truth; UI rendering is deferred.
- **No ProblemDetail `@ControllerAdvice`**. There is no business endpoint that can throw a domain error yet — adding the advice without a caller would be speculative. It lands with TASK-BE-04.
- **No frontend code**. [docs/frontend-standards.md](../../../docs/frontend-standards.md) is still a placeholder.

## Decisions

### D1 — Java 25 with Spring Boot 3.4.x and no preview flags

We use Java 25 (released Sept 2025) with `<release>25</release>` in `maven-compiler-plugin`. The features we need (Records, Pattern matching for `switch`, Virtual Threads, Sealed types) are all **stabilised** in Java 25; we do not enable `--enable-preview`.

**Alternatives considered:**
- Java 21 LTS — safer for ops, but Java 25 is what [SPEC.md](../../../SPEC.md) and [docs/backend-standards.md](../../../docs/backend-standards.md) commit to. Reverting would require a SPEC.md amendment.
- Java 25 with preview features — adds churn at every minor release. Not worth it.

**Rationale:** matches authoritative docs, keeps the build stable.

### D2 — jOOQ codegen plugin declared but gated by Maven profile

The `jooq-codegen-maven` plugin is added to `pom.xml` with `<executions>` that bind to `<phase>none</phase>` and `<id>codegen</id>`. A Maven profile `<id>codegen</id>` activates the binding (`<phase>generate-sources</phase>`). Default builds (`./mvnw verify`) do not run codegen; only `./mvnw -P codegen generate-sources` does.

**Alternatives considered:**
- Run codegen on every `verify` — slow, requires DB access on every build (including CI), pollutes context.
- Skip the plugin entirely in this task — works but creates more churn in TASK-BE-02 (which would need to add the plugin AND run it).

**Rationale:** declare once, activate when needed. TASK-BE-02 only needs to flip the profile.

### D3 — jOOQ generated classes are NOT committed to git

`target/generated-sources/jooq/` is gitignored. Developers regenerate on demand via `./mvnw -P codegen generate-sources`. CI runs the codegen profile only on tasks/branches that touch the schema (out of scope here — for now CI doesn't run codegen, since this task ships zero jOOQ code).

[docs/backend-standards.md §3](../../../docs/backend-standards.md) says "decide once and stick to it" — this is the decision.

**Alternatives considered:**
- Commit generated classes — increases PR diffs, creates merge conflicts on schema changes, drifts vs. live schema. Worth it only if codegen is slow; for this schema it isn't.

**Rationale:** smaller diffs, no drift risk, simpler PR review. The trade-off is that anyone clone-then-open in IDE without running codegen sees red references; this is documented in [docs/development-guide.md §5](../../../docs/development-guide.md).

### D4 — Modular-by-domain packages created empty with `package-info.java`

We pre-create every package listed in [docs/backend-standards.md §2](../../../docs/backend-standards.md), even though most are empty. Each empty package gets a `package-info.java` with a one-line Javadoc stating the bounded context's responsibility. This makes the convention visible in any file tree and prevents future TDD slices from improvising a new location.

**Alternatives considered:**
- Create packages lazily as needed — flexible, but historically leads to "where does this go?" hesitation per task and inconsistent placement.

**Rationale:** the package layout *is* the architecture. Pre-creating it makes the architecture an enforced invariant, not a suggestion.

### D5 — No `@ControllerAdvice` / `ProblemDetail` in this change

Adding error-handling infrastructure before there is any business endpoint that throws a domain exception is speculative. Spring Boot's default error handler is acceptable for the two Actuator endpoints. TASK-BE-04 (`GET /transactions`) will introduce both the first domain endpoint and the `@ControllerAdvice` that maps exceptions to `ProblemDetail`.

**Alternatives considered:**
- Ship the advice now with placeholder mappings — violates [CLAUDE.md](../../../CLAUDE.md) "Don't add abstractions beyond what the task requires".

**Rationale:** zero callers means zero coverage means dead code. Defer.

### D6 — Migrations applied via Docker init-script orchestrator, not Flyway

For local Docker Postgres, a two-phase orchestrator script `database/init-db.sh` is mounted into `/docker-entrypoint-initdb.d/00_init.sh` and applies:

1. `database/local/V000__local_supabase_stubs.sql` — local-only parity stubs (see D9).
2. `database/migrations/V001..Vn` — real schema migrations (also applied to Supabase remote via SQL Editor).

For Testcontainers, the same two folders are read via `Files.readString()` and applied through `pg.createStatement().execute()` for explicit multi-file ordering.

A future task (**TASK-DB-06 / `flyway-migrations`**, already queued in Notion as the next change after `backend-scaffolding`) will introduce Flyway with a baseline at V003. At that point Flyway will be pointed at `database/migrations/` only — the `database/local/` folder stays invisible to it, so the local stubs never attempt to run against Supabase remote.

**Alternatives considered:**
- Introduce Flyway now — bigger blast radius (baseline decisions, history tables in `myfinance` schema, Supabase auth schema interactions). Defer is the right call; TASK-DB-06 is its own focused change.
- Keep V000 stub in `database/migrations/` and rename to non-Flyway pattern — easier, but the directory split is more robust against accidental Flyway picks. Adversarial review (2026-05-13) explicitly flagged the foot-gun.

**Rationale:** the two-folder design + orchestrator script keep this change tightly scoped while pre-emptively isolating local-only artefacts from the production migration set.

### D9 — Local Supabase compatibility stubs (`database/local/V000__local_supabase_stubs.sql`)

Surfaced during apply (2026-05-13): a vanilla `postgres:17` container does not have Supabase's `auth.users` table, `auth.uid()` function, or `anon`/`authenticated`/`service_role` roles. V001 has FK references to `auth.users(id)` (6 occurrences) and V002 grants RLS policies to these roles (30+ statements). Without a stub, neither local Docker Compose nor Testcontainers can apply V001+V002.

**Decision:** ship a minimal idempotent stub at `database/local/V000__local_supabase_stubs.sql` containing exactly:
- `CREATE SCHEMA IF NOT EXISTS auth;`
- `CREATE TABLE IF NOT EXISTS auth.users (id uuid PRIMARY KEY);` (only the FK target column)
- `CREATE OR REPLACE FUNCTION auth.uid()` reading `request.jwt.claim.sub` from session config
- DO-block-guarded `CREATE ROLE` for the three Supabase roles
- A single seeded `auth.users` row with deterministic UUID `00000000-...-001` for FK-bearing fixtures

**Where it lives:** `database/local/`, separate from `database/migrations/`, so future Flyway (TASK-DB-06) only sees the real migrations.

**Alternatives considered:**
- Use the Supabase Docker image instead of vanilla Postgres — larger image, less standard for local dev parity vs production schema-only.
- Put the stub at `database/migrations/V000__...` with a comment header — adversarial review flagged this as a foot-gun for Flyway adoption (header comments don't stop Flyway).

**Rationale:** keep "what runs against Supabase" and "what runs locally for parity" as physically separate folders. Comments rot; directories don't.

### D7 — `application-test.yml` left minimal; Testcontainers injects DB config dynamically

The `application-test.yml` does not hardcode DB host/port/credentials. Each Testcontainers-based test exposes the container's `jdbcUrl`, `username`, `password` via `@DynamicPropertySource` (per-test class) so the test profile remains portable across CI runners.

**Alternatives considered:**
- Hardcode a fixed test DB — fragile across machines and CI environments.

**Rationale:** standard Testcontainers pattern, documented in `PostgresTestcontainerTest`.

### D8 — GitHub Actions CI on `ubuntu-latest` with Temurin JDK 25

CI uses `actions/setup-java@v4` with `distribution: temurin`, `java-version: 25`. If Temurin 25 is not yet on hosted runners at the time of merge, fallback to `corretto` or `zulu` distributions is acceptable — Temurin is the preference, not a hard requirement.

**Alternatives considered:**
- Build a custom Docker image with JDK 25 — overkill for a solo project.
- Use `actions/setup-java@v3` — works, but v4 is current.

**Rationale:** lowest-friction path that satisfies the JDK 25 contract.

## Risks / Trade-offs

- **[Risk] JDK 25 availability on GitHub-hosted runners** → Mitigation: pin to Temurin 25 via `setup-java@v4`; if missing at execution time, swap distribution to `zulu` in a 1-line PR. The build itself is JDK-vendor-agnostic.
- **[Risk] Developer clones repo and opens IDE without running codegen → red references in `db/jooq/`** → Mitigation: there ARE no `db/jooq/` files in this change (only `package-info.java`). The first red references appear with TASK-BE-02, which will add a `## First-time setup` callout to [docs/development-guide.md](../../../docs/development-guide.md).
- **[Risk] Docker Compose port 5433 collision with another local Postgres** → Mitigation: `.env.example` documents `LOCAL_DB_PORT=5433` with a comment to change it locally if needed.
- **[Trade-off] Empty `package-info.java` files vs. a leaner tree** → Cost: ~15 trivial files. Benefit: package layout is self-documenting and shows up in IDE indexes immediately.
- **[Trade-off] No Flyway here** → Cost: local DB reset requires `docker compose down -v` rather than `mvn flyway:migrate`. Benefit: smaller change, no baseline-decision rabbit hole.
- **[Trade-off] No `ProblemDetail` advice yet** → Cost: any future endpoint will need to land alongside the advice or accept default Spring error JSON briefly. Benefit: no speculative dead code.

## Migration Plan

This change does not migrate existing code (there is none). Deployment is local-developer-machine only; no production environment exists yet.

**Apply order:**

1. Run `/opsx:apply` against this change → creates `pom.xml`, `mvnw`, packages, configs, tests in the order defined by `tasks.md`.
2. `docker compose -f docker/docker-compose.yml up -d` to start local Postgres.
3. `./mvnw verify` to confirm green build.
4. `./mvnw spring-boot:run -Dspring-boot.run.profiles=local` to confirm app boots.
5. `curl http://localhost:8080/actuator/health` to confirm endpoint.
6. Push branch → CI confirms green on JDK 25 → merge → `/opsx:archive` → `/update-docs`.

**Rollback:** the change adds new files only. Reverting the commit restores the prior state (docs + migrations only). No DB state changes, no remote infra touched.

## Open Questions

- **Q1 — Spring Boot exact minor version**: 3.4.x at the time of writing; pin to the latest stable patch when implementing. If Spring Boot 3.5+ ships before merge, evaluate; default to 3.4.x for predictability.
- **Q2 — Maven Wrapper version**: pin to `apache-maven-3.9.9` (latest stable in the 3.9 line) unless a later 3.9.x is available; do not jump to 4.x.
- **Q3 — Spring Boot Actuator info contributors**: ship with `management.info.env.enabled=true` and `management.info.git.enabled=true` (the `git-commit-id-plugin` would be needed for git info). For this change, leave info endpoint returning `{}` to keep the build artefact list minimal. Revisit when there is a reason to surface build/git metadata.
