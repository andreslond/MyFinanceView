## Context

Today the repo has three migrations under `database/migrations/V001..V003`, already applied by hand to Supabase remote and applied by a bash orchestrator (`database/init-db.sh`) on first Docker volume init. A Testcontainers test (`PostgresTestcontainerTest`) re-applies them via raw JDBC. There is no `flyway_schema_history` anywhere — the source of truth for "what is applied" is folder content, not the database.

Five more migrations (V004…V009 — `display_name` ES, installments, cut/payment day, merchants, categorization fields, savings goals) are pending in `docs/data-model.md §3`. Without a migration tool, every one of them lands in three places via three different mechanisms (manual SQL on Supabase, init-db.sh loop locally, manual JDBC in test). That's a guarantee of drift.

The `backend-runtime` spec already telegraphs this change: its "Local Supabase compatibility stubs" requirement was deliberately written so that `database/local/V000__local_supabase_stubs.sql` lives separately from `database/migrations/`, *"so that the future Flyway adoption (TASK-DB-06) can point exclusively at `database/migrations/` and never accidentally apply local-only artefacts to Supabase remote."* The design below honours that constraint.

## Goals / Non-Goals

**Goals:**
- One mechanism — Flyway — applies V001..Vn against local Docker, Testcontainers, and Supabase remote.
- `flyway_schema_history` exists in `myfinance` on every environment, including Supabase (retroactively for V001–V003).
- Local-only `V000__local_supabase_stubs.sql` remains strictly outside Flyway's management and outside Supabase's reach.
- Pending V004..V009 ship as ordinary Flyway migration files with no further infra work.
- The Spring Boot app fails fast on startup if the database is in an unexpected state (validation on).

**Non-Goals:**
- Auto-running Flyway against Supabase from a contributor's laptop (Supabase migrations remain a deliberate, gated op via Maven profile).
- Rolling back via Flyway Undo — not supported in Flyway Community Edition and we don't need it for a single-user app. Rollback strategy is "write a forward migration".
- Migrating to Liquibase, dbmate, or sqitch. Flyway's SQL-only model is the right fit.
- Rewriting V001/V002/V003 content. Filenames and SQL stay byte-identical so existing Supabase rows are not re-touched.
- Removing `PostgresTestcontainerTest`'s raw-JDBC migration path — it's a useful "migrations apply against a vanilla Postgres" smoke test, independent of Spring auto-config.

## Decisions

### D1 — Migration files live on the classpath at `src/main/resources/db/migration/`

