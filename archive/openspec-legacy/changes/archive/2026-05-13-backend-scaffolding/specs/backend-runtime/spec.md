## ADDED Requirements

### Requirement: Spring Boot application bootstrap with profile selection

The system SHALL provide a Spring Boot 3.4+ application running on Java 25 that boots from a single entrypoint class `com.myfinanceview.MyFinanceViewApplication`, accepts a Spring profile via `spring-boot.run.profiles` or `SPRING_PROFILES_ACTIVE`, and loads `application.yml` plus an optional profile-specific `application-{profile}.yml` (`local`, `test`, `prod`) for overrides.

#### Scenario: Application starts in local profile against Docker Postgres

- **WHEN** the developer runs `./mvnw spring-boot:run -Dspring-boot.run.profiles=local` with the Docker Compose Postgres container up on port 5433
- **THEN** the application boots without exceptions, binds HTTP on port 8080, and writes an INFO log line that includes `profile=local`, `java=25`, and `virtualThreads=true`

#### Scenario: Spring application context loads cleanly in tests

- **WHEN** `MyFinanceViewApplicationTests.shouldLoadSpringContextWhenStarting` runs under `@SpringBootTest`
- **THEN** the test passes, asserting that all auto-configured beans resolve and no bean creation exception is thrown

### Requirement: Liveness health endpoint

The system SHALL expose `GET /actuator/health` as an unauthenticated endpoint that returns HTTP 200 with JSON body `{"status":"UP"}` when the application is running. Detail keys (DB, disk space, etc.) MUST NOT be exposed (`management.endpoint.health.show-details=never`).

#### Scenario: Health endpoint returns UP when application is running

- **WHEN** an HTTP client issues `GET /actuator/health` against a running instance
- **THEN** the response status is 200 and the JSON body equals `{"status":"UP"}`, with no `components` or `details` key present

#### Scenario: Health endpoint is reachable without authentication

- **WHEN** the request to `/actuator/health` is made without an `Authorization` header
- **THEN** the response status is 200 (the endpoint is allow-listed for unauthenticated access)

### Requirement: Build info endpoint

The system SHALL expose `GET /actuator/info` as an unauthenticated endpoint that returns HTTP 200 with at minimum an empty JSON object `{}` and optionally additional info contributors (build, git) when configured.

#### Scenario: Info endpoint responds successfully

- **WHEN** an HTTP client issues `GET /actuator/info`
- **THEN** the response status is 200 and the body is valid JSON

### Requirement: Virtual threads enabled at runtime

The system SHALL run the embedded servlet container and Spring task executors on virtual threads (Project Loom) by setting `spring.threads.virtual.enabled=true` in `application.yml` defaults. Reactive stacks (WebFlux) MUST NOT be introduced.

#### Scenario: Virtual threads property resolves to true

- **WHEN** the integration test reads `Environment.getProperty("spring.threads.virtual.enabled")` after context load
- **THEN** the value equals `true`

### Requirement: Modular-by-domain package layout

The system SHALL organize Java sources under `com.myfinanceview` in modules by bounded context (not by technical layer). The required top-level packages are: `api/controller`, `api/dto`, `api/exception`, `domain/transaction`, `domain/category`, `domain/merchant`, `domain/billing`, `domain/savings`, `db/repository`, `db/jooq`, and `config`. Each package MUST exist (with `package-info.java` if otherwise empty) so the convention is enforced from day one. Clean Architecture by layers, hexagonal ports-and-adapters maximalism, JPA/Hibernate, and Lombok MUST NOT be introduced.

#### Scenario: All canonical packages exist in source tree

- **WHEN** the developer lists `src/main/java/com/myfinanceview/` recursively
- **THEN** every package listed above is present and contains at least one `.java` file (entrypoint or `package-info.java`)

### Requirement: Local Postgres development environment via Docker Compose

The system SHALL provide a `docker/docker-compose.yml` definition that runs `postgres:17` exposed on host port 5433 with a two-phase init orchestrator (`database/init-db.sh`). The orchestrator SHALL first apply `database/local/V000__local_supabase_stubs.sql` (local-only Supabase parity stubs — see "Local Supabase compatibility stubs" requirement below) and then apply `database/migrations/V001..Vn` in alphabetical order. On `docker compose up -d` from a clean volume the container ends with the full `myfinance` schema and V003 seed data applied. The local DB credentials default to user `myfinance` / password `localpassword` / db `myfinance_local` and MUST be documented in `.env.example`.

#### Scenario: Docker Compose Postgres seeds the myfinance schema

- **WHEN** the developer runs `docker compose -f docker/docker-compose.yml up -d` from a clean state
- **THEN** the container reaches healthy state and `psql -h localhost -p 5433 -U myfinance -d myfinance_local -c "SELECT count(*) FROM myfinance.categories"` returns a count greater than or equal to 19 (seeded by V003)

#### Scenario: Testcontainers can run Postgres 17 with the same migrations

- **WHEN** `PostgresTestcontainerTest.shouldStartPostgresContainerWithSeedSchemaWhenInitialised` runs
- **THEN** a `PostgreSQLContainer<>("postgres:17")` starts, the test applies `database/local/V000` then `database/migrations/V001..V003` in order via JDBC, executes `SELECT count(*) FROM myfinance.categories`, and the result is greater than or equal to 19

