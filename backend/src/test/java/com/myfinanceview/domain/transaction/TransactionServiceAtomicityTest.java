package com.myfinanceview.domain.transaction;

import com.myfinanceview.domain.merchant.MerchantUpserter;
import com.myfinanceview.jooq.generated.tables.records.MerchantsRecord;
import com.myfinanceview.jooq.generated.tables.records.TransactionsRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static com.myfinanceview.jooq.generated.Tables.MERCHANTS;
import static com.myfinanceview.jooq.generated.Tables.TRANSACTIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Atomicity contract (tasks.md §6.11, spec.md scenario "Atomicidad — rollback si el feedback
 * loop falls"): when the merchant feedback loop fails after the transaction UPDATE has been
 * issued, the {@code @Transactional} on {@link TransactionService#updateCategory} MUST roll back
 * both writes — leaving {@code transactions.category_id} and the merchant snapshot unchanged.
 *
 * <p><b>Recipe choice — option (b).</b> Recipe (a) (force FK violation by deleting catB inside
 * the same JVM between the visibility guard and the UPDATE) is timing-fragile inside a single
 * service call because the guard SELECT and UPDATE run sequentially with no test hook between
 * them. Option (b) uses {@link MockBean} on {@link MerchantUpserter} to throw a
 * {@link RuntimeException} from {@code applyFeedback(...)} — a NARROW exception to the project's
 * "no DB mocks" rule. The service still hits real Postgres for the visibility guard, the
 * transaction lookup, and the {@code transactions.category_id} UPDATE; only the merchant write
 * is intercepted. This is the path the spec scenario explicitly allows.
 *
 * <p>Note: NOT annotated with {@code @Transactional} on the class — we WANT the service's own
 * {@code @Transactional} to commit/rollback authoritatively. Cleanup happens by setting up users
 * with UUIDs unique to this test class.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles({"test", "service"})
class TransactionServiceAtomicityTest extends TransactionServiceTestBase {

    @Autowired TransactionService service;

    @MockBean MerchantUpserter merchantUpserter;

    @Test
    void shouldRollbackTransactionUpdateWhenMerchantFeedbackFails() {
        // Unique user id for this test class so explicit cleanup at end stays simple.
        UUID userId = UUID.fromString("00000000-0000-0000-0000-00000a70a111");
        seedUser(userId);
        UUID catA = anySystemCategoryId();
        UUID catB = anotherSystemCategoryId(catA);
        UUID accountId = seedAccount(userId, "atomicity");

        OffsetDateTime preMerchantLastConfirmed = OffsetDateTime.now(java.time.ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS).minusDays(3);
        UUID merchantId = seedMerchant(userId, "atomicity-pattern", "ATOMICITY",
            catA, new BigDecimal("0.80"), 7, preMerchantLastConfirmed);
        UUID txId = seedTransaction(userId, accountId, catA, merchantId,
            "ATOMICITY", offsetMinutesAgo(15));

        TransactionsRecord beforeTx = dsl.selectFrom(TRANSACTIONS)
            .where(TRANSACTIONS.ID.eq(txId)).fetchOne();
        MerchantsRecord beforeMerchant = dsl.selectFrom(MERCHANTS)
            .where(MERCHANTS.ID.eq(merchantId)).fetchOne();

        // Force the merchant feedback to blow up AFTER the service has already issued the
        // UPDATE on transactions. @Transactional must roll the whole thing back.
        when(merchantUpserter.applyFeedback(any(), any(), any(), any()))
            .thenThrow(new RuntimeException("simulated merchant failure"));

        assertThatThrownBy(() -> service.updateCategory(userId, txId, catB))
            .isInstanceOf(RuntimeException.class);

        // Transaction row: rolled back. category_id stays catA.
        TransactionsRecord afterTx = dsl.selectFrom(TRANSACTIONS)
            .where(TRANSACTIONS.ID.eq(txId)).fetchOne();
        assertThat(afterTx.getCategoryId()).isEqualTo(catA);
        assertThat(afterTx.getUpdatedAt()).isEqualTo(beforeTx.getUpdatedAt());

        // Merchant row: untouched — the mock never executed the write.
        MerchantsRecord afterMerchant = dsl.selectFrom(MERCHANTS)
            .where(MERCHANTS.ID.eq(merchantId)).fetchOne();
        assertThat(afterMerchant.getCategoryId()).isEqualTo(catA);
        assertThat(afterMerchant.getConfidence()).isEqualByComparingTo(beforeMerchant.getConfidence());
        assertThat(afterMerchant.getMatchCount()).isEqualTo(beforeMerchant.getMatchCount());
        assertThat(afterMerchant.getLastConfirmedAt()).isEqualTo(beforeMerchant.getLastConfirmedAt());

        // Cleanup — class-scoped state because we deliberately skipped class-level @Transactional.
        dsl.deleteFrom(TRANSACTIONS).where(TRANSACTIONS.ID.eq(txId)).execute();
        dsl.deleteFrom(MERCHANTS).where(MERCHANTS.ID.eq(merchantId)).execute();
        dsl.execute("DELETE FROM myfinance.accounts WHERE id = ?", accountId);
        dsl.execute("DELETE FROM auth.users WHERE id = ?", userId);
    }
}
