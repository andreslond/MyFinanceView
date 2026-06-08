package com.myfinanceview.db;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Shared setup for repository tests that need V001..V005 applied against a Postgres 17
 * Testcontainer. Mirrors the {@code @BeforeAll static applyMigrationsOnce()} pattern from
 * {@code V005MerchantsTest} to avoid the {@code CREATE TYPE ... already exists} error that
 * happens when migrations re-run per test.
 *
 * <p>Tests own their own {@link Connection} / {@link DSLContext} per @Test. The container is
 * shared across the JVM but each test class gets a fresh-ish state via explicit DELETEs in
 * {@code @BeforeEach}.
 */
public final class JooqTestSupport {

    private JooqTestSupport() {
        throw new AssertionError("static utility — do not instantiate");
    }

    /** Apply V000 (local stubs) → V001..V005 against the given container. Call once per class. */
    public static void applyAllMigrations(PostgreSQLContainer<?> postgres) throws Exception {
        Path databaseDir = locateDatabaseDir();
        List<Path> migrations = List.of(
            databaseDir.resolve("local").resolve("V000__local_supabase_stubs.sql"),
            databaseDir.resolve("migrations").resolve("V001__initial_schema.sql"),
            databaseDir.resolve("migrations").resolve("V002__rls_policies.sql"),
            databaseDir.resolve("migrations").resolve("V003__seed_data.sql"),
            databaseDir.resolve("migrations").resolve("V004__categories_display_name.sql"),
            databaseDir.resolve("migrations").resolve("V005__merchants.sql")
        );
        try (Connection conn = openConnection(postgres)) {
            // Idempotency: when the container is reused across test classes (testcontainers.reuse.enable=true),
            // V001's CREATE TYPE myfinance.account_type would fail second time. Drop the schema CASCADE first;
            // V000 is fully idempotent (IF NOT EXISTS / OR REPLACE), V001..V005 then rebuild myfinance cleanly.
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

    public static Connection openConnection(PostgreSQLContainer<?> postgres) throws SQLException {
        return DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    /** Open a Postgres-dialect jOOQ {@link DSLContext} backed by the given connection. */
    public static DSLContext dsl(Connection conn) {
        return DSL.using(conn, SQLDialect.POSTGRES);
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
