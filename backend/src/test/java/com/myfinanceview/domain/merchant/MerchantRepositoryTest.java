package com.myfinanceview.domain.merchant;

import com.myfinanceview.db.JooqTestSupport;
import com.myfinanceview.jooq.generated.tables.records.MerchantsRecord;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static com.myfinanceview.jooq.generated.Tables.CATEGORIES;
import static com.myfinanceview.jooq.generated.Tables.MERCHANTS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link MerchantRepository} per tasks.md §5 deliverables: idempotent UPSERT, confirm with
 * cap, drift reset, and the cross-user isolation guard. Uses Testcontainers Postgres 17 with
 * V001..V005 — no DB mocks (project rule).
 *
 * <p><b>Container lifecycle:</b> manual start (no {@code @Testcontainers} / {@code @Container}).
 * The {@code @Testcontainers} extension calls {@code container.stop()} at class teardown which —
 * on Windows Docker Desktop — kills the shared reused container and causes "Connection refused"
 * in all later test classes that share the same container config. Ryuk handles JVM-exit cleanup.
 */
class MerchantRepositoryTest {

    private static final UUID USER_A = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID USER_B = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

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

    private static Connection connection;
    private static DSLContext dsl;
    private static MerchantRepository repo;

    private static UUID systemCategoryX;
    private static UUID systemCategoryY;

    @BeforeAll
    static void setupOnce() throws Exception {
        JooqTestSupport.applyAllMigrations(postgres);
        connection = JooqTestSupport.openConnection(postgres);
        dsl = JooqTestSupport.dsl(connection);
        repo = new MerchantRepository(dsl);

        connection.createStatement().execute(
            "INSERT INTO auth.users(id) VALUES ('" + USER_A + "'), ('" + USER_B + "') ON CONFLICT DO NOTHING");

        // Two distinct system categories so we can exercise drift (catX -> catY).
        var systemCats = dsl.select(CATEGORIES.ID)
            .from(CATEGORIES)
            .where(CATEGORIES.USER_ID.isNull())
            .orderBy(CATEGORIES.DISPLAY_NAME.asc())
            .limit(2)
            .fetch(CATEGORIES.ID);
        systemCategoryX = systemCats.get(0);
        systemCategoryY = systemCats.get(1);
    }

    @AfterAll
    static void closeConnection() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    @BeforeEach
    void cleanMerchantsTable() {
        dsl.deleteFrom(MERCHANTS).execute();
    }

    @Test
    void shouldInsertNewMerchantWhenUpsertByRawPatternNotExists() {
        UUID id = repo.upsertByRawPattern(USER_A, "netflix.com", "Netflix", systemCategoryX);

        Optional<MerchantsRecord> rec = repo.findById(id, USER_A);
        assertThat(rec).isPresent();
        assertThat(rec.get().getRawPattern()).isEqualTo("netflix.com");
        assertThat(rec.get().getDisplayName()).isEqualTo("Netflix");
        assertThat(rec.get().getCategoryId()).isEqualTo(systemCategoryX);
        assertThat(rec.get().getConfidence()).isEqualByComparingTo(new BigDecimal("0.50"));
        assertThat(rec.get().getMatchCount()).isEqualTo(1);
        assertThat(rec.get().getLastConfirmedAt()).isNotNull();
    }

