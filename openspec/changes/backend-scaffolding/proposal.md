## Why

The repository has documentation, schema migrations (V001–V003), and an OpenAPI direction defined, but **no executable Java code yet**. Every subsequent backend task (TASK-BE-02 jOOQ codegen, TASK-BE-03 JWT security, TASK-BE-04 GET /transactions, …) assumes a running Spring Boot 3.4 / Java 25 / Maven / jOOQ project against a local Postgres. Without that skeleton, TDD cannot start and the spec-driven workflow has nothing to land on. This change unblocks Épica 3 by delivering the minimum viable runtime: a `MyFinanceViewApplication` that boots, exposes `/actuator/health`, runs on virtual threads, and is paired with a local `docker compose` Postgres 17 reproducing migrations V001–V003.

## What Changes

- Add `pom.xml` with Spring Boot 3.4.x parent, `java.version=25`, jOOQ 3.19+, Testcontainers 1.20+, REST-assured 5.5+, with `jooq-codegen-maven` declared but gated behind a `codegen` Maven profile (real codegen is TASK-BE-02).
- Add Maven Wrapper (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/`).
- Add root `.gitignore` covering `target/`, `target/generated-sources/jooq/`, IDE files, and `.env.local`.
- Add `.env.example` with the full environment variable set documented in [docs/development-guide.md §2](../../../docs/development-guide.md).
- Create `MyFinanceViewApplication` (`@SpringBootApplication`) in `com.myfinanceview`.
- Create the modular-by-domain package skeleton from [docs/backend-standards.md §2](../../../docs/backend-standards.md) (`api/{controller,dto,exception}`, `domain/{transaction,category,merchant,billing,savings}`, `db/{repository,jooq}`, `config/`), each with a `package-info.java` placeholder.
- Add `application.yml` (defaults), `application-local.yml` (local Docker DB on 5433), `application-test.yml`. Defaults set `spring.threads.virtual.enabled=true`, Hikari `maximum-pool-size=5`, and expose `/actuator/health,/actuator/info`.
- Add `database/local/V000__local_supabase_stubs.sql` — local-only parity stubs that create `auth.users`, `auth.uid()`, and Supabase built-in roles (`anon`, `authenticated`, `service_role`) so V001+V002 apply cleanly against a vanilla `postgres:17` (Supabase remote already provides them). The file lives in `database/local/` — **explicitly separate from `database/migrations/`** — so the future Flyway adoption (TASK-DB-06) sees only the real migrations.
- Add `database/init-db.sh` — two-phase orchestrator mounted into `/docker-entrypoint-initdb.d/` that applies V000 from `database/local/` then V001..V003 from `database/migrations/` in order.
- Add `docker/docker-compose.yml` running `postgres:17` on port 5433 with both `database/local/` and `database/migrations/` mounted plus the orchestrator script.
- Add `.github/workflows/ci.yml` running `./mvnw verify` on JDK 25 (Temurin) on push and PR.
- Add four tests (TDD-first per [docs/base-standards.md §5](../../../docs/base-standards.md)):
  - Context load test
  - Contract test for `GET /actuator/health` returning 200 `{"status":"UP"}`
  - Integration test asserting virtual threads are enabled
  - Testcontainers test confirming Postgres 17 starts with V001..V003 applied and `myfinance.categories` seed is present.

**Explicitly out of scope** (separate tasks): JWT validation, jOOQ codegen execution, ProblemDetail advice, business endpoints, Flyway/Liquibase wiring (TASK-DB-06 in Notion, queued as the next change), springdoc/Swagger UI, frontend.

## Capabilities

### New Capabilities

- `backend-runtime`: The Spring Boot application bootstrap surface. Owns: application startup with profile selection, `/actuator/health` and `/actuator/info` exposure, virtual-threads runtime, modular-by-domain package layout, local Postgres connectivity via Docker Compose, and the Maven build + CI contract. Future changes (JWT in TASK-BE-03, error handling in TASK-BE-04) will modify this capability with deltas.

### Modified Capabilities

_None — this is the first OpenSpec change in the project; no canonical specs exist yet._

## Impact

- **Code (new):** `pom.xml`, `mvnw*`, `.mvn/wrapper/`, `src/main/java/com/myfinanceview/**`, `src/main/resources/application*.yml`, `src/test/java/com/myfinanceview/**`, `docker/docker-compose.yml`, `.github/workflows/ci.yml`, `.gitignore`, `.env.example`.
- **Code (existing):** none modified; no Java code exists yet.
- **APIs:** introduces `GET /actuator/health` and `GET /actuator/info` (Spring Boot Actuator defaults). No business endpoints.
- **Dependencies:** Spring Boot 3.4.x, jOOQ 3.19+, PostgreSQL JDBC 42.7.x, HikariCP (transitive), Testcontainers 1.20+, REST-assured 5.5+. **Forbidden** (per [docs/backend-standards.md §1](../../../docs/backend-standards.md)): JPA/Hibernate, WebFlux, Lombok, Spring Cloud, Kafka.
- **Infrastructure:** local Docker Compose service on port 5433. No remote infra change (Supabase remote untouched).
- **Documentation:** `/update-docs` post-archive must mark TASK-BE-01 done in [SPEC.md §7](../../../SPEC.md) and tick the corresponding Notion card; [docs/development-guide.md](../../../docs/development-guide.md) sections 3, 6, 7 must be validated against the delivered scaffold.
- **Risk areas:** Java 25 availability in CI runners (mitigation: Temurin via `actions/setup-java@v4`); decision on committing jOOQ-generated code (this change records the decision in `design.md`: gitignored, regenerated on demand).
