package com.myfinanceview.domain.category;

import com.myfinanceview.db.JooqTestSupport;
import com.myfinanceview.jooq.generated.enums.CategoryType;
import com.myfinanceview.jooq.generated.tables.records.CategoriesRecord;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.myfinanceview.jooq.generated.Tables.CATEGORIES;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the visibility guard in {@link CategoryRepository#findByIdVisibleToUser} — the
 * anti-IDOR pillar of design.md D10. The query must collapse three distinct outcomes into a
 * binary "visible or not": the caller cannot tell apart "category does not exist", "category
 * exists but belongs to another user", and "category is system" via this surface.
 *
 * <p><b>Container lifecycle:</b> manual start (no {@code @Testcontainers} / {@code @Container}).
 * The {@code @Testcontainers} extension calls {@code container.stop()} at class teardown which —
 * on Windows Docker Desktop — kills the shared reused container and causes "Connection refused"
 * in all later test classes that share the same container config. Ryuk handles JVM-exit cleanup.
 */
class CategoryRepositoryVisibilityGuardTest {

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
    private static CategoryRepository repo;

    private static UUID systemCategoryId;
    private static UUID userAOwnCategoryId;
    private static UUID userBPrivateCategoryId;

    @BeforeAll
    static void setupOnce() throws Exception {
        JooqTestSupport.applyAllMigrations(postgres);
        connection = JooqTestSupport.openConnection(postgres);
        dsl = JooqTestSupport.dsl(connection);
        repo = new CategoryRepository(dsl);

        connection.createStatement().execute(
            "INSERT INTO auth.users(id) VALUES ('" + USER_A + "'), ('" + USER_B + "') ON CONFLICT DO NOTHING");

        // Pick any one system category — V003 seeded 19 of them.
        systemCategoryId = dsl.select(CATEGORIES.ID)
            .from(CATEGORIES)
            .where(CATEGORIES.USER_ID.isNull())
            .limit(1)
            .fetchOne(CATEGORIES.ID);

        userAOwnCategoryId = UUID.randomUUID();
        userBPrivateCategoryId = UUID.randomUUID();

        dsl.insertInto(CATEGORIES)
            .set(CATEGORIES.ID, userAOwnCategoryId)
            .set(CATEGORIES.USER_ID, USER_A)
            .set(CATEGORIES.NAME, "A-Custom")
            .set(CATEGORIES.DISPLAY_NAME, "A Personalizada")
            .set(CATEGORIES.TYPE, CategoryType.expense)
            .execute();
        dsl.insertInto(CATEGORIES)
            .set(CATEGORIES.ID, userBPrivateCategoryId)
            .set(CATEGORIES.USER_ID, USER_B)
            .set(CATEGORIES.NAME, "B-Private")
            .set(CATEGORIES.DISPLAY_NAME, "B Privada")
            .set(CATEGORIES.TYPE, CategoryType.expense)
            .execute();
    }

    @AfterAll
    static void closeConnection() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    @BeforeEach
    void noOpResetBetweenTests() {
        // Seeded data is shared and read-only for these tests — nothing to reset.
    }

    @Test
    void shouldReturnSystemCategoryWhenFindByIdVisibleToUser() {
        Optional<CategoriesRecord> result = repo.findByIdVisibleToUser(systemCategoryId, USER_A);
        assertThat(result).isPresent();
        assertThat(result.get().getUserId()).isNull();
    }

    @Test
    void shouldReturnOwnCustomCategoryWhenFindByIdVisibleToUser() {
        Optional<CategoriesRecord> result = repo.findByIdVisibleToUser(userAOwnCategoryId, USER_A);
        assertThat(result).isPresent();
        assertThat(result.get().getUserId()).isEqualTo(USER_A);
    }

    @Test
    void shouldReturnEmptyWhenCustomCategoryBelongsToAnotherUser() {
        // The crown jewel of D10: userA queries userB's private category by exact UUID -> empty.
        Optional<CategoriesRecord> result = repo.findByIdVisibleToUser(userBPrivateCategoryId, USER_A);
        assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenCategoryIdDoesNotExist() {
        Optional<CategoriesRecord> result = repo.findByIdVisibleToUser(
            UUID.fromString("00000000-0000-0000-0000-000000000000"), USER_A);
        assertThat(result).isEmpty();
    }

    @Test
    void shouldIncludeSystemAndOwnCategoriesWhenFindAllVisibleToUser() {
        List<CategoriesRecord> result = repo.findAllVisibleToUser(USER_A);

        // All system categories (19 from V003) plus USER_A's one custom category.
        assertThat(result.size()).isEqualTo(20);
        // None of the rows belong to USER_B.
        assertThat(result).allSatisfy(rec ->
            assertThat(rec.getUserId() == null || rec.getUserId().equals(USER_A))
                .as("category %s leaked from another user (user_id=%s)", rec.getId(), rec.getUserId())
                .isTrue());
    }
}
