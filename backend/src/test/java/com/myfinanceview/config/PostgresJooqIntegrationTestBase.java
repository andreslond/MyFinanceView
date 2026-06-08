package com.myfinanceview.config;

import com.myfinanceview.db.JooqTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared base class for service-level integration tests that need a real
 * {@link org.jooq.DSLContext} + Spring {@code @Transactional} semantics around a
 * Testcontainers Postgres 17 container.
 *
 * <p><b>Container reuse:</b> the container is configured with {@code withReuse(true)} matching
 * the project-wide convention; {@code JooqTestSupport.applyAllMigrations} prepends
 * {@code DROP SCHEMA IF EXISTS myfinance CASCADE} so re-runs against a reused container are
 * idempotent. The container config (image / dbName / user / password) matches every other
 * {@code @Container} in this project so all classes share a single JVM-level reused container
 * when {@code testcontainers.reuse.enable=true} is set in {@code ~/.testcontainers.properties}.
 *
 * <p><b>Spring wiring:</b> {@code spring.datasource.{url,username,password}} are registered via
 * {@link DynamicPropertySource} so Spring Boot's {@code DataSourceAutoConfiguration} + jOOQ
 * {@code JooqAutoConfiguration} produce a {@link org.jooq.DSLContext} bean wired to the
 * container. Subclasses MUST activate both {@code test} and {@code service} profiles (in that
 * order — {@code service} re-enables the autoconfigs the base {@code test} profile excludes).
 *
 * <p>Usage:
 * <pre>{@code
 * @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
 * @ActiveProfiles({"test", "service"})
 * @Transactional   // auto-rollback per test for fast cleanup
 * class MyServiceTest extends PostgresJooqIntegrationTestBase { ... }
 * }</pre>
 */
public abstract class PostgresJooqIntegrationTestBase {

    // Manual lifecycle — intentionally NOT @Container + @Testcontainers. The JUnit extension
    // would call container.stop() at @AfterAll of each subclass, which on Windows + Docker
    // Desktop reaps the container even with reuse=true, so the next test class would get a
    // fresh container at a different port while the cached Spring context still holds the
    // stale port → "Connection refused". By starting once and never stopping (Ryuk handles
    // JVM-exit cleanup), the container's port stays valid for every class in the suite.
    // All test classes in this project follow this same manual lifecycle — no @Container
    // or @Testcontainers annotations anywhere. See §6 regression fix for the full root-cause.
    protected static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17")
        .withDatabaseName("myfinance_test")
        .withUsername("test")
        .withPassword("test")
        .withLabel("mfv.role", "service-test-base")
        .withReuse(true);

    static {
        // Start once on first class-load; reuse keeps it alive across the JVM.
        if (!postgres.isRunning()) {
            postgres.start();
        }
    }

    @BeforeAll
    static void applyMigrationsOnce() throws Exception {
        // Idempotent: drops + re-applies V001..V005 even when the container is reused across
        // test classes.
        JooqTestSupport.applyAllMigrations(postgres);
    }

    @DynamicPropertySource
    static void registerDatasourceProps(DynamicPropertyRegistry reg) {
        // Append prepareThreshold=0 so the Postgres JDBC driver never promotes a statement to
        // a server-side prepared plan. Necessary because applyAllMigrations() does
        // DROP SCHEMA myfinance CASCADE + re-run V001..V005 at the start of each test class,
        // which invalidates any cached plan ("cached plan must not change result type" when
        // the next class's SELECT hits a recycled connection). Forcing simple-protocol queries
        // avoids the issue entirely without measurable cost in tests.
        reg.add("spring.datasource.url",
            () -> postgres.getJdbcUrl() + "?prepareThreshold=0&preferQueryMode=simple"
                + "&autosave=conservative");
        reg.add("spring.datasource.username", postgres::getUsername);
        reg.add("spring.datasource.password", postgres::getPassword);
    }
}
