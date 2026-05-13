package com.myfinanceview.config;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
 * Verifies that a Postgres 17 Testcontainer initialised with V001..V003 yields the same myfinance
 * schema we run against locally and in Supabase. Intentionally a plain JDBC test — no Spring
 * context — so it isolates the "migrations apply cleanly" contract from any application wiring.
 *
 * The init scripts are applied manually (read file then execute) instead of {@code .withInitScript()}
 * because the latter only accepts a single script. We need V000 (local-only Supabase parity stubs)
 * from database/local/ then V001..V003 from database/migrations/ in order. See
 * openspec/changes/backend-scaffolding/design.md D6 for the rationale behind the directory split.
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
        Path databaseDir = locateDatabaseDir();
        List<Path> migrations = List.of(
            databaseDir.resolve("local").resolve("V000__local_supabase_stubs.sql"),
            databaseDir.resolve("migrations").resolve("V001__initial_schema.sql"),
            databaseDir.resolve("migrations").resolve("V002__rls_policies.sql"),
            databaseDir.resolve("migrations").resolve("V003__seed_data.sql")
        );

        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            for (Path migration : migrations) {
                assertThat(Files.exists(migration))
                    .as("migration file %s must exist", migration)
                    .isTrue();
                String sql = Files.readString(migration);
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(sql);
                }
            }

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT count(*) FROM myfinance.categories")) {
                assertThat(rs.next()).isTrue();
                int categoryCount = rs.getInt(1);
                assertThat(categoryCount).isGreaterThanOrEqualTo(19);
            }
        }
    }

    private static Path locateDatabaseDir() {
        Path cwd = Paths.get("").toAbsolutePath();
        Path candidate = cwd.resolve("database");
        if (Files.isDirectory(candidate)
            && Files.isDirectory(candidate.resolve("migrations"))
            && Files.isDirectory(candidate.resolve("local"))) {
            return candidate;
        }
        // Walk up looking for database/ root (in case tests run from a subdir).
        Path p = cwd;
        while (p != null) {
            Path c = p.resolve("database");
            if (Files.isDirectory(c)
                && Files.isDirectory(c.resolve("migrations"))
                && Files.isDirectory(c.resolve("local"))) {
                return c;
            }
            p = p.getParent();
        }
        throw new IllegalStateException("database/ root with migrations/ and local/ not found from " + cwd);
    }
}
