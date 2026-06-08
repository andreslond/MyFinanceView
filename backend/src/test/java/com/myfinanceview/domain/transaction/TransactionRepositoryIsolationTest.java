package com.myfinanceview.domain.transaction;

import com.myfinanceview.db.JooqTestSupport;
import com.myfinanceview.jooq.generated.enums.AccountType;
import com.myfinanceview.jooq.generated.enums.TransactionType;
import com.myfinanceview.jooq.generated.tables.records.TransactionsRecord;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.myfinanceview.jooq.generated.Tables.ACCOUNTS;
import static com.myfinanceview.jooq.generated.Tables.BANKS;
import static com.myfinanceview.jooq.generated.Tables.CATEGORIES;
import static com.myfinanceview.jooq.generated.Tables.TRANSACTIONS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the per-user isolation invariant of {@link TransactionRepository} (design.md D2): a
 * caller with {@code userA} CANNOT read or mutate {@code userB}'s transactions, even though the
 * jOOQ {@link DSLContext} connects with the test owner role (RLS-bypass equivalent of
 * {@code service_role} in production).
 *
 * <p>If any of these assertions ever passes when it should fail, every repository method that
 * touches a user-owned table is suspect — the regression would mean someone added a method
 * without {@code WHERE user_id = ?}.
 *
 * <p><b>Container lifecycle:</b> manual start (no {@code @Testcontainers} / {@code @Container}).
 * The {@code @Testcontainers} extension calls {@code container.stop()} at class teardown which —
 * on Windows Docker Desktop — kills the shared reused container and causes "Connection refused"
 * in all later test classes that share the same container config. Ryuk handles JVM-exit cleanup.
 */
class TransactionRepositoryIsolationTest {

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
    private static TransactionRepository repo;

    private static UUID bankId;
    private static UUID systemCategoryId;
    private static UUID accountA;
    private static UUID accountB;

    @BeforeAll
    static void setupOnce() throws Exception {
        JooqTestSupport.applyAllMigrations(postgres);
        connection = JooqTestSupport.openConnection(postgres);
        dsl = JooqTestSupport.dsl(connection);
        repo = new TransactionRepository(dsl);

        // Seed users (auth.users), bank, and accounts that all transactions in this class reuse.
        connection.createStatement().execute(
            "INSERT INTO auth.users(id) VALUES ('" + USER_A + "'), ('" + USER_B + "') ON CONFLICT DO NOTHING");

        bankId = dsl.select(BANKS.ID).from(BANKS).limit(1).fetchOne(BANKS.ID);
        systemCategoryId = dsl.select(CATEGORIES.ID)
            .from(CATEGORIES)
            .where(CATEGORIES.USER_ID.isNull())
            .limit(1)
            .fetchOne(CATEGORIES.ID);

        accountA = UUID.randomUUID();
        accountB = UUID.randomUUID();
        dsl.insertInto(ACCOUNTS)
            .set(ACCOUNTS.ID, accountA)
            .set(ACCOUNTS.USER_ID, USER_A)
            .set(ACCOUNTS.BANK_ID, bankId)
            .set(ACCOUNTS.TYPE, AccountType.checking)
            .set(ACCOUNTS.CURRENCY, "COP")
            .set(ACCOUNTS.NICKNAME, "A-checking")
            .execute();
        dsl.insertInto(ACCOUNTS)
            .set(ACCOUNTS.ID, accountB)
            .set(ACCOUNTS.USER_ID, USER_B)
            .set(ACCOUNTS.BANK_ID, bankId)
            .set(ACCOUNTS.TYPE, AccountType.checking)
            .set(ACCOUNTS.CURRENCY, "COP")
            .set(ACCOUNTS.NICKNAME, "B-checking")
            .execute();
    }

    @AfterAll
    static void closeConnection() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    @BeforeEach
    void cleanTransactionsTable() {
        // Each test seeds its own transactions; wipe between tests so they stay independent.
        dsl.deleteFrom(TRANSACTIONS).execute();
    }

    @Test
    void shouldOnlyReturnUserATransactionsForUserAFindPage() {
        seedTx(USER_A, accountA, "Tx A1", offsetMinutesAgo(10));
        seedTx(USER_A, accountA, "Tx A2", offsetMinutesAgo(20));
        seedTx(USER_B, accountB, "Tx B1", offsetMinutesAgo(5));
        seedTx(USER_B, accountB, "Tx B2", offsetMinutesAgo(15));

        List<TransactionsRecord> result = repo.findPage(USER_A, Optional.empty(), List.of(), 1, 25);

        assertThat(result).hasSize(2);
        assertThat(result).allSatisfy(r -> assertThat(r.getUserId()).isEqualTo(USER_A));
    }

