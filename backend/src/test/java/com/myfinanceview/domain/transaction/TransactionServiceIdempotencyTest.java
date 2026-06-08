package com.myfinanceview.domain.transaction;

import com.myfinanceview.api.dto.TransactionDTO;
import com.myfinanceview.jooq.generated.tables.records.MerchantsRecord;
import com.myfinanceview.jooq.generated.tables.records.TransactionsRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static com.myfinanceview.jooq.generated.Tables.MERCHANTS;
import static com.myfinanceview.jooq.generated.Tables.TRANSACTIONS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Idempotency contract (tasks.md §6.6, spec.md scenario "Idempotencia sin cambio"):
 * PATCH with {@code categoryId == currentCategoryId} MUST NOT write to DB. Verified by
 * snapshotting {@code updated_at} on the transaction AND the merchant's confidence/match_count/
 * last_confirmed_at before/after the call.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles({"test", "service"})
@Transactional
class TransactionServiceIdempotencyTest extends TransactionServiceTestBase {

    @Autowired TransactionService service;

    @Test
    void shouldNotMutateDbWhenCategoryIdEqualsCurrent() {
        seedUser(USER_A);
        UUID catA = anySystemCategoryId();
        UUID accountA = seedAccount(USER_A, "checking");
        BigDecimal preConfidence = new BigDecimal("0.60");
        OffsetDateTime preLastConfirmed = OffsetDateTime.now(java.time.ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS).minusDays(2);
        UUID merchantId = seedMerchant(USER_A, "netflix.com", "NETFLIX.COM *1234",
            catA, preConfidence, 3, preLastConfirmed);
        UUID txId = seedTransaction(USER_A, accountA, catA, merchantId,
            "NETFLIX.COM *1234", offsetMinutesAgo(15));

        TransactionsRecord beforeTx = dsl.selectFrom(TRANSACTIONS)
            .where(TRANSACTIONS.ID.eq(txId)).fetchOne();
        OffsetDateTime preTxUpdatedAt = beforeTx.getUpdatedAt();

        TransactionDTO result = service.updateCategory(USER_A, txId, catA);

        // Returned DTO reflects current state with same category.
        assertThat(result.categoryId()).isEqualTo(catA);

        // Transaction row: updated_at unchanged.
        TransactionsRecord afterTx = dsl.selectFrom(TRANSACTIONS)
            .where(TRANSACTIONS.ID.eq(txId)).fetchOne();
        assertThat(afterTx.getUpdatedAt()).isEqualTo(preTxUpdatedAt);
        assertThat(afterTx.getCategoryId()).isEqualTo(catA);

        // Merchant row: confidence + match_count + last_confirmed_at all unchanged.
        MerchantsRecord afterMerchant = dsl.selectFrom(MERCHANTS)
            .where(MERCHANTS.ID.eq(merchantId)).fetchOne();
        assertThat(afterMerchant.getConfidence()).isEqualByComparingTo(preConfidence);
        assertThat(afterMerchant.getMatchCount()).isEqualTo(3);
        assertThat(afterMerchant.getLastConfirmedAt()).isEqualTo(preLastConfirmed);
    }
}
