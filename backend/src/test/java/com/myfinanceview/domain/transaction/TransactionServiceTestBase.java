package com.myfinanceview.domain.transaction;

import com.myfinanceview.config.PostgresJooqIntegrationTestBase;
import com.myfinanceview.jooq.generated.enums.AccountType;
import com.myfinanceview.jooq.generated.enums.TransactionType;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static com.myfinanceview.jooq.generated.Tables.ACCOUNTS;
import static com.myfinanceview.jooq.generated.Tables.BANKS;
import static com.myfinanceview.jooq.generated.Tables.CATEGORIES;
import static com.myfinanceview.jooq.generated.Tables.MERCHANTS;
import static com.myfinanceview.jooq.generated.Tables.TRANSACTIONS;

/**
 * Shared seeding helpers for {@code TransactionService*} tests. Each concrete test class extends
 * this base, gets a fresh schema (auto-rolled-back per @Test via {@code @Transactional}), and
 * uses the helpers to insert ad-hoc users / accounts / categories / merchants / transactions.
 */
abstract class TransactionServiceTestBase extends PostgresJooqIntegrationTestBase {

    static final UUID USER_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final UUID USER_B = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    @Autowired DSLContext dsl;

    /** Insert an auth.users row idempotently. */
    void seedUser(UUID userId) {
        dsl.execute("INSERT INTO auth.users(id) VALUES (?) ON CONFLICT DO NOTHING", userId);
    }

    UUID anyBankId() {
        return dsl.select(BANKS.ID).from(BANKS).limit(1).fetchOne(BANKS.ID);
    }

    /** Pick the first system category (user_id IS NULL). */
    UUID anySystemCategoryId() {
        return dsl.select(CATEGORIES.ID)
            .from(CATEGORIES)
            .where(CATEGORIES.USER_ID.isNull())
            .limit(1)
            .fetchOne(CATEGORIES.ID);
    }

    /** Pick the second system category (different from {@link #anySystemCategoryId()}). */
    UUID anotherSystemCategoryId(UUID different) {
        return dsl.select(CATEGORIES.ID)
            .from(CATEGORIES)
            .where(CATEGORIES.USER_ID.isNull())
            .and(CATEGORIES.ID.notEqual(different))
            .limit(1)
            .fetchOne(CATEGORIES.ID);
    }

    /** Create a custom category owned by the given user. */
    UUID seedCustomCategory(UUID userId, String englishName, String displayName) {
        UUID id = UUID.randomUUID();
        dsl.insertInto(CATEGORIES)
            .set(CATEGORIES.ID, id)
            .set(CATEGORIES.USER_ID, userId)
            .set(CATEGORIES.NAME, englishName)
            .set(CATEGORIES.DISPLAY_NAME, displayName)
            .set(CATEGORIES.TYPE, com.myfinanceview.jooq.generated.enums.CategoryType.expense)
            .execute();
        return id;
    }

    UUID seedAccount(UUID userId, String nickname) {
        UUID id = UUID.randomUUID();
        dsl.insertInto(ACCOUNTS)
            .set(ACCOUNTS.ID, id)
            .set(ACCOUNTS.USER_ID, userId)
            .set(ACCOUNTS.BANK_ID, anyBankId())
            .set(ACCOUNTS.TYPE, AccountType.checking)
            .set(ACCOUNTS.CURRENCY, "COP")
            .set(ACCOUNTS.NICKNAME, nickname)
            .execute();
        return id;
    }

    UUID seedMerchant(UUID userId, String rawPattern, String displayName, UUID categoryId,
                      BigDecimal confidence, int matchCount, OffsetDateTime lastConfirmedAt) {
        UUID id = UUID.randomUUID();
        dsl.insertInto(MERCHANTS)
            .set(MERCHANTS.ID, id)
            .set(MERCHANTS.USER_ID, userId)
            .set(MERCHANTS.RAW_PATTERN, rawPattern)
            .set(MERCHANTS.DISPLAY_NAME, displayName)
            .set(MERCHANTS.CATEGORY_ID, categoryId)
            .set(MERCHANTS.CONFIDENCE, confidence)
            .set(MERCHANTS.MATCH_COUNT, matchCount)
            .set(MERCHANTS.LAST_CONFIRMED_AT, lastConfirmedAt)
            .execute();
        return id;
    }

    UUID seedTransaction(UUID userId, UUID accountId, UUID categoryId, UUID merchantId,
                         String description, OffsetDateTime occurredAt) {
        UUID id = UUID.randomUUID();
        var step = dsl.insertInto(TRANSACTIONS)
            .set(TRANSACTIONS.ID, id)
            .set(TRANSACTIONS.USER_ID, userId)
            .set(TRANSACTIONS.ACCOUNT_ID, accountId)
            .set(TRANSACTIONS.TYPE, TransactionType.debit_purchase)
            .set(TRANSACTIONS.AMOUNT, new BigDecimal("100.00"))
            .set(TRANSACTIONS.CURRENCY, "COP")
            .set(TRANSACTIONS.AMOUNT_BASE_CURRENCY, new BigDecimal("100.00"))
            .set(TRANSACTIONS.DESCRIPTION, description)
            .set(TRANSACTIONS.OCCURRED_AT, occurredAt);
        if (categoryId != null) step = step.set(TRANSACTIONS.CATEGORY_ID, categoryId);
        if (merchantId != null) step = step.set(TRANSACTIONS.MERCHANT_ID, merchantId);
        step.execute();
        return id;
    }

    static OffsetDateTime offsetMinutesAgo(int minutes) {
        return OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(minutes);
    }
}
