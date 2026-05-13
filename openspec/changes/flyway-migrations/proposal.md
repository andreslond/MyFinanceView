## Why

Migrations under `database/migrations/V001..V003` are currently applied two different ways: locally by a hand-rolled bash orchestrator (`database/init-db.sh`) on first Docker volume init, and manually against Supabase remote via the SQL editor. There is no schema-history table, no replay protection, no version contract — and the next five pending migrations (V004…V009 in `docs/data-model.md §3`) will land on Supabase, on local Docker, and on every Testcontainers integration test. Doing that without a migration tool is how a single user app gets into "what's actually applied where?" purgatory.

Flyway adoption was explicitly deferred at scaffolding time (see `backend-runtime` spec, "Local Supabase compatibility stubs" requirement: *"future Flyway adoption (TASK-DB-06) can point exclusively at `database/migrations/`"*). Now is the moment — before V004 ships.

## What Changes

- Add Flyway (`flyway-core` + `flyway-database-postgresql`) as a runtime dependency and let Spring Boot auto-configure it to run on application startup.
- Configure Flyway to manage **only** `database/migrations/` (classpath: `db/migration/`). The local-only `database/local/V000__local_supabase_stubs.sql` stays strictly out of Flyway's locations and continues to be applied by the Docker init orchestrator and Testcontainers `@BeforeAll` hooks.
- Baseline Supabase remote: V001, V002, V003 are already applied there, so configure `flyway.baseline-on-migrate=true` with `flyway.baseline-version=3` so first Flyway run on Supabase records the baseline without re-executing V001–V003. Local Docker (clean volume) and Testcontainers run all versions from scratch since they start empty after V000.
- Put the Flyway schema-history table inside the `myfinance` schema (`flyway_schema_history`), not `public`.
- Disable Flyway clean (`flyway.clean-disabled=true`) in every profile — destructive operation never permitted from the app.
- Simplify `database/init-db.sh`: drop phase 2 (the V001..Vn loop). The orchestrator now applies V000 only; everything else is Flyway's job.
- Move `database/migrations/V001..V003` into `src/main/resources/db/migration/` so they ship on the classpath (Flyway's default location), and update the Docker Compose volume mount to point at the new path. Keep filenames byte-identical.
- Provide a Maven profile `-P db-migrate` that runs `flyway:migrate` standalone against an arbitrary JDBC URL (used for the one-time Supabase baseline + future ops).
- Add a Testcontainers integration test that asserts: container starts → V000 stub applied → Flyway migrates → `myfinance.flyway_schema_history` shows V001–V003 (and any later versions) as `SUCCESS`.
- **BREAKING** (local dev only): contributors with an existing `myfinance_pgdata` Docker volume must `docker compose down -v` once to repopulate — because the orchestrator path changes. Documented in `docs/development-guide.md`.

## Capabilities

### New Capabilities
- `database-migrations`: Versioned, history-tracked schema migrations applied by Flyway at application startup against local Docker, Testcontainers, and Supabase remote — with strict separation of local-only compatibility stubs from production migrations.

### Modified Capabilities
- `backend-runtime`: The "Local Postgres development environment via Docker Compose" requirement changes — the init orchestrator no longer applies `V001..Vn`; only V000 stubs. V001+ are applied by Flyway at Spring Boot startup. The "Local Supabase compatibility stubs" requirement's scenario "Real migrations folder contains only production migrations" is preserved but its location moves (`src/main/resources/db/migration/`).

## Impact

- **Code**: `pom.xml` gains Flyway deps + `flyway-maven-plugin` (gated behind `-P db-migrate`). `application.yml` and profile overrides gain a `spring.flyway.*` block. `src/main/resources/db/migration/V001..V003.sql` added; `database/migrations/` deleted (or kept as a deprecated alias — see design).
- **Database**: New table `myfinance.flyway_schema_history` created automatically on first migrate. Supabase remote receives a one-time `flyway baseline` (admin op, documented). Three already-applied migrations (V001–V003) become tracked, retroactively.
- **Local dev**: One-time `docker compose down -v && up -d` required. `database/init-db.sh` reduced to V000 only.
- **Testcontainers**: Every integration test that boots Spring will now run Flyway on the container — adds ~100–300 ms per fresh container. Tests that bypass Spring (raw JDBC) keep applying V000+V001..V003 manually for now (no behavioural change).
- **Specs**: New `openspec/specs/database-migrations/spec.md`. Delta against `openspec/specs/backend-runtime/spec.md` updates two requirements.
- **Docs**: `docs/data-model.md §3` cross-references; `docs/development-guide.md` gets a "running migrations" section; `SPEC.md §12` item 4 closes.
- **Dependencies**: `org.flywaydb:flyway-core` (Spring Boot manages version → 10.x), `org.flywaydb:flyway-database-postgresql` (required for PG 15+ with Flyway 10). No transitive licensing concerns (Apache 2.0).
- **Unblocks**: V004 (TASK-DB-01), V005 (TASK-DB-03), V006 (TASK-DB-02), V007 (TASK-DB-04), V008 (TASK-DB-05), V009 (savings-goals) and every future schema change in this repo.
