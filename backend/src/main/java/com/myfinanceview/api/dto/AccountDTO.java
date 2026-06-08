package com.myfinanceview.api.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Wire representation of a user-owned bank account or credit card.
 *
 * <p><b>Schema-rename in mapper</b> (design D6 / adv-review B4): the DB column
 * {@code accounts.nickname} surfaces here as {@code name}. The rename happens in
 * {@link com.myfinanceview.domain.account.AccountMapper} so that the SQL/jOOQ surface stays
 * stable (no V006 to rename the column) while the JSON contract stays aligned with the MVP
 * frontend's {@code Account.name} field.
 *
 * <p>{@code last4} is nullable (savings / checking accounts have no card number).
 * {@code userId} and {@code bankId} are NOT exposed (internals).
 */
public record AccountDTO(
    UUID id,
    String name,
    String last4,
    String type,
    String currency,
    boolean active,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {}