    @Test
    void shouldReturnSameIdWhenUpsertByRawPatternAlreadyExists() {
        UUID first = repo.upsertByRawPattern(USER_A, "netflix.com", "Netflix", systemCategoryX);
        UUID second = repo.upsertByRawPattern(USER_A, "netflix.com", "Netflix", systemCategoryX);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void shouldIsolateUpsertBetweenUsers() {
        UUID aId = repo.upsertByRawPattern(USER_A, "rappi", "Rappi", systemCategoryX);
        UUID bId = repo.upsertByRawPattern(USER_B, "rappi", "Rappi", systemCategoryY);

        assertThat(aId).isNotEqualTo(bId);
        // userA cannot see userB's merchant even with the same raw_pattern.
        assertThat(repo.findById(bId, USER_A)).isEmpty();
    }

    @Test
    void shouldIncrementConfidenceCappedAtOneWhenConfirmCategory() {
        UUID id = repo.upsertByRawPattern(USER_A, "didi", "DiDi", systemCategoryX);
        // upsert leaves confidence at 0.50; nudge to 0.95 directly to exercise the cap.
        dsl.update(MERCHANTS)
            .set(MERCHANTS.CONFIDENCE, new BigDecimal("0.95"))
            .where(MERCHANTS.ID.eq(id))
            .execute();

        int firstAffected = repo.confirmCategory(id, USER_A);
        assertThat(firstAffected).isEqualTo(1);
        MerchantsRecord afterFirst = repo.findById(id, USER_A).orElseThrow();
        // 0.95 + 0.10 = 1.05 -> capped to 1.00
        assertThat(afterFirst.getConfidence()).isEqualByComparingTo(new BigDecimal("1.00"));
        assertThat(afterFirst.getMatchCount()).isEqualTo(2);

        int secondAffected = repo.confirmCategory(id, USER_A);
        assertThat(secondAffected).isEqualTo(1);
        MerchantsRecord afterSecond = repo.findById(id, USER_A).orElseThrow();
        // already at cap -> stays at 1.00, match_count keeps incrementing.
        assertThat(afterSecond.getConfidence()).isEqualByComparingTo(new BigDecimal("1.00"));
        assertThat(afterSecond.getMatchCount()).isEqualTo(3);
    }

    @Test
    void shouldResetCategoryConfidenceAndMatchCountWhenResetForDrift() {
        UUID id = repo.upsertByRawPattern(USER_A, "starbucks", "Starbucks", systemCategoryX);
        dsl.update(MERCHANTS)
            .set(MERCHANTS.CONFIDENCE, new BigDecimal("0.90"))
            .set(MERCHANTS.MATCH_COUNT, 5)
            .where(MERCHANTS.ID.eq(id))
            .execute();
        OffsetDateTime beforeReset = repo.findById(id, USER_A).orElseThrow().getLastConfirmedAt();

        int affected = repo.resetForDrift(id, USER_A, systemCategoryY);
        assertThat(affected).isEqualTo(1);

        MerchantsRecord after = repo.findById(id, USER_A).orElseThrow();
        assertThat(after.getCategoryId()).isEqualTo(systemCategoryY);
        assertThat(after.getConfidence()).isEqualByComparingTo(new BigDecimal("0.50"));
        assertThat(after.getMatchCount()).isEqualTo(1);
        assertThat(after.getLastConfirmedAt()).isAfterOrEqualTo(beforeReset);
        // display_name must NOT change on drift (design.md D5 — identity stays, classification resets).
        assertThat(after.getDisplayName()).isEqualTo("Starbucks");
    }

    @Test
    void shouldReturnZeroWhenConfirmCategoryOfOtherUsersMerchant() {
        UUID bMerchantId = repo.upsertByRawPattern(USER_B, "amazon", "Amazon", systemCategoryX);

        int affected = repo.confirmCategory(bMerchantId, USER_A);
        assertThat(affected).isEqualTo(0);

        // Sanity: userB's merchant is untouched (match_count still 1 from upsert).
        MerchantsRecord untouched = repo.findById(bMerchantId, USER_B).orElseThrow();
        assertThat(untouched.getMatchCount()).isEqualTo(1);
    }

    @Test
    void shouldReturnZeroWhenResetForDriftOfOtherUsersMerchant() {
        UUID bMerchantId = repo.upsertByRawPattern(USER_B, "uber", "Uber", systemCategoryX);

        int affected = repo.resetForDrift(bMerchantId, USER_A, systemCategoryY);
        assertThat(affected).isEqualTo(0);

        MerchantsRecord untouched = repo.findById(bMerchantId, USER_B).orElseThrow();
        assertThat(untouched.getCategoryId()).isEqualTo(systemCategoryX);
    }

    @Test
    void shouldReturnEmptyWhenFindByIdOfOtherUsersMerchant() {
        UUID bMerchantId = repo.upsertByRawPattern(USER_B, "spotify", "Spotify", systemCategoryX);

        assertThat(repo.findById(bMerchantId, USER_A)).isEmpty();
    }
}
