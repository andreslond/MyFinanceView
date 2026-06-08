package com.myfinanceview.domain.transaction;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.myfinanceview.domain.merchant.MerchantUpserter;
import com.myfinanceview.jooq.generated.tables.records.MerchantsRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static com.myfinanceview.jooq.generated.Tables.MERCHANTS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drift contract (tasks.md §6.8, spec.md scenario "Drift — el user cambia la categoría a una
 * distinta"): when the user assigns a category DIFFERENT from the merchant's current category,
 * the merchant resets — confidence=0.50, match_count=1, category_id=new — and an INFO log line
 * with {@code event=merchant_drift_reset} is emitted (ids only, no amount/description).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles({"test", "service"})
@Transactional
class TransactionServiceDriftTest extends TransactionServiceTestBase {

    @Autowired TransactionService service;

    private ListAppender<ILoggingEvent> appender;
    private Logger upserterLogger;

    @BeforeEach
    void attachAppender() {
        upserterLogger = (Logger) LoggerFactory.getLogger(MerchantUpserter.class);
        appender = new ListAppender<>();
        appender.start();
        upserterLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        upserterLogger.detachAppender(appender);
    }

    @Test
    void shouldResetMerchantAndLogDriftWhenCategoryChanges() {
        seedUser(USER_A);
        UUID catX = anySystemCategoryId();
        UUID catY = anotherSystemCategoryId(catX);
        UUID accountA = seedAccount(USER_A, "checking");

        BigDecimal preConfidence = new BigDecimal("0.90");
        int preMatchCount = 5;
        OffsetDateTime preLastConfirmed = OffsetDateTime.now(java.time.ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS).minusDays(3);
        UUID merchantId = seedMerchant(USER_A, "juan valdez", "JUAN VALDEZ *9876",
            catX, preConfidence, preMatchCount, preLastConfirmed);

        // The transaction's PRIOR category is anything; what matters is the merchant.category_id
        // (catX) vs the new categoryId (catY).
        String txDescription = "JUAN VALDEZ *9876";
        BigDecimal txAmount = new BigDecimal("12345.67");
        UUID txId = seedTransaction(USER_A, accountA, catX, merchantId,
            txDescription, offsetMinutesAgo(15));
        // Bump amount so we can search for it as a substring in logs and ensure no echo.
        dsl.update(com.myfinanceview.jooq.generated.Tables.TRANSACTIONS)
            .set(com.myfinanceview.jooq.generated.Tables.TRANSACTIONS.AMOUNT, txAmount)
            .where(com.myfinanceview.jooq.generated.Tables.TRANSACTIONS.ID.eq(txId))
            .execute();

        service.updateCategory(USER_A, txId, catY);

        MerchantsRecord merchant = dsl.selectFrom(MERCHANTS)
            .where(MERCHANTS.ID.eq(merchantId)).fetchOne();
        assertThat(merchant.getCategoryId()).isEqualTo(catY);
        assertThat(merchant.getConfidence()).isEqualByComparingTo("0.50");
        assertThat(merchant.getMatchCount()).isEqualTo(1);
        assertThat(merchant.getLastConfirmedAt()).isAfter(preLastConfirmed);
        // Display name preserved (identity persists, classification resets — D5).
        assertThat(merchant.getDisplayName()).isEqualTo("JUAN VALDEZ *9876");

        // Log assertions: a single INFO line with event=merchant_drift_reset, containing all ids
        // (merchant, old/new category, user), but NOT amount or description.
        List<ILoggingEvent> infoEvents = appender.list.stream()
            .filter(e -> e.getLevel() == Level.INFO)
            .toList();
        assertThat(infoEvents).hasSize(1);
        String message = infoEvents.get(0).getFormattedMessage();
        assertThat(message).contains("event=merchant_drift_reset");
        assertThat(message).contains(merchantId.toString());
        assertThat(message).contains("old_category_id=" + catX);
        assertThat(message).contains("new_category_id=" + catY);
        assertThat(message).contains("user_id=" + USER_A);

        // Zero-echo on the log line itself.
        assertThat(message).doesNotContain("12345.67");
        assertThat(message).doesNotContain("JUAN VALDEZ");
        assertThat(message).doesNotContain("9876");
    }
}
