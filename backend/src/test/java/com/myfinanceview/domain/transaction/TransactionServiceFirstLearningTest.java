package com.myfinanceview.domain.transaction;

import com.myfinanceview.jooq.generated.tables.records.MerchantsRecord;
import com.myfinanceview.jooq.generated.tables.records.TransactionsRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static com.myfinanceview.jooq.generated.Tables.MERCHANTS;
import static com.myfinanceview.jooq.generated.Tables.TRANSACTIONS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * First-learning contract (tasks.md §6.9, spec.md scenario "Transacción sin merchant resuelto
 * crea uno"): PATCH on a transaction with {@code merchant_id == null} creates a merchant via
 * UPSERT on {@code (user_id, raw_pattern)} with confidence=0.50, match_count=1, and assigns
 * {@code transaction.merchant_id} to the new merchant.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles({"test", "service"})
@Transactional
class TransactionServiceFirstLearningTest extends TransactionServiceTestBase {

    @Autowired TransactionService service;

    @Test
    void shouldUpsertMerchantAndAssignWhenTransactionHasNoMerchantYet() {
        seedUser(USER_A);
        UUID catY = anySystemCategoryId();
        UUID accountA = seedAccount(USER_A, "checking");

        String description = "NETFLIX.COM *1234";
        UUID txId = seedTransaction(USER_A, accountA, /*categoryId*/ null, /*merchantId*/ null,
            description, offsetMinutesAgo(15));

        service.updateCategory(USER_A, txId, catY);

        // Merchant exists with the normalized raw_pattern.
        MerchantsRecord merchant = dsl.selectFrom(MERCHANTS)
            .where(MERCHANTS.USER_ID.eq(USER_A))
            .and(MERCHANTS.RAW_PATTERN.eq("netflix.com"))
            .fetchOne();
        assertThat(merchant).isNotNull();
        assertThat(merchant.getDisplayName()).isEqualTo(description);
        assertThat(merchant.getCategoryId()).isEqualTo(catY);
        assertThat(merchant.getConfidence()).isEqualByComparingTo("0.50");
        assertThat(merchant.getMatchCount()).isEqualTo(1);
        assertThat(merchant.getLastConfirmedAt()).isNotNull();

        // Transaction now references the new merchant + new category.
        TransactionsRecord tx = dsl.selectFrom(TRANSACTIONS)
            .where(TRANSACTIONS.ID.eq(txId)).fetchOne();
        assertThat(tx.getMerchantId()).isEqualTo(merchant.getId());
        assertThat(tx.getCategoryId()).isEqualTo(catY);
    }
}
