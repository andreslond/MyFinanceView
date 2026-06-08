package com.myfinanceview.api.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myfinanceview.config.JacksonConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for the Jackson wiring defined in {@link JacksonConfig}.
 *
 * <p>No Spring context — the test instantiates an {@link ObjectMapper} the same way Spring Boot
 * would (via {@link Jackson2ObjectMapperBuilder}) and applies {@link JacksonConfig}'s customizer
 * manually. This catches misconfiguration of the BigDecimal serializer and the JSR-310 module
 * without paying the SpringBootTest startup cost.
 *
 * <p>Spec rationale:
 * <ul>
 *   <li>backend-mvp-readonly spec — <b>"BigDecimal serialization preserves precision"</b> —
 *       scenarios <i>amount se serializa como string</i> and <i>scale preservado en serialización</i>.</li>
 *   <li>design.md D6 — DTO contract: drop {@code nickname} in {@link AccountDTO} (renamed to
 *       {@code name}) and drop {@code parentId} in {@link CategoryDTO}.</li>
 *   <li>design.md D11 — zero-echo of internal fields (no {@code userId}, {@code rawPayload},
 *       {@code externalId} keys in any DTO JSON).</li>
 * </ul>
 */
class JacksonSerializationTest {

    private static ObjectMapper mapper;

    @BeforeAll
    static void setUp() {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        // Apply the same customizer Spring uses at boot — this is what JacksonConfig contributes.
        new JacksonConfig().jacksonBuilderCustomizer().customize(builder);
        mapper = builder.build();
    }

    // ----- BigDecimal serialization (string, scale-preserving) ---------------------------------

    @Test
    void shouldSerializeTransactionAmountAsQuotedString() throws Exception {
        TransactionDTO tx = sampleTx(new BigDecimal("12345.67"));
        String json = mapper.writeValueAsString(tx);
        // Quoted string, NOT a JSON number.
        assertThat(json).contains("\"amount\":\"12345.67\"");
        assertThat(json).doesNotContain("\"amount\":12345.67");
    }

    @Test
    void shouldPreserveScaleZero10InSerialization() throws Exception {
        // new BigDecimal("0.10") has scale=2. A naive toString() of an unboxed double would print
        // "0.1". toPlainString() preserves the trailing zero.
        TransactionDTO tx = sampleTx(new BigDecimal("0.10"));
        String json = mapper.writeValueAsString(tx);
        assertThat(json).contains("\"amount\":\"0.10\"");
        assertThat(json).doesNotContain("\"amount\":\"0.1\"");
    }

    @Test
    void shouldRoundTripBigDecimalFromQuotedString() throws Exception {
        // Inbound JSON arrives as a quoted string; the default Jackson deserializer for BigDecimal
        // accepts both quoted strings and numeric tokens. We only verify the string-quoted path
        // because that's the contract this codebase commits to publishing.
        UUID id = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        String json = """
            {
              "id": "%s",
              "accountId": "%s",
              "type": "debit_purchase",
              "amount": "12345.67",
              "currency": "COP",
              "occurredAt": "2026-06-01T15:30:00Z",
              "createdAt": "2026-06-01T15:30:00Z",
              "updatedAt": "2026-06-01T15:30:00Z"
            }
            """.formatted(id, accountId);
        TransactionDTO tx = mapper.readValue(json, TransactionDTO.class);
        assertThat(tx.amount()).isEqualTo(new BigDecimal("12345.67"));
    }

    // ----- OffsetDateTime serialization (ISO-8601 UTC, no timestamps) --------------------------

    @Test
    void shouldSerializeOccurredAtAsIsoUtcString() throws Exception {
        OffsetDateTime when = OffsetDateTime.of(2026, 6, 1, 15, 30, 0, 0, ZoneOffset.UTC);
        TransactionDTO tx = new TransactionDTO(
            UUID.randomUUID(), UUID.randomUUID(), null, null,
            "debit_purchase", new BigDecimal("1.00"), "COP", null,
            when, when, when);
        String json = mapper.writeValueAsString(tx);
        // ISO-8601 with the 'Z' (UTC) offset; no epoch-millis timestamp.
        assertThat(json).contains("\"occurredAt\":\"2026-06-01T15:30:00Z\"");
    }

