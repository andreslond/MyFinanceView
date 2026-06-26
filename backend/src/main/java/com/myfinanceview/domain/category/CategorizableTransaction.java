package com.myfinanceview.domain.category;

import java.math.BigDecimal;

/**
 * Minimum normalised view of a transaction for deterministic categorisation.
 *
 * <p>The caller (service layer) is responsible for:
 * <ul>
 *   <li>Converting {@code occurred_at} (OffsetDateTime UTC) to a local date in
 *       {@code America/Bogota} before passing to domain logic.</li>
 *   <li>Mapping the jOOQ-generated {@code transaction_type} enum to
 *       {@link TransactionKind}.</li>
 *   <li>Ensuring {@code descriptor} is not null/blank and {@code amount} is
 *       not null and >= 0.</li>
 * </ul>
 *
 * @param descriptor raw bank descriptor text; not null, not blank
 * @param amount     transaction amount, scale 2; not null; must be >= 0
 * @param type       transaction kind; not null
 * @param currency   ISO 4217 currency code (e.g. "COP"); not null
 */
public record CategorizableTransaction(
        String descriptor,
        BigDecimal amount,
        TransactionKind type,
        String currency
) {}
