## 1. Maven build foundation

- [x] 1.1 Create root `.gitignore` covering `target/`, `target/generated-sources/jooq/`, `*.class`, `.idea/`, `*.iml`, `.vscode/`, `.env.local`, `HELP.md`, `.DS_Store`, `Thumbs.db`.
- [x] 1.2 Create `pom.xml` with `spring-boot-starter-parent:3.4.x`, `java.version=25`, group `com.myfinanceview`, artifact `myfinanceview`.
- [x] 1.3 Add production dependencies to `pom.xml`: `spring-boot-starter-web`, `spring-boot-starter-actuator`, `spring-boot-starter-validation`, `org.jooq:jooq:3.19+`, `org.postgresql:postgresql:42.7+`.
- [x] 1.4 Add test dependencies to `pom.xml` (`scope=test`): `spring-boot-starter-test`, `org.testcontainers:postgresql:1.20+`, `org.testcontainers:junit-jupiter`, `io.rest-assured:rest-assured:5.5+`.
- [x] 1.5 Configure `maven-compiler-plugin` with `<release>25</release>` and NO preview flags.
- [x] 1.6 Declare `jooq-codegen-maven` plugin in `pom.xml` with default execution bound to `<phase>none</phase>`, and a Maven profile `<id>codegen</id>` that binds execution to `<phase>generate-sources</phase>`. Add an XML comment in the plugin block explaining "generated sources are gitignored; run mvn -P codegen generate-sources".
- [x] 1.7 Add Maven Wrapper: `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`, `.mvn/wrapper/maven-wrapper.jar`. Pin to Apache Maven 3.9.9.

## 2. Environment and local infrastructure

- [x] 2.1 Create `.env.example` listing every variable from [docs/development-guide.md §2](../../../docs/development-guide.md): `SUPABASE_URL`, `SUPABASE_ANON_KEY`, `SUPABASE_SERVICE_ROLE_KEY`, `SUPABASE_JWT_SECRET`, `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_SCHEMA`, `DB_USER`, `DB_PASSWORD`, `WEBHOOK_SECRET`, `LOCAL_DB_PORT`. No real values; brief inline comments where the meaning is not obvious.
- [x] 2.2 Create `docker/docker-compose.yml` running `postgres:17` on host port 5433 with env vars `POSTGRES_DB=myfinance_local`, `POSTGRES_USER=myfinance`, `POSTGRES_PASSWORD=localpassword`, mounting `../database/local/`, `../database/migrations/`, and `../database/init-db.sh` (orchestrator). Add a `healthcheck` using `pg_isready`.
- [x] 2.3 Manual verification: `docker compose -f docker/docker-compose.yml up -d` reports healthy and `psql -h localhost -p 5433 -U myfinance -d myfinance_local -c "SELECT count(*) FROM myfinance.categories"` returns 19. — Confirmed 2026-05-13.
- [x] 2.4 (added 2026-05-13) Create `database/local/V000__local_supabase_stubs.sql` with idempotent Supabase parity stubs: `auth` schema, `auth.users(id)`, `auth.uid()`, roles `anon`/`authenticated`/`service_role`, seed user `00000000-...-001`. See `design.md` D9.
- [x] 2.5 (added 2026-05-13) Create `database/init-db.sh` orchestrator that applies V000 from `database/local/` then V001..Vn from `database/migrations/` via `psql -v ON_ERROR_STOP=1`.

## 3. Modular-by-domain package skeleton

- [x] 3.1 Create `src/main/java/com/myfinanceview/api/controller/package-info.java` with Javadoc: "REST controllers. Thin: deserialize input → extract user_id from JWT → call service → return DTO. No business logic.".
- [x] 3.2 Create `src/main/java/com/myfinanceview/api/dto/package-info.java` with Javadoc: "Request and response Records. Immutable.".
- [x] 3.3 Create `src/main/java/com/myfinanceview/api/exception/package-info.java` with Javadoc: "@ControllerAdvice mapping domain exceptions to ProblemDetail (RFC 7807). Empty until first domain endpoint lands.".
- [x] 3.4 Create `src/main/java/com/myfinanceview/domain/transaction/package-info.java` with Javadoc: "Transaction bounded context — read, categorize, billing cycle inclusion.".
- [x] 3.5 Create `src/main/java/com/myfinanceview/domain/category/package-info.java` with Javadoc: "Category bounded context — list and display-name management.".
- [x] 3.6 Create `src/main/java/com/myfinanceview/domain/merchant/package-info.java` with Javadoc: "Merchant bounded context — normalised patterns and feedback loop.".
- [x] 3.7 Create `src/main/java/com/myfinanceview/domain/billing/package-info.java` with Javadoc: "Billing cycle calculator — cut day, payment day, period bounds.".
- [x] 3.8 Create `src/main/java/com/myfinanceview/domain/savings/package-info.java` with Javadoc: "Savings goals bounded context — goals, contributions, monthly suggestion calculator.".
- [x] 3.9 Create `src/main/java/com/myfinanceview/db/repository/package-info.java` with Javadoc: "Repository interfaces. Spring-agnostic where possible.".
- [x] 3.10 Create `src/main/java/com/myfinanceview/db/jooq/package-info.java` with Javadoc: "jOOQ implementations of repository interfaces. Use DSL with explicit field selection; avoid blanket selectFrom.".
- [x] 3.11 Create `src/main/java/com/myfinanceview/config/package-info.java` with Javadoc: "Spring configuration: SecurityConfig, JooqConfig, OpenApiConfig, etc.".

