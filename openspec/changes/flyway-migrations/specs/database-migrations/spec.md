## ADDED Requirements

### Requirement: Flyway runs at Spring Boot application startup

The system SHALL include `org.flywaydb:flyway-core` and `org.flywaydb:flyway-database-postgresql` on the runtime classpath and SHALL let Spring Boot auto-configure Flyway to execute `migrate()` against the configured `spring.datasource` before any other database-touching bean initialises. Flyway MUST be enabled by default (`spring.flyway.enabled=true`) in every profile except where explicitly opted out for non-database tests.

#### Scenario: Application boots and applies pending migrations against a clean local Postgres

- **WHEN** the developer runs `./mvnw spring-boot:run -Dspring-boot.run.profiles=local` against the Docker Compose Postgres container that has had only `V000__local_supabase_stubs.sql` applied
- **THEN** the application logs include a Flyway summary line such as `Successfully applied N migrations to schema "myfinance"`, the `myfinance.flyway_schema_history` table exists with one row per applied version, and HTTP `/actuator/health` returns 200

#### Scenario: Application startup fails fast if Flyway cannot reach the database

- **WHEN** the application boots with `spring.datasource.url` pointing at a host that does not accept connections
- **THEN** Spring context initialisation fails with a clear Flyway-attributed error before the embedded HTTP server binds, and exit code is non-zero

### Requirement: Migration files live on the classpath under `db/migration/`

The system SHALL place all production migration SQL files at `src/main/resources/db/migration/` so they ship inside the application jar. Files MUST follow Flyway versioned-migration naming `V<n>__<snake_case_description>.sql` (single underscore separates version from description, double underscore after the version). The directory `database/migrations/` MUST NOT exist after this change — its three prior files MUST be moved (preserving git history via `git mv`) to the classpath location with byte-identical content.

#### Scenario: Classpath contains the expected migration files

- **WHEN** the integration test enumerates resources matching `classpath:db/migration/V*__*.sql`
- **THEN** the result includes `V001__initial_schema.sql`, `V002__rls_policies.sql`, `V003__seed_data.sql` (and any later `V004+` that has been added) and excludes `V000__local_supabase_stubs.sql`

#### Scenario: Legacy migrations folder is removed

- **WHEN** the developer lists the repository root
- **THEN** `database/migrations/` does not exist; `database/local/V000__local_supabase_stubs.sql` is the only file remaining under `database/`

### Requirement: Flyway manages the `myfinance` schema and stores its history table there

The system SHALL configure Flyway with `spring.flyway.schemas=myfinance`, `spring.flyway.default-schema=myfinance`, and `spring.flyway.create-schemas=true`. The Flyway schema-history table MUST be `myfinance.flyway_schema_history` (default name, scoped to the configured schema). No Flyway artefacts MAY be created in `public`, `auth`, `storage`, or any other schema.

#### Scenario: History table exists in `myfinance` after first migrate

- **WHEN** Flyway completes its first migrate against a clean Postgres container
- **THEN** `SELECT to_regclass('myfinance.flyway_schema_history')` returns a non-null OID, and `SELECT to_regclass('public.flyway_schema_history')` returns NULL

#### Scenario: History table records each applied production migration exactly once

- **WHEN** Flyway has migrated a freshly-initialised database through V003
- **THEN** `SELECT version, description, success FROM myfinance.flyway_schema_history ORDER BY installed_rank` returns three rows for versions `1`, `2`, `3`, each with `success = true`, and no row references `V000` or the local stubs file

### Requirement: Flyway clean is disabled in every profile

The system SHALL set `spring.flyway.clean-disabled=true` in the base `application.yml` and MUST NOT override it to `false` in any profile (`local`, `test`, `prod`) or environment file. The Maven `flyway-maven-plugin` execution MUST NOT bind `flyway:clean` to any default Maven phase.

#### Scenario: Calling Flyway clean is rejected

- **WHEN** the integration test obtains the auto-configured `Flyway` bean and invokes `flyway.clean()`
- **THEN** a `FlywayException` is thrown whose message references `clean is disabled`, and no schema objects are dropped

### Requirement: Local-only Supabase parity stubs are excluded from Flyway management

