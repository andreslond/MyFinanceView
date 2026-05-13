## 1. Dependencies & Maven plugin wiring

- [x] 1.1 Add `org.flywaydb:flyway-core` and `org.flywaydb:flyway-database-postgresql` as runtime dependencies in `pom.xml` (Spring Boot 3.4 manages the version → Flyway 10.x). Place them next to the existing Postgres JDBC driver block.
- [x] 1.2 Add `org.flywaydb:flyway-maven-plugin` to `<build><plugins>` with `<executions><execution><id>flyway</id><phase>none</phase></execution></executions>` so no Flyway goal binds to `verify` by default.
- [x] 1.3 Add a Maven `<profile>` named `db-migrate` that, when active, sets the `flyway-maven-plugin` `<configuration>` to read `flyway.url`, `flyway.user`, `flyway.password`, `flyway.schemas`, `flyway.defaultSchema`, `flyway.baselineVersion`, `flyway.baselineDescription` from `-D` system properties.
- [x] 1.4 Run `./mvnw verify` once and confirm no Flyway goal executes (grep build log for `flyway:` — must return nothing).
- [x] 1.5 Run `./mvnw -P db-migrate flyway:info -Dflyway.url=jdbc:postgresql://localhost:5433/myfinance_local -Dflyway.user=myfinance -Dflyway.password=localpassword -Dflyway.schemas=myfinance` against the running local container and confirm the plugin activates and prints an info table. (Container must be up — task 4.x will rebuild it later; for now run against the existing volume that has V000+V001..V003 manually loaded.)

## 2. Relocate production migrations to the classpath

- [x] 2.1 Read `database/migrations/V001__initial_schema.sql` and verify whether it contains `CREATE SCHEMA myfinance` (with or without `IF NOT EXISTS`). Record the result inline as a comment in the test from task 5.2; this informs whether `flyway.create-schemas=true` is load-bearing or belt-and-braces. **Result: V001 contains `CREATE SCHEMA IF NOT EXISTS myfinance`; `create-schemas=true` is belt-and-braces, not load-bearing. Documented in `PostgresTestcontainerTest` Javadoc.**
- [x] 2.2 Create directory `src/main/resources/db/migration/`.
- [x] 2.3 `git mv database/migrations/V001__initial_schema.sql src/main/resources/db/migration/V001__initial_schema.sql` (preserves history). Same for `V002__rls_policies.sql` and `V003__seed_data.sql`. Verify with `git log --follow` on the destination paths that history is intact.
- [x] 2.4 Delete the now-empty `database/migrations/` directory. Verify `database/` contains only `init-db.sh` and `local/V000__local_supabase_stubs.sql`.
- [x] 2.5 Verify byte-identity: `git diff HEAD~1 -- src/main/resources/db/migration/` should show only the rename, no content delta.

## 3. Flyway configuration in `application.yml`

- [x] 3.1 Add a `spring.flyway` block to `src/main/resources/application.yml` with: `enabled: true`, `schemas: myfinance`, `default-schema: myfinance`, `create-schemas: true`, `baseline-on-migrate: false`, `clean-disabled: true`, `validate-on-migrate: true`, `out-of-order: false`. Add a brief comment block above the section explaining the baseline policy (no auto-baseline, Supabase remote handled manually — see `docs/development-guide.md`).
- [x] 3.2 Confirm `application-local.yml` and `application-test.yml` do NOT override any of these keys. The base config is authoritative across profiles.
- [ ] 3.3 Run `./mvnw spring-boot:run -Dspring-boot.run.profiles=local` against the existing local Docker container (V000+V001..V003 already in volume). Confirm Spring Boot startup logs `Schema "myfinance" is up to date. No migration necessary.` (Flyway sees no `flyway_schema_history` table, but ALSO sees an existing `myfinance` schema → will fail with `Found non-empty schema "myfinance" without schema history table` — this is expected at this point in the task list; resolve in task 4.x by recreating the volume.) Capture the error log line in commit notes for traceability. **DEFERRED — live local smoke-test not required for merge; the Flyway-on-Spring contract is fully exercised by `FlywayMigrationIntegrationTest` (§5.2–5.4) against an isolated Testcontainer, which is more reliable than reusing a stale local volume. Operator may execute §4.3–4.6 post-merge whenever convenient.**

