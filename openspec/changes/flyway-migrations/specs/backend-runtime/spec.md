## MODIFIED Requirements

### Requirement: Local Postgres development environment via Docker Compose

The system SHALL provide a `docker/docker-compose.yml` definition that runs `postgres:17` exposed on host port 5433 with a single-phase init orchestrator (`database/init-db.sh`). The orchestrator SHALL apply only `database/local/V000__local_supabase_stubs.sql` — the local-only Supabase parity stubs (see "Local Supabase compatibility stubs" requirement below). All production migrations (`V001..Vn`) SHALL be applied by Flyway at Spring Boot startup against the running container, NOT by the orchestrator. On `docker compose up -d` from a clean volume the container ends with the V000 parity stubs applied; on the next `./mvnw spring-boot:run -Dspring-boot.run.profiles=local` the application boots, Flyway migrates V001..Vn against the container, and the full `myfinance` schema is present. The local DB credentials default to user `myfinance` / password `localpassword` / db `myfinance_local` and MUST be documented in `.env.example`.

#### Scenario: Docker Compose Postgres applies V000 parity stubs on first start

- **WHEN** the developer runs `docker compose -f docker/docker-compose.yml up -d` from a clean state
- **THEN** the container reaches healthy state, the `auth` schema, `auth.users` table, `auth.uid()` function, and the `anon`, `authenticated`, `service_role` roles all exist, and `myfinance.flyway_schema_history` does NOT yet exist (Flyway has not run)

#### Scenario: Spring Boot startup against the local container applies V001..Vn via Flyway

- **WHEN** the developer runs `./mvnw spring-boot:run -Dspring-boot.run.profiles=local` against the Docker Compose Postgres container that has only V000 applied
- **THEN** Flyway runs on startup, `myfinance.flyway_schema_history` is populated with one `success=true` row per classpath migration, `SELECT count(*) FROM myfinance.categories` returns at least 19 (V003 seed applied), and the application binds HTTP on port 8080

#### Scenario: Testcontainers can run Postgres 17 with the same migrations

- **WHEN** `PostgresTestcontainerTest.shouldStartPostgresContainerWithSeedSchemaWhenInitialised` runs
- **THEN** a `PostgreSQLContainer<>("postgres:17")` starts, the test applies `database/local/V000` then the classpath files `db/migration/V001__initial_schema.sql`, `V002__rls_policies.sql`, `V003__seed_data.sql` in order via JDBC, executes `SELECT count(*) FROM myfinance.categories`, and the result is greater than or equal to 19

### Requirement: Local Supabase compatibility stubs

The system SHALL provide `database/local/V000__local_supabase_stubs.sql` containing the minimum surface a vanilla `postgres:17` needs for V001+V002 to apply cleanly — namely: schema `auth`, table `auth.users(id uuid PRIMARY KEY)`, function `auth.uid()`, and roles `anon`, `authenticated`, `service_role`. The stub file MUST be idempotent (`IF NOT EXISTS` / `OR REPLACE` / DO-block guards) and MUST live in `database/local/`, strictly separate from `src/main/resources/db/migration/` (Flyway's classpath location for production migrations), so that Flyway points exclusively at production migrations and never accidentally applies local-only artefacts to Supabase remote.

#### Scenario: V000 stub applies cleanly against vanilla Postgres

- **WHEN** the orchestrator runs `database/local/V000__local_supabase_stubs.sql` against a freshly-initialised `postgres:17` container
- **THEN** the SQL executes without error, the `auth` schema exists, `auth.users` accepts inserts and FK references, `auth.uid()` is callable, and the three Supabase roles exist

#### Scenario: V000 stub is idempotent on re-runs

- **WHEN** the orchestrator applies V000 twice against the same database
- **THEN** the second run completes without error (no duplicate object errors), confirming `IF NOT EXISTS` and DO-block guards

#### Scenario: Flyway classpath contains only production migrations

- **WHEN** `src/main/resources/db/migration/` is listed
- **THEN** no `V000` file is present and no filename contains `local`, `stub`, or `supabase_stubs`; only `V001__initial_schema.sql`, `V002__rls_policies.sql`, `V003__seed_data.sql` (and any future Vn intended for Supabase remote) are present

#### Scenario: V000 is never applied to a Flyway-managed environment

- **WHEN** the integration test inspects `myfinance.flyway_schema_history` on any Flyway-managed container
- **THEN** no row in the history table has a script name containing `V000`, `local_supabase_stubs`, or any of the role names created by V000