The system SHALL keep `database/local/V000__local_supabase_stubs.sql` outside Flyway's `locations`. The default `spring.flyway.locations=classpath:db/migration` (implicit) MUST be retained, and V000 MUST NOT be copied or symlinked into `src/main/resources/db/migration/`. Flyway MUST NOT, under any configuration accessible from `application.yml` or profile overrides, apply V000 to any database.

#### Scenario: V000 does not appear in Flyway info output

- **WHEN** the integration test runs `flyway.info().all()` against a Spring-managed container
- **THEN** no entry in the returned `MigrationInfo[]` has a script name containing `V000` or `local_supabase_stubs`

#### Scenario: Production-migration directory contains no local-only files

- **WHEN** `src/main/resources/db/migration/` is listed
- **THEN** every file's version is `≥ 1` and no filename contains `local`, `stub`, or `supabase_stubs`

### Requirement: Supabase remote is baselined at version 3 via a one-time Maven operation

The system SHALL NOT enable `spring.flyway.baseline-on-migrate` (default `false` remains). Migrating against Supabase remote — where `V001__initial_schema.sql`, `V002__rls_policies.sql`, and `V003__seed_data.sql` are already applied — MUST be preceded by a single manual baseline operation that records version `3` in `myfinance.flyway_schema_history`. The repository SHALL provide a Maven profile `db-migrate` that activates `flyway-maven-plugin` so an operator can run `./mvnw -P db-migrate flyway:baseline -Dflyway.url=... -Dflyway.user=... -Dflyway.password=... -Dflyway.baselineVersion=3 -Dflyway.baselineDescription="Existing schema V001-V003 applied manually"`. The procedure MUST be documented in `docs/development-guide.md`.

#### Scenario: Maven profile activates the Flyway plugin

- **WHEN** the developer runs `./mvnw -P db-migrate flyway:info -Dflyway.url=jdbc:postgresql://localhost:5433/myfinance_local -Dflyway.user=myfinance -Dflyway.password=localpassword -Dflyway.schemas=myfinance`
- **THEN** the command executes successfully and prints a Flyway info table that lists the migrations currently visible to Flyway

#### Scenario: Default Maven verify does not invoke any Flyway goal

- **WHEN** the developer runs `./mvnw verify` without `-P db-migrate`
- **THEN** the build does not execute `flyway:migrate`, `flyway:baseline`, `flyway:clean`, `flyway:info`, or `flyway:repair`, and no Flyway plugin output appears in the log

### Requirement: Migrations are immutable once applied

The system SHALL configure Flyway with `spring.flyway.validate-on-migrate=true` and `spring.flyway.out-of-order=false`. Editing the SQL of a migration that has already been recorded as `success=true` in any environment's history table MUST cause subsequent Flyway runs to fail with a checksum-mismatch error. The project policy "applied migrations are immutable; corrections ship as a new V<n+1>" MUST be documented in `docs/data-model.md`.

#### Scenario: Modifying an applied migration's SQL is caught at next migrate

- **GIVEN** a Postgres container that has Flyway-migrated through V003 successfully
- **WHEN** the test mutates the content of `V003__seed_data.sql` on the classpath (e.g. by editing a copy in a test resource) and reruns `flyway.migrate()` against the same container
- **THEN** Flyway throws a checksum-mismatch exception referencing version `3`, and no further DDL/DML is executed

### Requirement: Integration test asserts the full Flyway-on-Spring contract

The system SHALL include a Spring Boot integration test (`com.myfinanceview.db.FlywayMigrationIntegrationTest`) that boots a `postgres:17` Testcontainer with V000 pre-applied, points Spring at it via `@DynamicPropertySource`, lets the Spring context auto-run Flyway, and asserts: (a) the `myfinance.flyway_schema_history` table exists; (b) it contains a `success=true` row for each of V001, V002, V003 (and any subsequent V004+ that ship in the classpath at test time); (c) it contains zero rows referencing V000; (d) `SELECT count(*) FROM myfinance.categories ≥ 19`.

#### Scenario: Integration test passes on a clean container

- **WHEN** `./mvnw verify` runs `FlywayMigrationIntegrationTest.shouldRecordEveryClasspathMigrationInHistoryWhenSpringBoots`
- **THEN** the test passes, the Testcontainer boots, V000 is applied via the container's init script mechanism, Spring's auto-configured Flyway records V001..V003 (and any later versions) in `myfinance.flyway_schema_history` with `success = true`, the category-count assertion is met, and the container is torn down cleanly
