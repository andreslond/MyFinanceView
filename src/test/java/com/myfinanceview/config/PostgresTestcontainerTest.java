package com.myfinanceview.config;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that a Postgres 17 Testcontainer initialised with V000+V001..V003 yields the same
 * myfinance schema we run against locally and in Supabase. Intentionally a plain JDBC test —
 * no Spring context — so it isolates the "migrations apply cleanly against a vanilla Postgres"
 * contract from any application wiring (and from Flyway itself).
 *
 * <p>Path resolution after the {@code flyway-migrations} change: V000 still lives at
 * {@code database/local/V000__local_supabase_stubs.sql} (off the Flyway classpath by design),
 * but V001..V003 moved to {@code src/main/resources/db/migration/} so they ship inside the
 * application jar. This test reads them from the classpath via the test classloader, which
 * makes the test robust to CWD changes (IDE, Maven, CI) and to future migration files added
 * without touching this resolver. See
 * {@code openspec/changes/flyway-migrations/design.md} D1+D7.
 *
 * <p>V000 is still read from the filesystem (relative to the repo root) because it is
 * deliberately NOT on the classpath — that's the whole point of keeping local-only parity
 * stubs invisible to Flyway. {@link #locateDatabaseLocalDir()} walks up to find it.
 */
@Testcontainers
class PostgresTestcontainerTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17")
        .withDatabaseName("myfinance_test")
        .withUsername("test")
        .withPassword("test");

    @Test
    void shouldStartPostgresContainerWithSeedSchemaWhenInitialised() throws Exception {
        // V001 contains `CREATE SCHEMA IF NOT EXISTS myfinance` so create-schemas=true in
        // application.yml is belt-and-braces, not load-bearing. Verified 2026-05-13.
        Path v000 = locateDatabaseLocalDir().resolve("V000__local_supabase_stubs.sql");
        assertThat(Files.exists(v000))
            .as("V000 stub file must exist at %s", v000)
            .isTrue();
        List<String> classpathMigrations = List.of(
            "db/migration/V001__initial_schema.sql",
            "db/migration/V002__rls_policies.sql",
            "db/migration/V003__seed_data.sql"
        );

        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            // V000 from filesystem (never on the Flyway classpath).
            executeSql(conn, Files.readString(v000), v000.toString());
            // V001..V003 from the application classpath (same place Flyway reads them).
            for (String resource : classpathMigrations) {
                executeSql(conn, readClasspathResource(resource), "classpath:" + resource);
            }

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT count(*) FROM myfinance.categories")) {
                assertThat(rs.next()).isTrue();
                int categoryCount = rs.getInt(1);
                assertThat(categoryCount).isGreaterThanOrEqualTo(19);
            }
        }
    }

    private static void executeSql(Connection conn, String sql, String source) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (Exception e) {
            throw new RuntimeException("Failed applying " + source, e);
        }
    }

    private static String readClasspathResource(String resource) throws Exception {
        try (InputStream in = PostgresTestcontainerTest.class
                .getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Classpath resource not found: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Path locateDatabaseLocalDir() {
        Path cwd = Paths.get("").toAbsolutePath();
        Path candidate = cwd.resolve("database").resolve("local");
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        // Walk up looking for database/local/ (in case tests run from a subdir).
        Path p = cwd;
        while (p != null) {
            Path c = p.resolve("database").resolve("local");
            if (Files.isDirectory(c)) {
                return c;
            }
            p = p.getParent();
        }
        throw new IllegalStateException("database/local/ not found from " + cwd);
    }
}