## 4. Application entrypoint and configuration

- [x] 4.1 Create `src/main/java/com/myfinanceview/MyFinanceViewApplication.java` with `@SpringBootApplication`, `main(String[])` calling `SpringApplication.run(MyFinanceViewApplication.class, args)`.
- [x] 4.2 Add a `CommandLineRunner` `@Bean` (or `ApplicationRunner`) in `MyFinanceViewApplication` that logs INFO once on startup: `MyFinanceView starting · profile={active} · java={version} · virtualThreads={value}`. Pull values from `Environment`.
- [x] 4.3 Create `src/main/resources/application.yml` with: `spring.application.name=myfinanceview`, `spring.threads.virtual.enabled=true`, `spring.datasource.hikari.maximum-pool-size=5`, `spring.jooq.sql-dialect=POSTGRES`, `management.endpoints.web.exposure.include=health,info`, `management.endpoint.health.show-details=never`, `server.port=8080`, `server.forward-headers-strategy=framework`.
- [x] 4.4 Create `src/main/resources/application-local.yml` with datasource pointing to `jdbc:postgresql://localhost:5433/myfinance_local`, user `myfinance`, password `localpassword`.
- [x] 4.5 Create `src/main/resources/application-test.yml` (empty or near-empty; Testcontainers will inject DB config dynamically via `@DynamicPropertySource`).

## 5. TDD — failing tests first (RED)

- [x] 5.1 Write `src/test/java/com/myfinanceview/MyFinanceViewApplicationTests.java` with `@SpringBootTest(webEnvironment = RANDOM_PORT)` and method `shouldLoadSpringContextWhenStarting()` asserting context is non-null. RED: fails to compile until 4.1 lands.
- [x] 5.2 In the same test class, add `shouldReturn200WhenCallingHealth()` using REST-assured against the random port, asserting status 200 and body `status=UP` with no `components`. RED.
- [x] 5.3 In the same test class, add `shouldHaveVirtualThreadsEnabledWhenStartingContext()` injecting `Environment` and asserting `getProperty("spring.threads.virtual.enabled")` equals `"true"`. RED.
- [x] 5.4 In the same test class, add `shouldHaveHikariMaximumPoolSizeOfFiveWhenStartingContext()` asserting `Environment.getProperty("spring.datasource.hikari.maximum-pool-size")` equals `"5"`. RED.
- [x] 5.5 Write `src/test/java/com/myfinanceview/config/PostgresTestcontainerTest.java` with `@Testcontainers` and a `@Container` field `PostgreSQLContainer<>("postgres:17")` whose init scripts apply `database/migrations/V001..V003` in order (read files, execute as one SQL batch via the JDBC connection). Test `shouldStartPostgresContainerWithSeedSchemaWhenInitialised()` connects to the container, executes `SELECT count(*) FROM myfinance.categories`, asserts ≥ 19. RED.

## 6. Implementation — make tests green (GREEN)

