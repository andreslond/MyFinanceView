package com.myfinanceview.db;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies V005 creates {@code myfinance.merchants}, enables RLS, adds the
 * nullable {@code transactions.merchant_id} FK with partial index, and enforces
 * all CHECK / UNIQUE constraints from design.md §D8.
 *
 * Init scripts: V000 (local stubs) → V001..V004 → V005.
 *
 * <p>We don't exercise the RLS policy semantics here (that requires
 * {@code SET request.jwt.claim.sub} per query and is covered in repository
 * isolation tests later); this test only asserts the policies/structure exist.
 *
 * <p><b>Container lifecycle:</b> manual start (no {@code @Testcontainers} / {@code @Container}).
 * The {@code @Testcontainers} extension calls {@code container.stop()} at class teardown which —
 * on Windows Docker Desktop — kills the shared reused container and causes "Connection refused"
 * in all later test classes that share the same container config. Ryuk handles JVM-exit cleanup.
 */
class V005MerchantsTest {

    private static final UUID SEED_USER = UUID.fromString("00000000-0000-0000-0000-000000000001");

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
    void shouldCreateMerchantsTableWithExpectedSchemaWhenV005Applied() throws Exception {
        try (Connection conn = openConnection()) {
            Map<String, ColumnInfo> cols = loadColumns(conn, "myfinance", "merchants");

            assertThat(cols).containsKeys(
                "id", "user_id", "display_name", "raw_pattern", "category_id",
                "confidence", "match_count", "last_confirmed_at",
                "created_at", "updated_at");

            assertColumn(cols, "id",                 "uuid",      "NO");
            assertColumn(cols, "user_id",            "uuid",      "NO");
            assertColumn(cols, "display_name",       "text",      "NO");
            assertColumn(cols, "raw_pattern",        "text",      "NO");
            assertColumn(cols, "category_id",        "uuid",      "NO");
            assertColumn(cols, "confidence",         "numeric",   "NO");
            assertColumn(cols, "match_count",        "integer",   "NO");
            assertColumn(cols, "last_confirmed_at",  "timestamp with time zone", "YES");
            assertColumn(cols, "created_at",         "timestamp with time zone", "NO");
            assertColumn(cols, "updated_at",         "timestamp with time zone", "NO");

            ColumnInfo confidence = cols.get("confidence");
            assertThat(confidence.numericPrecision).isEqualTo(3);
            assertThat(confidence.numericScale).isEqualTo(2);
        }
    }

    @Test
    void shouldEnforceUniqueOnUserIdAndRawPatternWhenInsertingDuplicateMerchant() throws Exception {
        try (Connection conn = openConnection()) {
            UUID categoryId = aSystemCategoryId(conn);

            insertMerchant(conn, SEED_USER, "netflix.com", "Netflix", categoryId);

            assertThatThrownBy(() ->
                insertMerchant(conn, SEED_USER, "netflix.com", "Netflix duplicate", categoryId))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("merchants");
        }
    }

