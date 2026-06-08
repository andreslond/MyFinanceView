package com.myfinanceview.domain.transaction;

import com.myfinanceview.jooq.generated.tables.records.MerchantsRecord;
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
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Re-confirmation contract (tasks.md §6.7, spec.md scenario "Cambio de categoría con merchant
 * existente confirma la categoría aprendida"): when the user assigns a category that matches the
 * merchant's CURRENT category, the merchant gets a confidence bump (+0.10 capped at 1.00) and
 * match_count += 1.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles({"test", "service"})
@Transactional
class TransactionServiceReconfirmTest extends TransactionServiceTestBase {

    @Autowired TransactionService service;

    @Test
    void shouldBumpConfidenceAndMatchCountWhenReconfirmingSameCategory() {
        seedUser(USER_A);
        UUID catX = anySystemCategoryId();
        UUID catY = anotherSystemCategoryId(catX);
        UUID accountA = seedAccount(USER_A, "checking");
        UUID merchantId = seedMerchant(USER_A, "rappi", "RAPPI 42",
            catX, new BigDecimal("0.60"), 3,
            OffsetDateTime.now(java.time.ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS).minusDays(1));
        // Transaction starts with catY (some prior categorisation). The user changes it to catX
        // (which is also the merchant's category → re-confirmation path).
        UUID txId = seedTransaction(USER_A, accountA, catY, merchantId,
            "RAPPI 42", offsetMinutesAgo(15));

        service.updateCategory(USER_A, txId, catX);

        MerchantsRecord merchant = dsl.selectFrom(MERCHANTS)
            .where(MERCHANTS.ID.eq(merchantId)).fetchOne();
        assertThat(merchant.getCategoryId()).isEqualTo(catX);
        assertThat(merchant.getConfidence()).isEqualByComparingTo("0.70");
        assertThat(merchant.getMatchCount()).isEqualTo(4);
        assertThat(merchant.getLastConfirmedAt()).isAfter(OffsetDateTime.now().minusMinutes(1));
    }

    @Test
    void shouldCapConfidenceAt100WhenAlreadyAt095() {
        seedUser(USER_A);
        UUID catX = anySystemCategoryId();
        UUID catY = anotherSystemCategoryId(catX);
        UUID accountA = seedAccount(USER_A, "checking");
        UUID merchantId = seedMerchant(USER_A, "amazon.com", "AMAZON.COM",
            catX, new BigDecimal("0.95"), 9,
            OffsetDateTime.now(java.time.ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS).minusDays(1));
        UUID txId = seedTransaction(USER_A, accountA, catY, merchantId,
            "AMAZON.COM", offsetMinutesAgo(15));

        service.updateCategory(USER_A, txId, catX);

        BigDecimal confidence = dsl.select(MERCHANTS.CONFIDENCE)
            .from(MERCHANTS)
            .where(MERCHANTS.ID.eq(merchantId))
            .fetchOne(MERCHANTS.CONFIDENCE);
        // LEAST(1.00, 0.95 + 0.10) = 1.00 — NOT 1.05.
        assertThat(confidence).isEqualByComparingTo("1.00");
    }

    @Test
    void shouldStayAt100WhenAlreadyAt100() {
        seedUser(USER_A);
        UUID catX = anySystemCategoryId();
        UUID catY = anotherSystemCategoryId(catX);
        UUID accountA = seedAccount(USER_A, "checking");
        UUID merchantId = seedMerchant(USER_A, "apple.com", "APPLE.COM",
            catX, new BigDecimal("1.00"), 12,
            OffsetDateTime.now(java.time.ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS).minusDays(1));
        UUID txId = seedTransaction(USER_A, accountA, catY, merchantId,
            "APPLE.COM", offsetMinutesAgo(15));

        service.updateCategory(USER_A, txId, catX);

        BigDecimal confidence = dsl.select(MERCHANTS.CONFIDENCE)
            .from(MERCHANTS)
            .where(MERCHANTS.ID.eq(merchantId))
            .fetchOne(MERCHANTS.CONFIDENCE);
        assertThat(confidence).isEqualByComparingTo("1.00");
    }
}
