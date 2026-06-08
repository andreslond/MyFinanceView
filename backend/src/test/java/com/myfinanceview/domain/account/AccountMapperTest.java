package com.myfinanceview.domain.account;

import com.myfinanceview.api.dto.AccountDTO;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link AccountMapper}. No Spring, no Testcontainers — the mapper logic is
 * a pure function and that's exactly how we exercise it.
 *
 * <p>The pin here is the {@code nickname → name} rename mandated by design D6 / adv-review B4.
 * If anyone introduces an {@code accounts.name} column later, this test will still hold (it pins
 * the wire shape regardless of the underlying column name).
 *
 * <p><b>Why no {@code AccountsRecord}?</b> jOOQ codegen is deferred to tasks.md §5.1; the mapper
 * currently consumes primitive parameters and the §5 work will add a {@code fromRecord} overload
 * that delegates here. Pinning the rename invariant at the {@code fromRow} level still gets us
 * full safety because the {@code fromRecord} overload would be a one-liner pass-through.
 */
class AccountMapperTest {

    private static final OffsetDateTime CREATED_AT =
        OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime UPDATED_AT =
        OffsetDateTime.of(2026, 2, 15, 12, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void shouldRenameNicknameColumnToNameOnDtoWhenMapping() {
        UUID id = UUID.randomUUID();
        AccountDTO dto = AccountMapper.fromRow(
            id, "Davivienda Signature", "1234", "credit_card", "COP", true,
            CREATED_AT, UPDATED_AT);

        // The literal point of this mapper — the rename is the only meaningful transformation.
        assertThat(dto.name()).isEqualTo("Davivienda Signature");
        assertThat(dto.id()).isEqualTo(id);
    }

    @Test
    void shouldMapAllFieldsPreservingValuesWhenAllFieldsPopulated() {
        UUID id = UUID.randomUUID();
        AccountDTO dto = AccountMapper.fromRow(
            id, "Bancolombia Ahorros", "9876", "savings", "COP", true,
            CREATED_AT, UPDATED_AT);

        assertThat(dto.id()).isEqualTo(id);
        assertThat(dto.name()).isEqualTo("Bancolombia Ahorros");
        assertThat(dto.last4()).isEqualTo("9876");
        assertThat(dto.type()).isEqualTo("savings");
        assertThat(dto.currency()).isEqualTo("COP");
        assertThat(dto.active()).isTrue();
        assertThat(dto.createdAt()).isEqualTo(CREATED_AT);
        assertThat(dto.updatedAt()).isEqualTo(UPDATED_AT);
    }

    @Test
    void shouldKeepLast4NullWhenAccountHasNoCardNumber() {
        // Checking / savings accounts in the seed have null last4 — verify the mapper does not
        // synthesize a placeholder.
        AccountDTO dto = AccountMapper.fromRow(
            UUID.randomUUID(), "Cuenta Corriente", null, "checking", "COP", true,
            CREATED_AT, UPDATED_AT);

        assertThat(dto.last4()).isNull();
        assertThat(dto.type()).isEqualTo("checking");
    }

    @Test
    void shouldExposeCreditCardTypeAsLowercaseLiteralWhenMapping() {
        // Enum stringification convention: lowercase, snake_case matching the SQL enum literal.
        AccountDTO dto = AccountMapper.fromRow(
            UUID.randomUUID(), "Davivienda Signature", "1234", "credit_card", "COP", true,
            CREATED_AT, UPDATED_AT);

        assertThat(dto.type()).isEqualTo("credit_card");
    }

    @Test
    void shouldKeepActiveFalseWhenAccountDeactivated() {
        AccountDTO dto = AccountMapper.fromRow(
            UUID.randomUUID(), "Old Card", "0001", "credit_card", "USD", false,
            CREATED_AT, UPDATED_AT);

        assertThat(dto.active()).isFalse();
    }
}