    @Test
    void shouldEnforceConfidenceCheckConstraint() throws Exception {
        try (Connection conn = openConnection()) {
            UUID categoryId = aSystemCategoryId(conn);

            assertThatThrownBy(() ->
                insertMerchantWithConfidence(conn, SEED_USER, "rappi-overcap", "Rappi", categoryId,
                    "1.50"))
                .isInstanceOf(SQLException.class);

            // 0.00 OK
            insertMerchantWithConfidence(conn, SEED_USER, "rappi-zero", "Rappi", categoryId, "0.00");
            // 1.00 OK
            insertMerchantWithConfidence(conn, SEED_USER, "rappi-one", "Rappi", categoryId, "1.00");

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT count(*) FROM myfinance.merchants WHERE confidence IN (0.00, 1.00)")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(2);
            }
        }
    }

    @Test
    void shouldEnforceMatchCountCheckConstraint() throws Exception {
        try (Connection conn = openConnection()) {
            UUID categoryId = aSystemCategoryId(conn);

            assertThatThrownBy(() -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO myfinance.merchants (user_id, display_name, raw_pattern, category_id, match_count) " +
                        "VALUES (?, ?, ?, ?, ?)")) {
                    ps.setObject(1, SEED_USER);
                    ps.setString(2, "Bad merchant");
                    ps.setString(3, "bad-pattern");
                    ps.setObject(4, categoryId);
                    ps.setInt(5, -1);
                    ps.executeUpdate();
                }
            }).isInstanceOf(SQLException.class);
        }
    }

    @Test
    void shouldEnableRlsOnMerchantsTable() throws Exception {
        try (Connection conn = openConnection()) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT relrowsecurity FROM pg_class c " +
                     "JOIN pg_namespace n ON n.oid = c.relnamespace " +
                     "WHERE n.nspname = 'myfinance' AND c.relname = 'merchants'")) {
                assertThat(rs.next())
                    .as("myfinance.merchants must exist in pg_class")
                    .isTrue();
                assertThat(rs.getBoolean("relrowsecurity"))
                    .as("RLS must be enabled on myfinance.merchants")
                    .isTrue();
            }

            // sanity: four policies present (select/insert/update/delete)
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT count(*) FROM pg_policies " +
                     "WHERE schemaname = 'myfinance' AND tablename = 'merchants'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1))
                    .as("expected 4 RLS policies on merchants (select/insert/update/delete)")
                    .isEqualTo(4);
            }
        }
    }

    @Test
    void shouldAddMerchantIdNullableFkOnTransactions() throws Exception {
        try (Connection conn = openConnection()) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT data_type, is_nullable FROM information_schema.columns " +
                     "WHERE table_schema = 'myfinance' AND table_name = 'transactions' " +
                     "  AND column_name = 'merchant_id'")) {
                assertThat(rs.next())
                    .as("merchant_id column must exist on myfinance.transactions")
                    .isTrue();
                assertThat(rs.getString("data_type")).isEqualTo("uuid");
                assertThat(rs.getString("is_nullable")).isEqualTo("YES");
            }

            // FK target must be myfinance.merchants(id)
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT ccu.table_schema AS target_schema, " +
                     "       ccu.table_name   AS target_table, " +
                     "       ccu.column_name  AS target_column " +
                     "FROM information_schema.table_constraints tc " +
                     "JOIN information_schema.key_column_usage kcu " +
                     "  ON tc.constraint_name = kcu.constraint_name " +
                     " AND tc.table_schema = kcu.table_schema " +
                     "JOIN information_schema.constraint_column_usage ccu " +
                     "  ON ccu.constraint_name = tc.constraint_name " +
                     " AND ccu.table_schema = tc.table_schema " +
                     "WHERE tc.constraint_type = 'FOREIGN KEY' " +
                     "  AND tc.table_schema = 'myfinance' " +
                     "  AND tc.table_name = 'transactions' " +
                     "  AND kcu.column_name = 'merchant_id'")) {
                assertThat(rs.next())
                    .as("FK on transactions.merchant_id must exist")
                    .isTrue();
                assertThat(rs.getString("target_schema")).isEqualTo("myfinance");
                assertThat(rs.getString("target_table")).isEqualTo("merchants");
                assertThat(rs.getString("target_column")).isEqualTo("id");
            }
        }
    }

    @Test
    void shouldCreatePartialIndexOnTransactionsMerchantId() throws Exception {
        try (Connection conn = openConnection()) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT indexdef FROM pg_indexes " +
                     "WHERE schemaname = 'myfinance' " +
                     "  AND tablename = 'transactions' " +
                     "  AND indexname = 'idx_transactions_merchant_id'")) {
                assertThat(rs.next())
                    .as("idx_transactions_merchant_id must exist")
                    .isTrue();
                String def = rs.getString("indexdef");
                assertThat(def)
                    .as("partial index predicate must be merchant_id IS NOT NULL")
                    .containsIgnoringCase("WHERE")
                    .containsIgnoringCase("merchant_id IS NOT NULL");
            }
        }
    }

    // ------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------

    private static UUID aSystemCategoryId(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT id FROM myfinance.categories WHERE user_id IS NULL LIMIT 1")) {
            assertThat(rs.next()).isTrue();
            return (UUID) rs.getObject("id");
        }
    }

    private static void insertMerchant(Connection conn, UUID userId, String rawPattern,
                                       String displayName, UUID categoryId) throws SQLException {
        insertMerchantWithConfidence(conn, userId, rawPattern, displayName, categoryId, "0.50");
    }

    private static void insertMerchantWithConfidence(Connection conn, UUID userId, String rawPattern,
                                                     String displayName, UUID categoryId,
                                                     String confidenceLiteral) throws SQLException {
        // Inline numeric literal (validated, no user input) so the CHECK
        // constraint message is exactly what trips — not a parameter-binding
        // numeric conversion error from the JDBC driver.
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(
                "INSERT INTO myfinance.merchants " +
                "(user_id, display_name, raw_pattern, category_id, confidence) VALUES (" +
                "'" + userId + "'::uuid, " +
                "'" + displayName.replace("'", "''") + "', " +
                "'" + rawPattern.replace("'", "''") + "', " +
                "'" + categoryId + "'::uuid, " +
                confidenceLiteral + ")");
        }
    }

    private static Map<String, ColumnInfo> loadColumns(Connection conn, String schema, String table)
            throws SQLException {
        Map<String, ColumnInfo> out = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT column_name, data_type, is_nullable, numeric_precision, numeric_scale " +
                "FROM information_schema.columns " +
                "WHERE table_schema = ? AND table_name = ?")) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ColumnInfo ci = new ColumnInfo();
                    ci.dataType = rs.getString("data_type");
                    ci.isNullable = rs.getString("is_nullable");
                    ci.numericPrecision = (Integer) rs.getObject("numeric_precision");
                    ci.numericScale = (Integer) rs.getObject("numeric_scale");
                    out.put(rs.getString("column_name"), ci);
                }
            }
        }
        return out;
    }

    private static void assertColumn(Map<String, ColumnInfo> cols, String name,
                                     String expectedType, String expectedNullable) {
        ColumnInfo ci = cols.get(name);
        assertThat(ci).as("column %s present", name).isNotNull();
        assertThat(ci.dataType).as("column %s data_type", name).isEqualTo(expectedType);
        assertThat(ci.isNullable).as("column %s is_nullable", name).isEqualTo(expectedNullable);
    }

    private static final class ColumnInfo {
        String dataType;
        String isNullable;
        Integer numericPrecision;
        Integer numericScale;
    }

    @BeforeAll
    static void applyMigrationsOnce() throws Exception {
        Path databaseDir = locateDatabaseDir();
        List<Path> migrations = List.of(
            databaseDir.resolve("local").resolve("V000__local_supabase_stubs.sql"),
            databaseDir.resolve("migrations").resolve("V001__initial_schema.sql"),
            databaseDir.resolve("migrations").resolve("V002__rls_policies.sql"),
            databaseDir.resolve("migrations").resolve("V003__seed_data.sql"),
            databaseDir.resolve("migrations").resolve("V004__categories_display_name.sql"),
            databaseDir.resolve("migrations").resolve("V005__merchants.sql")
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