Move `database/migrations/V001__initial_schema.sql`, `V002__rls_policies.sql`, `V003__seed_data.sql` → `src/main/resources/db/migration/` (Flyway's default classpath location, no extra `spring.flyway.locations` config needed). Use `git mv` so blame is preserved.

**Why:** Classpath migrations ship inside the application jar. `bootRun` finds them automatically. `mvn test` finds them on the test classpath. The fat jar deployed to Supabase ops box (or executed via `java -jar`) finds them. No filesystem-path fragility, no Docker volume mount needed for the app to migrate — only for the init-db.sh V000 phase.

**Alternatives considered:**
- *Keep them in `database/migrations/`, configure `spring.flyway.locations=filesystem:./database/migrations`.* Works but couples the running process's CWD to the repo layout. Breaks for `java -jar`. Rejected.
- *Keep `database/migrations/` as a symlink-or-copy target.* Doubles the source of truth. Rejected.

### D2 — `V000__local_supabase_stubs.sql` stays in `database/local/`, off the Flyway classpath

V000 is applied by:
1. The Docker init orchestrator (mounted into the container).
2. Testcontainers tests, manually, in a `@BeforeAll` or via `Container.withCopyToContainer` + an init script wrapper.

It does not exist on `src/main/resources/db/migration/` and `spring.flyway.locations` is left at its default (which only scans the classpath), so Flyway literally cannot see V000.

**Why:** V000 creates Supabase parity surfaces (`auth.users` stub, `auth.uid()` function, `anon` / `authenticated` / `service_role` roles). Applying that to Supabase remote would either silently no-op (objects already exist) or, worse, ALTER something real. The physical-location split makes the mistake structurally impossible — there is no environment variable a contributor can flip that would accidentally migrate V000 to Supabase.

### D3 — Supabase remote is baselined at V3 via a one-time manual Maven op; `baseline-on-migrate` is OFF everywhere

Flyway config in `application.yml`:

```yaml
spring:
  flyway:
    enabled: true
    schemas: myfinance
    default-schema: myfinance
    create-schemas: true
    baseline-on-migrate: false
    clean-disabled: true
    validate-on-migrate: true
    out-of-order: false
```

For **local Docker** and **Testcontainers**: the `myfinance` schema is absent (after V000 runs, only the `auth` schema and roles exist). Flyway sees an empty target → no baseline needed → applies V001 (which `CREATE SCHEMA myfinance` … unless V001 doesn't; T2 in tasks verifies and adds `flyway.create-schemas=true` is sufficient) through Vn from scratch.

For **Supabase remote**: V001..V003 are already applied. We run, **once, manually**:

```
./mvnw -P db-migrate flyway:baseline \
  -Dflyway.url=jdbc:postgresql://db.akkoqdjmmozyqdfjkabg.supabase.co:5432/postgres \
  -Dflyway.user=... -Dflyway.password=... \
  -Dflyway.schemas=myfinance \
  -Dflyway.defaultSchema=myfinance \
  -Dflyway.baselineVersion=3 \
  -Dflyway.baselineDescription="Existing schema V001-V003 applied manually"
```

This inserts a single row into `myfinance.flyway_schema_history` marking V3 as the baseline. From then on, every Spring Boot startup against Supabase calls `migrate()`, which sees baseline = 3 and applies V004+ in order.

**Why this over `baseline-on-migrate=true`:** Auto-baseline is a foot-gun. If a contributor points Flyway at the *wrong* database (say, the staging Supabase fork) which happens to be empty or partially populated, auto-baseline silently records the wrong baseline version and now V001..V003 are skipped forever on that DB. Explicit, one-time, documented baseline op = no surprises.

**Alternatives considered:**
- *Repeatable `baseline-on-migrate=true` + `baseline-version=3`.* Works for Supabase but breaks local clean DBs where we want V001+ to actually run. Would require profile-specific overrides — ugly. Rejected.
- *Pre-insert the history row in V001 itself.* Conflates "the migration" with "tracking the migration". Rejected.

### D4 — `flyway-maven-plugin` declared with default phase `none`, activated via `-P db-migrate`

```xml
<plugin>
  <groupId>org.flywaydb</groupId>
  <artifactId>flyway-maven-plugin</artifactId>
  <executions><execution><phase>none</phase></execution></executions>
</plugin>
```

Profile `db-migrate` (defined in `<profiles>`) sets the plugin bindings so contributors can call `mvn -P db-migrate flyway:info`, `flyway:migrate`, `flyway:baseline`, `flyway:repair` against any JDBC URL passed via `-Dflyway.url=...`.

**Why:** The Spring Boot app autoruns Flyway on startup — that's the normal path. The Maven plugin is the escape hatch for ops work that doesn't involve booting the app: Supabase baseline, ad-hoc `info` to see what's applied, post-incident `repair`. Gating behind a profile means `./mvnw verify` never accidentally touches a real DB.

### D5 — `clean-disabled=true` is non-negotiable, on every profile

`spring.flyway.clean-disabled=true` is set in the base `application.yml` and never overridden. There is no scenario in this repo — local, test, or prod — where `flyway clean` (DROP everything) is acceptable. If a contributor wants a fresh local DB, `docker compose down -v && up -d` is the documented path.

**Why:** Clean is destructive and irreversible. The app has zero need for it. Disable it once at the root and let any developer who wants to override think hard about the choice.

### D6 — Init orchestrator collapses to V000-only; Docker Compose volume mount for `database/migrations/` is removed

`database/init-db.sh` after this change:

```bash
#!/bin/bash
set -euo pipefail
echo "[init-db] Applying local Supabase parity stubs..."
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
     -f /var/lib/myfinance/local/V000__local_supabase_stubs.sql
echo "[init-db] Done. Flyway will apply V001..Vn at Spring Boot startup."
```

`docker/docker-compose.yml` drops the `../database/migrations:/var/lib/myfinance/migrations` mount (the Postgres container no longer needs to see them — only the app does).

**Why:** The orchestrator's job collapses to "make Postgres look enough like Supabase for V001 to apply". That's V000 only. Everything else is Flyway's job, executed by the app process (or the Maven plugin), not the database container's entrypoint.

### D7 — Testcontainers strategy: keep the plain-JDBC smoke test, add a Spring + Flyway integration test

The existing `PostgresTestcontainerTest` (raw JDBC, no Spring) continues to apply V000 → V001 → V002 → V003 by reading files and `Statement.execute()`. Its job — *"migrations apply standalone against a vanilla `postgres:17`"* — is unaffected. It just needs its V001..V003 paths updated to point at `src/main/resources/db/migration/` instead of `database/migrations/`.

A NEW test, `FlywayMigrationIntegrationTest` under `src/test/java/com/myfinanceview/db/` (`@SpringBootTest`, `@Testcontainers`), boots a Postgres 17 container, pre-applies V000 via Testcontainers' init mechanism, points Spring at it via `@DynamicPropertySource`, lets Flyway auto-migrate on context load, then asserts:
- `myfinance.flyway_schema_history` contains rows for V001, V002, V003 (and any future V004+) with `success=true`.
- `myfinance.flyway_schema_history` does NOT contain a row referencing `V000`.
- `myfinance.categories` row count ≥ 19 (V003 seed ran).

**Why two tests:** They cover different contracts. Plain-JDBC test = "the SQL files themselves are valid against a clean Postgres". Spring test = "Flyway's auto-config, location resolution, baseline policy, and history-table behaviour all wire up correctly". Losing either leaves a blind spot.

### D8 — `flyway-database-postgresql` is added explicitly

Flyway 10 (the version Spring Boot 3.4 manages) split out the DBMS-specific code into separate jars. For PostgreSQL, `org.flywaydb:flyway-database-postgresql` MUST be on the classpath or Flyway fails at startup with `No database found to handle …`. Add it as a `runtime` dependency next to `flyway-core`.

## Risks / Trade-offs

- **Contributor's local Docker volume becomes stale → first `docker compose up -d` after the merge fails or skips migrations.** → Mitigation: `tasks.md` includes a `docs/development-guide.md` update documenting the one-time `docker compose down -v` step; the `pom.xml` change is gated behind a clear merge note.
- **Supabase baseline is a manual step → easy to forget.** → Mitigation: dedicated task in `tasks.md` with the exact `mvn` invocation; document the procedure in `docs/development-guide.md` under "Supabase remote operations"; first Spring Boot startup against an un-baselined Supabase fails loudly (Flyway: *"Found non-empty schema 'myfinance' without schema history table"*) — a noisy failure is better than silent drift.
- **Flyway checksum validation could fire if V001/V002/V003 are ever edited in place.** → Mitigation: project policy is already "migrations are immutable once merged"; codify in `docs/data-model.md` and reinforce in `docs/backend-standards.md`. Out-of-order set to false so a misnumbered migration would also fail fast.
- **Testcontainers integration test costs ~100–300 ms more per fresh container as Flyway runs.** → Accepted. The single-user app's test suite is small; the safety win dwarfs the cost.
- **`flyway-database-postgresql` is a non-zero dependency footprint (~1 MB).** → Accepted; required for Flyway 10.
- **If V001 doesn't itself `CREATE SCHEMA myfinance`, Flyway must create it before running V001 (else V001's `CREATE TABLE myfinance.…` fails).** → Mitigation: `flyway.create-schemas=true` + `flyway.default-schema=myfinance` covers it. Task T2 verifies V001 content and the actual behaviour in the new integration test.
- **Spring Security / auth.users dependency:** V001/V002 reference `auth.users`. Locally that's a V000 stub; on Supabase it's the real table. Order is preserved (V000 always runs before Flyway in local; on Supabase the real `auth.users` exists since project creation). No new risk introduced.

## Migration Plan

1. **Pre-merge** (this change's `/opsx:apply`):
   - Add Flyway deps + plugin to `pom.xml`.
   - Move V001–V003 to classpath. Update `PostgresTestcontainerTest` paths.
   - Add Flyway config to `application.yml`. Add new `FlywayMigrationIntegrationTest`.
   - Simplify `init-db.sh`. Update `docker/docker-compose.yml` volume mounts.
   - `./mvnw verify` runs green (both tests pass; Testcontainers boots, Flyway migrates, history table reflects V001–V003).

2. **Post-merge, one-time Supabase baseline** (manual ops, documented in `docs/development-guide.md`):
   ```
   ./mvnw -P db-migrate flyway:info -Dflyway.url=...        # confirm empty history
   ./mvnw -P db-migrate flyway:baseline -Dflyway.baselineVersion=3 ...
   ./mvnw -P db-migrate flyway:info -Dflyway.url=...        # confirm V3 baseline row
   ```

3. **Next migration (V004)** lands as `src/main/resources/db/migration/V004__categories_display_name_es.sql` — no infra work; pure SQL change → covered by Flyway on next startup against each env.

**Rollback plan:**
- If something goes wrong post-merge before the Supabase baseline runs, Supabase is untouched (Flyway hasn't been pointed at it yet) — revert the merge commit and nothing on the remote DB is harmed.
- If the integration test catches a bug in the Flyway config locally, fix and re-run. Local Docker volume is disposable.
- If the Supabase baseline op itself misfires (e.g. wrong baseline version recorded), `mvn -P db-migrate flyway:repair` adjusts the history table; worst case, manually `DELETE FROM myfinance.flyway_schema_history` and re-baseline.

## Open Questions

- **Does V001 contain `CREATE SCHEMA myfinance IF NOT EXISTS`?** Task T2 verifies; if not, `flyway.create-schemas=true` handles it before V001 runs. Either way works.
- **Should `flyway.placeholders.*` be wired up now (for env-specific values like the Supabase JWT issuer)?** Deferred. V001–V009 have no placeholder needs. Reintroduce if a future migration genuinely needs it.
- **Should we deploy a CI step that runs `flyway:validate` against Supabase remote nightly?** Deferred. Single-user app, low blast radius. Worth revisiting when a second human contributor joins.
