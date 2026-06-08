package com.myfinanceview.domain.account;

import com.myfinanceview.api.dto.AccountDTO;
import com.myfinanceview.jooq.generated.tables.records.AccountsRecord;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Pure row-to-DTO mapper for accounts. Static-only utility — instantiation is forbidden.
 *
 * <p><b>Schema-rename here</b> (design D6 / adv-review B4): the input parameter {@code nickname}
 * (the DB column) becomes {@code AccountDTO.name}. This is the only "renaming surface" in the
 * codebase; the SQL/jOOQ layer keeps using {@code nickname}.
 *
 * <p><b>jOOQ codegen status (2026-06-07):</b> jOOQ codegen is deferred to tasks.md §5.1, so this
 * mapper currently takes a primitive parameter list rather than {@code fromRecord(AccountsRecord)}.
 * When codegen lands, a {@code fromRecord(AccountsRecord rec)} overload will delegate here:
 * <pre>{@code
 *   return fromRow(
 *     rec.getId(), rec.getNickname(), rec.getLast4(),
 *     rec.getType().getLiteral(), rec.getCurrency(), rec.getActive(),
 *     rec.getCreatedAt(), rec.getUpdatedAt());
 * }</pre>
 * The rename invariant lives in this one place either way.
 */
public final class AccountMapper {

    private AccountMapper() {
        throw new AssertionError("static utility — do not instantiate");
    }

    /**
     * Map a row tuple from {@code myfinance.accounts} to its wire DTO.
     *
     * @param id          {@code accounts.id}.
     * @param nickname    {@code accounts.nickname} — renamed to {@link AccountDTO#name} on the wire.
     * @param last4       {@code accounts.last4} (nullable for non-card accounts).
     * @param type        already stringified enum literal ({@code "checking"}, {@code "savings"},
     *                    {@code "credit_card"}). The repository SHALL call
     *                    {@code rec.getType().getLiteral()} (or equivalent) before invoking this
     *                    mapper so the DTO surface stays driver-agnostic.
     * @param currency    ISO 4217 code.
     * @param active      {@code accounts.active}.
     * @param createdAt   {@code accounts.created_at} (UTC).
     * @param updatedAt   {@code accounts.updated_at} (UTC).
     */
    public static AccountDTO fromRow(
        UUID id,
        String nickname,
        String last4,
        String type,
        String currency,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
    ) {
        return new AccountDTO(id, nickname, last4, type, currency, active, createdAt, updatedAt);
    }

    /**
     * Convenience overload for jOOQ-generated records. Delegates to {@link #fromRow} so the
     * {@code nickname → name} rename invariant lives in exactly one place.
     */
    public static AccountDTO fromRecord(AccountsRecord rec) {
        return fromRow(
            rec.getId(),
            rec.getNickname(),
            rec.getLast4(),
            rec.getType().getLiteral(),
            rec.getCurrency(),
            rec.getActive(),
            rec.getCreatedAt(),
            rec.getUpdatedAt()
        );
    }
}