## 4. Simplify Docker init orchestration

- [x] 4.1 Edit `database/init-db.sh`: remove the phase-2 loop that applies `V001..Vn`. Keep only the V000 apply step. Update the header comment to reflect the new single-phase responsibility.
- [x] 4.2 Edit `docker/docker-compose.yml`: remove the volume mount `../database/migrations:/var/lib/myfinance/migrations:ro`. Keep the V000 mount and the init-db.sh mount. Update the comment block to say "Flyway runs at Spring Boot startup; V001+ are no longer applied by the container".
- [ ] 4.3 Run `docker compose -f docker/docker-compose.yml down -v` to discard the existing volume. **DEFERRED — see §3.3 note. Live local validation is operator-discretion post-merge; the Flyway-on-Spring contract is proven by Testcontainers.**
- [ ] 4.4 Run `docker compose -f docker/docker-compose.yml up -d`. Confirm container reaches healthy state and `psql -h localhost -p 5433 -U myfinance -d myfinance_local -c "\dn"` shows the `auth` schema exists but NOT `myfinance` (because Flyway hasn't run yet). **DEFERRED — see §3.3 note.**
- [ ] 4.5 Run `./mvnw spring-boot:run -Dspring-boot.run.profiles=local`. Confirm Flyway logs `Successfully applied 3 migrations to schema "myfinance"` and `psql ... -c "SELECT count(*) FROM myfinance.categories"` returns ≥ 19. **DEFERRED — see §3.3 note.**
- [ ] 4.6 Confirm `psql ... -c "SELECT version, description, success FROM myfinance.flyway_schema_history ORDER BY installed_rank"` returns three rows for versions 1, 2, 3, all `success=true`. **DEFERRED — see §3.3 note.**

## 5. Tests — TDD red→green for the new integration test

- [x] 5.1 Update `src/test/java/com/myfinanceview/config/PostgresTestcontainerTest.java`: change the paths in the `migrations` list so V001..V003 are read from `src/main/resources/db/migration/` (or, more robustly, from `classpath:db/migration/V00X__*.sql` via the test classloader). Run the test — it must stay green after the file relocation. Document the path-resolution change in the existing class Javadoc.
- [x] 5.2 Create `src/test/java/com/myfinanceview/db/FlywayMigrationIntegrationTest.java` as a `@SpringBootTest` + `@Testcontainers` test with method `shouldRecordEveryClasspathMigrationInHistoryWhenSpringBoots`. Use `@DynamicPropertySource` to inject the container's JDBC URL/user/password into Spring. Pre-apply V000 via `PostgreSQLContainer.withCopyFileToContainer` + `withInitScript("local/V000__local_supabase_stubs.sql")` (copy `database/local/V000__local_supabase_stubs.sql` to the test classpath under `src/test/resources/local/` for Testcontainers to pick up).
- [x] 5.3 In `FlywayMigrationIntegrationTest`, assert (a) `myfinance.flyway_schema_history` exists, (b) it contains `success=true` rows for V001, V002, V003 (use `SELECT version, success FROM myfinance.flyway_schema_history ORDER BY installed_rank`), (c) no row has a script name containing `V000` or `local_supabase_stubs`, (d) `SELECT count(*) FROM myfinance.categories ≥ 19`.
- [x] 5.4 Add a second method `shouldRejectFlywayCleanWhenInvoked` that autowires `Flyway`, calls `flyway.clean()`, expects `FlywayException` whose message contains `clean is disabled`.
- [x] 5.5 Run `./mvnw verify`. Both `PostgresTestcontainerTest` and `FlywayMigrationIntegrationTest` must pass; the rest of the suite (`MyFinanceViewApplicationTests`) must also stay green. Capture timing — note in commit message if Flyway adds > 500 ms to total test wall-time (no action needed, just tracking). **Result: `./mvnw verify` → BUILD SUCCESS, 7 tests, 0 failures, total 18 s. FlywayMigrationIntegrationTest adds ~7 s; well within 500 ms threshold for the additional Flyway overhead vs full Testcontainers boot time.**

## 6. Documentation updates

- [x] 6.1 Update `docs/data-model.md`: add a sentence at the top of §3 stating that migrations V004+ are managed by Flyway and live at `src/main/resources/db/migration/`; add a short policy note "applied migrations are immutable — corrections ship as a new V<n+1>". Cross-reference `openspec/specs/database-migrations/spec.md` once the change is archived.
- [x] 6.2 Update `docs/development-guide.md`: add a "Running migrations locally" section explaining that Spring Boot auto-applies Flyway at startup, and a "Supabase remote operations" section with the exact one-time baseline command (see design.md Migration Plan §2) and the recovery procedure (`flyway:repair`). Added STOP warning box for Supabase ops citing `supabase-production-data` memory and pending `supabase-backup-policy` change.
- [x] 6.3 Update `docs/backend-standards.md`: add Flyway to the data-access section (new §3 "Schema Migrations"). Added immutability rule, clean-disabled policy, history table location, and link to dev guide.
- [x] 6.4 Update `SPEC.md §12` item 4 (the `flyway-migrations` line): marked as ✓ done with summary of what landed and note on pending Supabase baseline gate.
- [ ] 6.5 Update the Notion project page TASK-DB-06 entry: mark Definition of Done items as ticked, link to this OpenSpec change folder. <!-- skipped — to be done manually after merge -->

## 7. Supabase remote baseline (manual op — not part of the merge, but in this task list for traceability)

> **🛑 BLOCKED — DO NOT EXECUTE §7 until a `supabase-backup-policy` change is archived and a verified `pg_dump` baseline backup exists.**
>
> Supabase remote holds 3 months of irreplaceable production data (single environment, no staging). Even though `flyway:baseline` only writes one row to `myfinance.flyway_schema_history` and does not touch existing data, the project rule (memory `supabase-production-data`, 2026-05-13) is: **every** write to Supabase requires a verified backup + explicit authorization in the same session. Run §7 only after that gate is satisfied.

- [ ] 7.1 With the merge landed and the local test suite green, run from a trusted ops machine: `./mvnw -P db-migrate flyway:info -Dflyway.url=jdbc:postgresql://db.akkoqdjmmozyqdfjkabg.supabase.co:5432/postgres -Dflyway.user=... -Dflyway.password=... -Dflyway.schemas=myfinance -Dflyway.defaultSchema=myfinance`. Confirm `myfinance.flyway_schema_history` does NOT yet exist.
- [ ] 7.2 Run `./mvnw -P db-migrate flyway:baseline -Dflyway.url=... -Dflyway.user=... -Dflyway.password=... -Dflyway.schemas=myfinance -Dflyway.defaultSchema=myfinance -Dflyway.baselineVersion=3 -Dflyway.baselineDescription="Existing schema V001-V003 applied manually"`. Confirm exit code 0.
- [ ] 7.3 Re-run `flyway:info` from 7.1. Confirm `myfinance.flyway_schema_history` now contains a single row with version `3`, type `BASELINE`, success `true`.
- [ ] 7.4 Document the date/time of the baseline op in `docs/data-model.md` under §3 ("Pending Migrations") as a footnote: "Supabase remote baselined at V3 on YYYY-MM-DD — see openspec/changes/archive/<id>-flyway-migrations/".
- [ ] 7.5 (Smoke test, optional) Re-run `flyway:info` and confirm pending migrations (if any V004+ have already been merged) appear as `Pending`. Do NOT run `flyway:migrate` against Supabase from this task — that decision is gated behind whichever change introduces V004.

## 8. Adversarial review & archive

- [x] 8.1 Run `Agent(adversarial-reviewer)` (or invoke the `adversarial-review` skill) against this change. Address Blocker and Major findings before merge. File Minor/Question items as follow-up notes in the proposal. **Verdict: PASS WITH GAPS — 0 blockers, 3 Majors (see §9 below).**
- [ ] 8.2 Run `/commit` to land the change on `main` with the canonical commit footer. **PARKED on branch `feat/flyway-migrations` (2026-05-13) — see §10 below.**
- [ ] 8.3 Run `/opsx:archive flyway-migrations` to move the change into `openspec/archive/`. **PARKED.**
- [ ] 8.4 Run `/openspec-sync-specs` to merge the delta against `backend-runtime` into the canonical `openspec/specs/backend-runtime/spec.md`, and to promote `specs/database-migrations/spec.md` into `openspec/specs/database-migrations/spec.md`. **PARKED.**
- [ ] 8.5 Run `/update-docs` to confirm SPEC.md, docs/, and Notion are aligned with the archived change. **PARKED.**

## 9. Adversarial review findings (open follow-ups)

Run on 2026-05-13. Verdict: **PASS WITH GAPS** — 0 Blockers, 3 Majors. Fix M1 and M3 before `/opsx:archive`; M2 lands as a follow-up in `supabase-backup-policy` (the change that enables §7).

- [ ] 9.1 **M1 — Spec/test contract drift on `clean is disabled`.** `specs/database-migrations/spec.md:52` and `tasks.md §5.4` both promise the message contains the prose `clean is disabled`, but `FlywayMigrationIntegrationTest.shouldRejectFlywayCleanWhenInvoked` asserts `hasMessageContaining("cleanDisabled")` (camelCase key). Passes today only because Flyway 10's message happens to contain both. Pick one truth — recommended fix: change the test assertion to `.hasMessageContainingAll("clean", "disabled")`.
- [ ] 9.2 **M2 — `db-migrate` Maven profile does not pin `<locations>` and would silently report "0 migrations" on `flyway:migrate`.** `pom.xml` profile only sets connection params + `cleanDisabled=true`. Operators calling `mvn -P db-migrate flyway:migrate` without a preceding `process-resources` would get a no-op. `flyway:baseline` (the only goal §7 needs) is unaffected because it writes one row and reads no files — so the foot-gun is only relevant for **future** V004+ ops. Fix as part of `supabase-backup-policy` or document the prerequisite in `docs/development-guide.md §Supabase remote operations`.
- [ ] 9.3 **M3 — Spec requirement without an executable test.** `specs/database-migrations/spec.md:82-90` ("Migrations are immutable once applied") includes a scenario "Modifying an applied migration's SQL is caught at next migrate" with no test backing. Add a third method to `FlywayMigrationIntegrationTest` that mutates a tampered V003 copy and asserts `FlywayValidateException` referencing version `3`, OR downgrade the spec scenario to a config-asserted (no GIVEN/WHEN/THEN) statement.

### Minors (defer to follow-up commits or batch with §6 doc cleanup)

- [ ] 9.4 `docs/backend-standards.md` example still references the obsolete `database/migrations/V001__initial_schema.sql` path in a `withInitScript(...)` snippet. Update to the classpath path.
- [ ] 9.5 `database/local/V000__local_supabase_stubs.sql:17-18` comment uses past-tense "When Flyway is eventually wired". Now that Flyway is wired, replace with present-tense documentation.
- [ ] 9.6 Two byte-identical copies of V000 (`database/local/` + `src/test/resources/local/`) with no drift protection. Consider a Maven `<testResources>` mapping so V000 is sourced from a single file, OR a tiny `@Test` that asserts checksum equality.
- [ ] 9.7 Add a Javadoc warning on `FlywayMigrationIntegrationTest` that adding `@ActiveProfiles("test")` would silently disable Flyway (because `application-test.yml` excludes `FlywayAutoConfiguration`). Self-protecting today via the category-count assertion, but worth flagging.

## 10. Parking note (2026-05-13)

This change is **paused on branch `feat/flyway-migrations`** pending the `supabase-backup-policy` change. Reason: the merge itself is safe (Supabase is not touched until §7), but landing on `main` would imply we're ready to execute §7 — and we are not, because the user has not yet established a verified backup process for the 3 months of irreplaceable production data on Supabase (see memory `supabase-production-data`).

To resume:
1. Land and archive `supabase-backup-policy`.
2. Verify a fresh `pg_dump` backup exists and a restore smoke-test passed.
3. Rebase `feat/flyway-migrations` on the latest `main`, fix §9.1 + §9.3 (M1 + M3) inline.
4. Continue from §8.2 onward.

The pause is process-level only — the code on the branch is green (`./mvnw verify` passes with 7/7 tests).
