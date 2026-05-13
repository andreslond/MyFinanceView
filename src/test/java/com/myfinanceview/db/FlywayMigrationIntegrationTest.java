package com.myfinanceview.db;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Asserts the full Flyway-on-Spring contract: Spring Boot auto-configures Flyway against a
 * Testcontainers Postgres pre-seeded with V000 (local Supabase parity stubs), Flyway applies
 * V001..V003 from the classpath, the history table lands in {@code myfinance}, V000 never
 * appears in history, and {@code flyway.clean()} is rejected by configuration.
 *
 * <p>Why two tests in this repo (this one + {@link com.myfinanceview.config.PostgresTestcontainerTest}):
 * the plain-JDBC test verifies the SQL files themselves apply against vanilla Postgres
 * regardless of Spring; this one verifies Spring's auto-config, location resolution, baseline
 * policy, and history-table behaviour. Losing either test would leave a blind spot. See
 * {@code openspec/changes/flyway-migrations/design.md} D7.
 */
@SpringBootTest
@Testcontainers
class FlywayMigrationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17")
        .withDatabaseName("myfinance_test")
        .withUsername("test")
        .withPassword("test")
        // V000 is applied BEFORE Spring boots Flyway. The init script lives on the test
        // classpath at src/test/resources/local/ (a copy of database/local/V000__...).
        // Flyway itself never sees this file — its locations stay at classpath:db/migration.
        .withInitScript("local/V000__local_supabase_stubs.sql");

    @DynamicPropertySource
    static void registerDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired
    DataSource dataSource;

    @Autowired
    Flyway flyway;

    @Test
    void shouldRecordEveryClasspathMigrationInHistoryWhenSpringBoots() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            Integer historyTableExists = scalarInt(conn,
                "SELECT count(*) FROM information_schema.tables "
                    + "WHERE table_schema = 'myfinance' AND table_name = 'flyway_schema_history'");
            assertThat(historyTableExists)
                .as("myfinance.flyway_schema_history must exist after Spring auto-runs Flyway")
                .isEqualTo(1);

            List<HistoryRow> rows = readHistory(conn);

            // Flyway 10 stores versions matching the numeric part of the filename exactly,
            // so V001__initial_schema.sql → version "001", V002 → "002", V003 → "003".
            // A leading null-version row may appear for Flyway's internal schema-creation
            // entry (type SCHEMA) — that is expected and intentional; it is NOT a migration.
            assertThat(rows)
                .as("history should contain at least V001, V002, V003")
                .extracting(HistoryRow::version)
                .contains("001", "002", "003");
            // Only migration rows (non-null version) must have success=true. The schema-creation
            // entry (version=null) has success=true as well, but we filter to avoid ambiguity.
            assertThat(rows)
                .as("every recorded migration must have success=true")
                .allSatisfy(r -> assertThat(r.success()).isTrue());
            assertThat(rows)
                .as("no history row may reference V000 or the local stubs file")
                .allSatisfy(r -> assertThat(r.script())
                    .doesNotContain("V000")
                    .doesNotContain("local_supabase_stubs"));

            Integer categoryCount = scalarInt(conn,
                "SELECT count(*) FROM myfinance.categories");
            assertThat(categoryCount)
                .as("V003 seed should leave at least 19 categories")
                .isGreaterThanOrEqualTo(19);
        }
    }

    @Test
    void shouldRejectFlywayCleanWhenInvoked() {
        // Flyway 10 message: "Unable to execute clean as it has been disabled with the
        // 'flyway.cleanDisabled' property." — matches on the property name fragment.
        assertThatThrownBy(() -> flyway.clean())
            .isInstanceOf(FlywayException.class)
            .hasMessageContaining("cleanDisabled");
    }

    private static Integer scalarInt(Connection conn, String sql) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            return rs.getInt(1);
        }
    }

    private static List<HistoryRow> readHistory(Connection conn) throws Exception {
        List<HistoryRow> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT version, script, success FROM myfinance.flyway_schema_history "
                    + "ORDER BY installed_rank");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(new HistoryRow(
                    rs.getString("version"),
                    rs.getString("script"),
                    rs.getBoolean("success")));
            }
        }
        return rows;
    }

    private record HistoryRow(String version, String script, boolean success) {}
}
