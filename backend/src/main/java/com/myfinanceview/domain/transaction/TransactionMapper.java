package com.myfinanceview.domain.transaction;

import com.myfinanceview.api.dto.TransactionDTO;
import com.myfinanceview.jooq.generated.tables.records.TransactionsRecord;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Pure row-to-DTO mapper for transactions. Static-only utility — instantiation is forbidden.
 *
 * <p>This mapper enforces the design D6 "do-not-expose" boundary by sheer absence of parameters:
 * {@code user_id}, {@code raw_payload}, {@code notes}, {@code external_id},
 * {@code amount_base_currency}, and {@code source} have no corresponding inputs, so the wire DTO
 * cannot accidentally leak them. Adding any of those to {@link TransactionDTO} would require an
 * explicit threat-model review in a separate change.
 *
 * <p>{@code amount} stays a {@link BigDecimal} all the way through to {@link TransactionDTO} —
 * the Jackson configuration in {@link com.myfinanceview.config.JacksonConfig} handles the
 * outbound string-with-preserved-scale serialisation; this mapper does not perform any
 * scale or rounding adjustment (the DB column is {@code NUMERIC(18,2)} so values arrive at
 * scale 2 already).
 *
 * <p><b>jOOQ codegen status (2026-06-07):</b> taken as primitive parameters pending §5.1; will
 * gain a {@code fromRecord(TransactionsRecord rec)} overload once codegen runs against V001..V005.
 */
public final class TransactionMapper {

    private TransactionMapper() {
        throw new AssertionError("static utility — do not instantiate");
    }

    /**
     * Map a row tuple from {@code myfinance.transactions} to its wire DTO.
     *
     * @param id           {@code transactions.id}.
     * @param accountId    {@code transactions.account_id} (not null).
     * @param categoryId   {@code transactions.category_id} (nullable when uncategorised).
     * @param merchantId   {@code transactions.merchant_id} (nullable until feedback loop resolves).
     * @param type         stringified {@code myfinance.transaction_type} enum literal.
     * @param amount       {@code transactions.amount} — positive {@code NUMERIC(18,2)}.
     * @param currency     ISO 4217 code.
     * @param description  {@code transactions.description} (nullable).
     * @param occurredAt   when the transaction occurred (UTC).
     * @param createdAt    row create timestamp (UTC).
     * @param updatedAt    row update timestamp (UTC).
     */
    public static TransactionDTO fromRow(
        UUID id,
        UUID accountId,
        UUID categoryId,
        UUID merchantId,
        String type,
        BigDecimal amount,
        String currency,
        String description,
        OffsetDateTime occurredAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
    ) {
        return new TransactionDTO(
            id, accountId, categoryId, merchantId,
            type, amount, currency, description,
            occurredAt, createdAt, updatedAt);
    }

    /**
     * Convenience overload for jOOQ-generated records. Delegates to {@link #fromRow} — the
     * no-exposure invariant (no {@code user_id}, no {@code raw_payload}, ...) is the absence of
     * those parameters in {@link #fromRow}; this overload cannot weaken it.
     */
    public static TransactionDTO fromRecord(TransactionsRecord rec) {
        return fromRow(
            rec.getId(),
            rec.getAccountId(),
            rec.getCategoryId(),
            rec.getMerchantId(),
            rec.getType().getLiteral(),
            rec.getAmount(),
            rec.getCurrency(),
            rec.getDescription(),
            rec.getOccurredAt(),
            rec.getCreatedAt(),
            rec.getUpdatedAt()
        );
    }
}