### Requirement: Local Supabase compatibility stubs

The system SHALL provide `database/local/V000__local_supabase_stubs.sql` containing the minimum surface a vanilla `postgres:17` needs for V001+V002 to apply cleanly — namely: schema `auth`, table `auth.users(id uuid PRIMARY KEY)`, function `auth.uid()`, and roles `anon`, `authenticated`, `service_role`. The stub file MUST be idempotent (`IF NOT EXISTS` / `OR REPLACE` / DO-block guards) and MUST live in `database/local/`, strictly separate from `database/migrations/`, so that the future Flyway adoption (TASK-DB-06) can point exclusively at `database/migrations/` and never accidentally apply local-only artefacts to Supabase remote.

#### Scenario: V000 stub applies cleanly against vanilla Postgres

- **WHEN** the orchestrator runs `database/local/V000__local_supabase_stubs.sql` against a freshly-initialised `postgres:17` container
- **THEN** the SQL executes without error, the `auth` schema exists, `auth.users` accepts inserts and FK references, `auth.uid()` is callable, and the three Supabase roles exist

#### Scenario: V000 stub is idempotent on re-runs

- **WHEN** the orchestrator applies V000 twice against the same database
- **THEN** the second run completes without error (no duplicate object errors), confirming `IF NOT EXISTS` and DO-block guards

#### Scenario: Real migrations folder contains only production migrations

- **WHEN** `database/migrations/` is listed
- **THEN** no `V000` file is present; only `V001__initial_schema.sql`, `V002__rls_policies.sql`, `V003__seed_data.sql` (and any future Vn that is intended for Supabase remote)

### Requirement: Maven build with reproducible JDK 25 contract

The system SHALL declare `java.version=25` and use `spring-boot-starter-parent:3.4.x` in `pom.xml`. The repository MUST ship Maven Wrapper (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`) so contributors and CI run an identical Maven version. The command `./mvnw verify` MUST complete successfully on a clean checkout when Docker is available for Testcontainers.

#### Scenario: Maven verify succeeds on a clean checkout

- **WHEN** a contributor clones the repository, ensures Docker is running, and executes `./mvnw verify`
- **THEN** the command exits 0, all unit, contract, and integration tests pass, and the build artifact under `target/` is produced

#### Scenario: CI runs the build on JDK 25 Temurin

- **WHEN** a pull request is opened against `main`
- **THEN** GitHub Actions workflow `.github/workflows/ci.yml` runs on `ubuntu-latest` with `actions/setup-java@v4` configured for `distribution: temurin`, `java-version: 25`, executes `./mvnw verify`, and reports green

### Requirement: HikariCP connection pool sized for Supabase free tier

The system SHALL configure `spring.datasource.hikari.maximum-pool-size=5` in `application.yml` defaults to respect the Supabase free-tier connection limit. Any change to this value MUST be accompanied by a comment explaining why.

#### Scenario: Hikari pool maximum is 5 by default

- **WHEN** the integration test reads `Environment.getProperty("spring.datasource.hikari.maximum-pool-size")`
- **THEN** the value equals `5`

### Requirement: jOOQ dependency present but codegen gated

The system SHALL include `org.jooq:jooq:3.19+` and the `org.postgresql:postgresql:42.7+` JDBC driver as runtime dependencies in `pom.xml`. The `jooq-codegen-maven` plugin SHALL be declared with execution `<phase>none</phase>` by default, activatable only via Maven profile `-P codegen`. This change MUST NOT produce generated jOOQ classes; that responsibility belongs to a later change.

#### Scenario: Default Maven verify does not execute jOOQ codegen

- **WHEN** the developer runs `./mvnw verify` without `-P codegen`
- **THEN** the build does not invoke `jooq-codegen-maven`, no files are written to `target/generated-sources/jooq/`, and no Supabase remote connection is opened

### Requirement: Environment variable contract documented

The system SHALL ship a `.env.example` file at the repository root listing every environment variable required by the application (Supabase URL, anon key, service-role key, JWT secret, DB host/port/name/schema/user/password, webhook secret, local DB port) without real values. The `.env.local` file MUST be gitignored. Secrets MUST NOT be committed.

#### Scenario: Repository contains env example but no env values

- **WHEN** the developer inspects the repository
- **THEN** `.env.example` exists and is tracked, `.env.local` is listed in `.gitignore`, and a grep for known Supabase secret prefixes returns no matches outside `.env.example` and documentation files

### Requirement: Application root .gitignore covers build and IDE artefacts

The system SHALL include a root `.gitignore` that excludes at minimum: `target/`, `target/generated-sources/jooq/`, `*.class`, `.idea/`, `*.iml`, `.vscode/`, `.env.local`, `HELP.md`, and OS-specific files (`.DS_Store`, `Thumbs.db`).

#### Scenario: Build outputs and IDE files are ignored

- **WHEN** the developer runs `./mvnw verify` and opens the project in IntelliJ IDEA
- **THEN** `git status` reports no changes for `target/`, `.idea/`, or any generated artefacts
