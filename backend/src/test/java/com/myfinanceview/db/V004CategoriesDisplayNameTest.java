package com.myfinanceview.db;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

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
 * Verifies V004 adds {@code myfinance.categories.display_name} as a NOT NULL text column
 * and backfills the 19 system categories (user_id IS NULL) with Spanish display names.
 *
 * Init scripts applied in order: V000 (local Supabase stubs), V001..V003 (current baseline), V004.
 *
 * <p>Mapping rationale: V003 seeds the system categories with English display names as {@code name}
 * (e.g. {@code 'Dining Out'}, not {@code 'restaurants_and_cafes'}). The spec V003 source is authoritative
 * over SPEC.md §4 — V004 maps each existing V003 {@code name} to its Spanish counterpart per
 * design.md D8 table (e.g. {@code 'Dining Out'} → {@code 'Restaurantes y Cafés'}).
 *
 * <p><b>Container lifecycle:</b> manual start (no {@code @Testcontainers} / {@code @Container}).
 * The {@code @Testcontainers} extension calls {@code container.stop()} at class teardown which —
 * on Windows Docker Desktop — kills the shared reused container and causes "Connection refused"
 * in all later test classes that share the same container config. Ryuk handles JVM-exit cleanup.
 */
class V004CategoriesDisplayNameTest {

    // Manual lifecycle — intentionally NOT @Container + @Testcontainers. See class javadoc.
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17")
        .withDatabaseName("myfinance_test")
        .withUsername("test")
        .withPassword("test")
        .withReuse(true);

    static {
        if (!postgres.isRunning()) {
            postgres.start();
        }
    }

    @Test
    void shouldAddDisplayNameColumnAsNotNullWhenV004Applied() throws Exception {
        try (Connection conn = openConnection()) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT data_type, is_nullable " +
                     "FROM information_schema.columns " +
                     "WHERE table_schema = 'myfinance' " +
                     "  AND table_name = 'categories' " +
                     "  AND column_name = 'display_name'")) {
                assertThat(rs.next())
                    .as("display_name column must exist on myfinance.categories")
                    .isTrue();
                assertThat(rs.getString("data_type")).isEqualTo("text");
                assertThat(rs.getString("is_nullable")).isEqualTo("NO");
            }
        }
    }

    @Test
    void shouldBackfillAll19SystemCategoriesWithSpanishDisplayName() throws Exception {
        try (Connection conn = openConnection()) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT count(*) FROM myfinance.categories WHERE user_id IS NULL")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1))
                    .as("V003 seeds exactly 19 system categories (14 expense + 5 income)")
                    .isEqualTo(19);
            }

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT count(*) FROM myfinance.categories WHERE display_name IS NULL")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1))
                    .as("V004 must backfill display_name for all categories before SET NOT NULL")
                    .isZero();
            }
        }
    }

    @Test
    void shouldSetDiningOutDisplayNameToRestaurantesYCafes() throws Exception {
        // V003 seeds the 'restaurants and cafes' category with name = 'Dining Out'
        // (English display label used as internal key). V004 maps it to the Spanish
        // canonical 'Restaurantes y Cafés' per SPEC.md §4 / design.md D8.
        try (Connection conn = openConnection()) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT display_name FROM myfinance.categories " +
                     "WHERE name = 'Dining Out' AND user_id IS NULL")) {
                assertThat(rs.next())
                    .as("V003 seed 'Dining Out' (system category) must exist")
                    .isTrue();
                assertThat(rs.getString("display_name"))
                    .isEqualTo("Restaurantes y Cafés");
            }
        }
    }

    // ------------------------------------------------------------------------

    @BeforeAll
    static void applyMigrationsOnce() throws Exception {
        Path databaseDir = locateDatabaseDir();
        List<Path> migrations = List.of(
            databaseDir.resolve("local").resolve("V000__local_supabase_stubs.sql"),
            databaseDir.resolve("migrations").resolve("V001__initial_schema.sql"),
            databaseDir.resolve("migrations").resolve("V002__rls_policies.sql"),
            databaseDir.resolve("migrations").resolve("V003__seed_data.sql"),
            databaseDir.resolve("migrations").resolve("V004__categories_display_name.sql")
        );
        try (Connection conn = DriverManager.getConnection(
                 postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            // Idempotency under container reuse: drop myfinance so V001's CREATE TYPE doesn't conflict.
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DROP SCHEMA IF EXISTS myfinance CASCADE");
            }
            for (Path migration : migrations) {
                if (!Files.exists(migration)) {
                    throw new IllegalStateException("migration file not found: " + migration);
                }
                String sql = Files.readString(migration);
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(sql);
                }
            }
        }
    }

    private static Connection openConnection() throws Exception {
        return DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static Path locateDatabaseDir() {
        Path cwd = Paths.get("").toAbsolutePath();
        Path candidate = cwd.resolve("database");
        if (Files.isDirectory(candidate)
            && Files.isDirectory(candidate.resolve("migrations"))
            && Files.isDirectory(candidate.resolve("local"))) {
            return candidate;
        }
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