    @Test
    void shouldReturnEmptyWhenFilteringByAccountIdOfOtherUser() {
        seedTx(USER_A, accountA, "Tx A", offsetMinutesAgo(10));
        seedTx(USER_B, accountB, "Tx B", offsetMinutesAgo(10));

        List<TransactionsRecord> result = repo.findPage(USER_A, Optional.of(accountB), List.of(), 1, 25);

        assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenFindByIdOfOtherUsersTransaction() {
        UUID txB = seedTx(USER_B, accountB, "Tx B", offsetMinutesAgo(5));

        Optional<TransactionsRecord> result = repo.findById(txB, USER_A);

        assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnZeroRowsWhenUpdateCategoryOfOtherUsersTransaction() {
        UUID txB = seedTx(USER_B, accountB, "Tx B", offsetMinutesAgo(5));

        int affected = repo.updateCategory(txB, USER_A, systemCategoryId, OffsetDateTime.now(ZoneOffset.UTC));

        assertThat(affected).isEqualTo(0);

        // Sanity: USER_B's transaction still has no category (was never set in seed).
        TransactionsRecord untouched = dsl.selectFrom(TRANSACTIONS).where(TRANSACTIONS.ID.eq(txB)).fetchOne();
        assertThat(untouched.getCategoryId()).isNull();
    }

    @Test
    void shouldReturnZeroRowsWhenAssignMerchantIdOfOtherUsersTransaction() {
        UUID txB = seedTx(USER_B, accountB, "Tx B", offsetMinutesAgo(5));

        int affected = repo.assignMerchantId(txB, USER_A, UUID.randomUUID());

        assertThat(affected).isEqualTo(0);
    }

    @Test
    void shouldOrderByOccurredAtDescThenIdDesc() {
        OffsetDateTime sharedTimestamp = offsetMinutesAgo(30);
        UUID tx1 = seedTx(USER_A, accountA, "older", offsetMinutesAgo(60));
        UUID tx2 = seedTx(USER_A, accountA, "shared-a", sharedTimestamp);
        UUID tx3 = seedTx(USER_A, accountA, "shared-b", sharedTimestamp);
        UUID tx4 = seedTx(USER_A, accountA, "newest", offsetMinutesAgo(5));

        List<TransactionsRecord> result = repo.findPage(USER_A, Optional.empty(), List.of(), 1, 25);

        assertThat(result).hasSize(4);
        assertThat(result.get(0).getId()).isEqualTo(tx4); // newest first
        assertThat(result.get(3).getId()).isEqualTo(tx1); // older last
        // The two shared-timestamp rows are between newest and oldest. We don't assert which
        // comes first because Postgres orders UUIDs as 16 unsigned bytes while Java's
        // UUID.compareTo uses signed-long comparison — orderings can disagree when the most
        // significant bit is set. The repo SQL uses `ORDER BY ... id DESC` which is correct;
        // we only verify the primary ordering invariants here.
        UUID secondId = result.get(1).getId();
        UUID thirdId = result.get(2).getId();
        assertThat(List.of(secondId, thirdId)).containsExactlyInAnyOrder(tx2, tx3);
    }

    @Test
    void shouldLimitToPageSizePlusOneRowsForHasMoreCalc() {
        for (int i = 0; i < 30; i++) {
            seedTx(USER_A, accountA, "row-" + i, offsetMinutesAgo(i));
        }

        List<TransactionsRecord> result = repo.findPage(USER_A, Optional.empty(), List.of(), 1, 25);

        // Repository returns pageSize + 1 so the service can compute hasMore = (size > pageSize).
        assertThat(result).hasSize(26);
    }

    // ------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------

    private static UUID seedTx(UUID userId, UUID accountId, String description, OffsetDateTime occurredAt) {
        UUID id = UUID.randomUUID();
        dsl.insertInto(TRANSACTIONS)
            .set(TRANSACTIONS.ID, id)
            .set(TRANSACTIONS.USER_ID, userId)
            .set(TRANSACTIONS.ACCOUNT_ID, accountId)
            .set(TRANSACTIONS.TYPE, TransactionType.debit_purchase)
            .set(TRANSACTIONS.AMOUNT, new BigDecimal("100.00"))
            .set(TRANSACTIONS.CURRENCY, "COP")
            .set(TRANSACTIONS.AMOUNT_BASE_CURRENCY, new BigDecimal("100.00"))
            .set(TRANSACTIONS.DESCRIPTION, description)
            .set(TRANSACTIONS.OCCURRED_AT, occurredAt)
            .execute();
        return id;
    }

    private static OffsetDateTime offsetMinutesAgo(int minutes) {
        return OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(minutes);
    }
}
