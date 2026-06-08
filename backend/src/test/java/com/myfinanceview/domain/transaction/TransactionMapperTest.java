package com.myfinanceview.domain.transaction;

import com.myfinanceview.api.dto.TransactionDTO;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link TransactionMapper}. The mapper is intentionally tiny — its real
 * value is enforcing the design D6 do-not-expose boundary by sheer absence of parameters.
 *
 * <p>We pin:
 * <ol>
 *   <li>All wire-visible fields map straight through.</li>
 *   <li>{@link TransactionDTO} record has no internal-only components ({@code userId},
 *       {@code rawPayload}, {@code notes}, {@code externalId}, {@code amountBaseCurrency},
 *       {@code source}) — re-asserted reflectively so a sloppy addition fails CI.</li>
 * </ol>
 */
class TransactionMapperTest {

    private static final OffsetDateTime OCCURRED_AT =
        OffsetDateTime.of(2026, 6, 1, 15, 30, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime CREATED_AT =
        OffsetDateTime.of(2026, 6, 1, 15, 30, 1, 0, ZoneOffset.UTC);
    private static final OffsetDateTime UPDATED_AT =
        OffsetDateTime.of(2026, 6, 1, 15, 30, 2, 0, ZoneOffset.UTC);

    @Test
    void shouldMapAllVisibleFieldsWhenAllPopulated() {
        UUID id = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();

        TransactionDTO dto = TransactionMapper.fromRow(
            id, accountId, categoryId, merchantId,
            "debit_purchase", new BigDecimal("12345.67"), "COP", "EXITO CRA 19",
            OCCURRED_AT, CREATED_AT, UPDATED_AT);

        assertThat(dto.id()).isEqualTo(id);
        assertThat(dto.accountId()).isEqualTo(accountId);
        assertThat(dto.categoryId()).isEqualTo(categoryId);
        assertThat(dto.merchantId()).isEqualTo(merchantId);
        assertThat(dto.type()).isEqualTo("debit_purchase");
        assertThat(dto.amount()).isEqualTo(new BigDecimal("12345.67"));
        assertThat(dto.currency()).isEqualTo("COP");
        assertThat(dto.description()).isEqualTo("EXITO CRA 19");
        assertThat(dto.occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(dto.createdAt()).isEqualTo(CREATED_AT);
        assertThat(dto.updatedAt()).isEqualTo(UPDATED_AT);
    }

    @Test
    void shouldAllowCategoryIdAndMerchantIdNullWhenUncategorised() {
        TransactionDTO dto = TransactionMapper.fromRow(
            UUID.randomUUID(), UUID.randomUUID(), null, null,
            "incoming_transfer", new BigDecimal("100.00"), "COP", null,
            OCCURRED_AT, CREATED_AT, UPDATED_AT);

        assertThat(dto.categoryId()).isNull();
        assertThat(dto.merchantId()).isNull();
        assertThat(dto.description()).isNull();
    }

    @Test
    void shouldPreserveBigDecimalScaleWhenMappingFromRow() {
        // Sanity check that BigDecimal values pass through unmolested — Jackson is responsible for
        // the wire-side scale preservation; this test pins that the mapper does not normalise.
        TransactionDTO dto = TransactionMapper.fromRow(
            UUID.randomUUID(), UUID.randomUUID(), null, null,
            "debit_purchase", new BigDecimal("0.10"), "COP", null,
            OCCURRED_AT, CREATED_AT, UPDATED_AT);

        assertThat(dto.amount().toPlainString()).isEqualTo("0.10");
    }

    @Test
    void shouldNotExposeInternalOnlyComponentsInTransactionDtoRecord() {
        var componentNames = Arrays.stream(TransactionDTO.class.getRecordComponents())
            .map(RecordComponent::getName)
            .toList();

        // design D6 — none of these PII / internal columns should ever land on the wire DTO.
        assertThat(componentNames)
            .doesNotContain("userId")
            .doesNotContain("rawPayload")
            .doesNotContain("notes")
            .doesNotContain("externalId")
            .doesNotContain("amountBaseCurrency")
            .doesNotContain("source");
    }
}
