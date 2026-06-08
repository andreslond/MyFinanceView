package com.myfinanceview.api.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Wire representation of a transaction served by {@code GET /api/v1/transactions} and
 * {@code PATCH /api/v1/transactions/{id}/category}.
 *
 * <p>Shape pinned by {@code docs/api-spec.yml} and by design.md D6. Nullable fields:
 * <ul>
 *   <li>{@code categoryId} — null when the transaction has not been categorised yet.</li>
 *   <li>{@code merchantId} — populated post-V005 once the feedback loop resolves a merchant
 *       (initially null for the 362 historical rows).</li>
 *   <li>{@code description} — bank-provided free text, may be missing in some payloads.</li>
 * </ul>
 *
 * <p><b>Intentionally NOT exposed</b> (PII / internal): {@code user_id}, {@code raw_payload},
 * {@code notes}, {@code external_id}, {@code amount_base_currency}, {@code source}.
 * See design.md D6 risks paragraph and spec.md Requirement <i>BigDecimal serialization preserves
 * precision</i>: {@code amount} serialises as a <b>string</b> via the custom serializer in
 * {@link com.myfinanceview.config.JacksonConfig} (toPlainString(), scale preserved).
 *
 * <p>{@code type} is the lowercase string form of the {@code myfinance.transaction_type} enum
 * (e.g. {@code "debit_purchase"}, {@code "credit_card_payment"}) — the mapper stringifies it so
 * the wire shape stays driver-agnostic.
 */
public record TransactionDTO(
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
) {}