    // ----- Schema rename (B4) — accounts.nickname → AccountDTO.name -----------------------------

    @Test
    void shouldNotIncludeNicknameKeyInAccountJson() throws Exception {
        AccountDTO account = new AccountDTO(
            UUID.randomUUID(), "Davivienda Signature", "1234", "credit_card", "COP", true,
            OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC),
            OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        String json = mapper.writeValueAsString(account);
        // The schema column "nickname" must NEVER leak through the DTO surface.
        assertThat(json).doesNotContain("nickname");
        assertThat(json).contains("\"name\":\"Davivienda Signature\"");
    }

    // ----- Schema field drop (B3) — categories has no parent_id; DTO must not invent one -------

    @Test
    void shouldNotIncludeParentIdKeyInCategoryJson() throws Exception {
        CategoryDTO category = new CategoryDTO(
            UUID.randomUUID(), "Restaurantes y Cafés", "expense", "#FF6B6B", "utensils");
        String json = mapper.writeValueAsString(category);
        // parentId was MVP frontend speculation that has no schema backing; adv-review B3.
        assertThat(json).doesNotContain("parentId");
        assertThat(json).contains("\"name\":\"Restaurantes y Cafés\"");
    }

    // ----- Zero-echo of internal fields across all DTOs ----------------------------------------

    @Test
    void shouldNotIncludeUserIdInAnyDtoJson() throws Exception {
        // None of these record types declare userId — record serialization is structural, so a
        // missing field cannot leak. This test pins the contract: if someone adds userId to any
        // DTO later, this fails fast.
        String accountJson = mapper.writeValueAsString(sampleAccount());
        String categoryJson = mapper.writeValueAsString(sampleCategory());
        String txJson = mapper.writeValueAsString(sampleTx(new BigDecimal("1.00")));
        assertThat(accountJson).doesNotContain("userId");
        assertThat(categoryJson).doesNotContain("userId");
        assertThat(txJson).doesNotContain("userId");
    }

    @Test
    void shouldNotIncludeRawPayloadOrExternalIdInTransactionJson() throws Exception {
        // Adv-review D6 forbids exposing raw_payload, external_id, notes, source,
        // amount_base_currency — none should appear in the wire JSON.
        String json = mapper.writeValueAsString(sampleTx(new BigDecimal("1.00")));
        assertThat(json).doesNotContain("rawPayload");
        assertThat(json).doesNotContain("externalId");
        assertThat(json).doesNotContain("notes");
        assertThat(json).doesNotContain("source");
        assertThat(json).doesNotContain("amountBaseCurrency");
    }

    // ----- PageDTO<T> structural shape ----------------------------------------------------------

    @Test
    void shouldSerializePageDtoWithExpectedKeys() throws Exception {
        PageDTO<TransactionDTO> page = new PageDTO<>(
            List.of(sampleTx(new BigDecimal("10.00"))),
            1, 25, true);
        String json = mapper.writeValueAsString(page);
        assertThat(json).contains("\"rows\":");
        assertThat(json).contains("\"page\":1");
        assertThat(json).contains("\"pageSize\":25");
        assertThat(json).contains("\"hasMore\":true");
    }

    // ----- Fixtures -----------------------------------------------------------------------------

    private static TransactionDTO sampleTx(BigDecimal amount) {
        OffsetDateTime when = OffsetDateTime.of(2026, 6, 1, 15, 30, 0, 0, ZoneOffset.UTC);
        return new TransactionDTO(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "debit_purchase", amount, "COP", "TEST DESCRIPTION",
            when, when, when);
    }

    private static AccountDTO sampleAccount() {
        OffsetDateTime when = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        return new AccountDTO(
            UUID.randomUUID(), "Davivienda Signature", "1234", "credit_card", "COP", true,
            when, when);
    }

    private static CategoryDTO sampleCategory() {
        return new CategoryDTO(
            UUID.randomUUID(), "Restaurantes y Cafés", "expense", "#FF6B6B", "utensils");
    }
}