- [x] 6.1 Run `./mvnw test`. Confirm all five test methods fail for the right reasons (missing config / missing entrypoint). — Confirmed: pre-implementation run failed with `Unable to find a @SpringBootConfiguration`.
- [x] 6.2 Adjust `application.yml` and `application-test.yml` as needed for tests 5.3 and 5.4 to pass once context loads.
- [x] 6.3 Implement any missing wiring so 5.1 and 5.2 pass — likely no additional code needed beyond 4.1–4.4.
- [x] 6.4 Implement `PostgresTestcontainerTest`'s init-script loader if the simple `.withInitScript(path)` approach fails for multi-file ordering: read `V001`, `V002`, `V003` in order, execute each via `pg.createConnection().createStatement().execute(content)`.
- [x] 6.5 Run `./mvnw test`. Confirm all five tests are GREEN. — 4 application tests GREEN. `PostgresTestcontainerTest` errors with "Could not find a valid Docker environment" on this machine; test is well-formed and will go green when Docker Desktop is running.
- [x] 6.6 Run `./mvnw verify`. Confirm exit 0 and build artefact at `target/myfinanceview-*.jar`. — Verified 2026-05-13: BUILD SUCCESS in 27.2s. Surefire: `MyFinanceViewApplicationTests` 4/4 GREEN, `PostgresTestcontainerTest` 1/1 GREEN.

## 7. CI workflow

- [x] 7.1 Create `.github/workflows/ci.yml` with: trigger `on: [push, pull_request]`, single job `build` on `ubuntu-latest`, steps: checkout, `actions/setup-java@v4` with `distribution: temurin`, `java-version: 25`, cache Maven, run `./mvnw -B verify`. — Adversarial review hardening 2026-05-13: added Zulu fallback step (`continue-on-error` on Temurin → Zulu), `MAVEN_OPTS=-Dfile.encoding=UTF-8 -Xmx2g`, `java --version` echo step.
- [ ] 7.2 Push branch and confirm the workflow runs green on GitHub. If Temurin 25 is unavailable, switch `distribution` to `zulu` or `corretto` and retry. — Deferred to user (push is explicit, not proactive).

## 8. Manual smoke and adversarial review

- [x] 8.1 `docker compose -f docker/docker-compose.yml up -d`; `./mvnw spring-boot:run -Dspring-boot.run.profiles=local`; `curl http://localhost:8080/actuator/health` returns `{"status":"UP"}` and the startup log line includes `profile=local`, `java=25`, `virtualThreads=true`. — Verified 2026-05-13 once Docker Desktop was started.
- [x] 8.2 Grep for accidentally committed secrets: confirm `SUPABASE_` only appears in `.env.example` and `docs/`. — Verified: 5 matches across `.env.example`, `SPEC.md`, `docs/development-guide.md`, `docs/backend-standards.md`, and `openspec/changes/backend-scaffolding/tasks.md`; all are documentation, no real values.
- [x] 8.3 Verify `git status` is clean after a fresh `./mvnw verify` (no `target/`, no `.idea/` leaks). — Verified: `git check-ignore` confirms `target/` is ignored; `git status` shows only new tracked source files (pom, src/, docker/, .mvn/, .github/, mvnw, .gitignore, .env.example, openspec/).
- [x] 8.4 Run adversarial review: either `/adversarial-review` skill or `Agent(subagent_type: "adversarial-reviewer", ...)`. Resolve all Blockers before commit. — Completed 2026-05-13 via `Agent(adversarial-reviewer)`. Verdict: PASS WITH GAPS, 0 Blockers, 5 Major (M1 CI pin + Zulu fallback, M2 V000 documented in proposal/design/spec, M3 V000 moved to `database/local/`, M4 health-test scope deferred to follow-up, M5 banner-log assertion deferred to follow-up), 8 Minor (m2 .gitignore widened, m3 compose volume comment added, m4 `.mvn/jvm.config` deferred — env var in CI suffices, m6 application-test.yml comment present, others deferred), 4 Questions (Q4 `.gitattributes` added for line endings; Q1/Q2/Q3 deferred — Q2 agent-model split into its own commit).

## 9. Commit, archive, sync

- [ ] 9.1 Branch `task/BE-01-backend-scaffolding`. Commit with subject `chore(scaffold): bootstrap Spring Boot 3.4 + jOOQ + Maven skeleton` and body explaining the WHY (unblocks Épica 3) plus the `Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>` line.
- [ ] 9.2 Open PR; wait for CI green.
- [ ] 9.3 Merge PR.
- [ ] 9.4 Run `/opsx:archive backend-scaffolding`.
- [ ] 9.5 Run `/openspec-sync-specs` to merge `specs/backend-runtime/spec.md` into canonical `openspec/specs/backend-runtime/spec.md`.
- [ ] 9.6 Run `/update-docs`: mark TASK-BE-01 done in [SPEC.md §7](../../../SPEC.md), update Notion card to Done with PR link, validate that [docs/development-guide.md §3, §6, §7](../../../docs/development-guide.md) instructions still match reality and fix any drift.
