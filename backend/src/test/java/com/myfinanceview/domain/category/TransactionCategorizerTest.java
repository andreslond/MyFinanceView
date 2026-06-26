package com.myfinanceview.domain.category;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TDD suite for {@link TransactionCategorizer#categorize}.
 * Each test maps to exactly one Gherkin scenario in
 * features/transaction_categorization_rules.feature (@s1–@s21).
 */
class TransactionCategorizerTest {

    // ── Shared category refs ───────────────────────────────────────────────────

    private static final UUID ID_DINING_1 = UUID.fromString("a1b2c3d4-0001-0001-0001-000000000001");
    private static final UUID ID_DINING_2 = UUID.fromString("a1b2c3d4-0002-0002-0002-000000000002");
    private static final UUID ID_TRANSPORT = UUID.fromString("a1b2c3d4-0003-0003-0003-000000000003");
    private static final UUID ID_DINING_EATS = UUID.fromString("a1b2c3d4-0004-0004-0004-000000000004");
    private static final UUID ID_GROCERIES = UUID.fromString("a1b2c3d4-0005-0005-0005-000000000005");
    private static final UUID ID_HEALTH = UUID.fromString("a1b2c3d4-0006-0006-0006-000000000006");
    private static final UUID ID_TRANSPORT_2 = UUID.fromString("a1b2c3d4-0007-0007-0007-000000000007");
    private static final UUID ID_ENTERTAINMENT = UUID.fromString("a1b2c3d4-0008-0008-0008-000000000008");
    private static final UUID ID_GROCERIES_2 = UUID.fromString("a1b2c3d4-0009-0009-0009-000000000009");
    private static final UUID ID_MISC = UUID.fromString("a1b2c3d4-0010-0010-0010-000000000010");
    private static final UUID ID_SHOPPING = UUID.fromString("a1b2c3d4-0011-0011-0011-000000000011");
    private static final UUID ID_SHOPPING_2 = UUID.fromString("a1b2c3d4-0012-0012-0012-000000000012");
    private static final UUID ID_DINING_3 = UUID.fromString("a1b2c3d4-0013-0013-0013-000000000013");
    private static final UUID ID_REFUNDS = UUID.fromString("a1b2c3d4-0014-0014-0014-000000000014");
    private static final UUID ID_TRANSPORT_3 = UUID.fromString("a1b2c3d4-0015-0015-0015-000000000015");

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static CategoryRule merchantRule(UUID ruleId, String pattern, UUID catId, String catName) {
        return new CategoryRule(ruleId, pattern, null, null, Collections.emptySet(),
                new CategoryRef(catId, catName));
    }

    private static CategorizableTransaction tx(String descriptor, String amount,
            TransactionKind kind) {
        return new CategorizableTransaction(descriptor, new BigDecimal(amount), kind, "COP");
    }

    // ── @s1 — Match por patrón de comercio ────────────────────────────────────

    @Test
    @DisplayName("@s1 — shouldReturnMatchedWhenSingleMerchantRuleMatches")
    void shouldReturnMatchedWhenSingleMerchantRuleMatches() {
        CategorizableTransaction transaction = tx("RAPPI COLOMBIA S.A.S", "25000.00",
                TransactionKind.CREDIT_CARD_PURCHASE);
        CategoryRule rule = merchantRule(UUID.randomUUID(), "RAPPI", ID_DINING_1, "Dining Out");

        CategoryMatch result = TransactionCategorizer.categorize(transaction, List.of(rule));

        assertThat(result).isInstanceOf(Matched.class);
        Matched matched = (Matched) result;
        assertThat(matched.category().id()).isEqualTo(ID_DINING_1);
        assertThat(matched.category().name()).isEqualTo("Dining Out");
    }

    // ── @s2 — Match case-insensitive ──────────────────────────────────────────

    @Test
    @DisplayName("@s2 — shouldReturnMatchedWhenPatternIsLowercaseAndDescriptorIsUppercase")
    void shouldReturnMatchedWhenPatternIsLowercaseAndDescriptorIsUppercase() {
        CategorizableTransaction transaction = tx("RAPPI COLOMBIA S.A.S", "25000.00",
                TransactionKind.CREDIT_CARD_PURCHASE);
        CategoryRule rule = merchantRule(UUID.randomUUID(), "rappi", ID_DINING_1, "Dining Out");

        CategoryMatch result = TransactionCategorizer.categorize(transaction, List.of(rule));

        assertThat(result).isInstanceOf(Matched.class);
        assertThat(((Matched) result).category().id()).isEqualTo(ID_DINING_1);
    }

    // ── @s3 — NoMatch: descriptor no coincide ─────────────────────────────────

    @Test
    @DisplayName("@s3 — shouldReturnNoMatchWhenDescriptorDoesNotContainPattern")
    void shouldReturnNoMatchWhenDescriptorDoesNotContainPattern() {
        CategorizableTransaction transaction = tx("TRANSACCION DESCONOCIDA", "10000.00",
                TransactionKind.DEBIT_PURCHASE);
        CategoryRule rule = merchantRule(UUID.randomUUID(), "RAPPI", ID_DINING_1, "Dining Out");

        CategoryMatch result = TransactionCategorizer.categorize(transaction, List.of(rule));

        assertThat(result).isInstanceOf(NoMatch.class);
    }

    // ── @s4 — NoMatch: lista vacía ────────────────────────────────────────────

    @Test
    @DisplayName("@s4 — shouldReturnNoMatchWhenRuleListIsEmpty")
    void shouldReturnNoMatchWhenRuleListIsEmpty() {
        CategorizableTransaction transaction = tx("BOLD*RESTAURANTE LA PLAZA", "50000.00",
                TransactionKind.CREDIT_CARD_PURCHASE);

        CategoryMatch result = TransactionCategorizer.categorize(transaction, List.of());

        assertThat(result).isInstanceOf(NoMatch.class);
    }

    // ── @s5 — Varias reglas, misma categoría → Matched ────────────────────────

    @Test
    @DisplayName("@s5 — shouldReturnMatchedWhenMultipleRulesMatchSameCategory")
    void shouldReturnMatchedWhenMultipleRulesMatchSameCategory() {
        CategorizableTransaction transaction = tx("DIDI FOOD BOGOTA", "30000.00",
                TransactionKind.CREDIT_CARD_PURCHASE);
        CategoryRule rule1 = merchantRule(UUID.randomUUID(), "DIDI", ID_DINING_2, "Dining Out");
        CategoryRule rule2 = merchantRule(UUID.randomUUID(), "FOOD", ID_DINING_2, "Dining Out");

        CategoryMatch result = TransactionCategorizer.categorize(transaction, List.of(rule1, rule2));

        assertThat(result).isInstanceOf(Matched.class);
        assertThat(((Matched) result).category().id()).isEqualTo(ID_DINING_2);
    }

    // ── @s6 — Varias reglas, categorías distintas → NoMatch (ambigüedad) ──────

    @Test
    @DisplayName("@s6 — shouldReturnNoMatchWhenMultipleRulesMatchDifferentCategories")
    void shouldReturnNoMatchWhenMultipleRulesMatchDifferentCategories() {
        CategorizableTransaction transaction = tx("UBER EATS COLOMBIA", "45000.00",
                TransactionKind.CREDIT_CARD_PURCHASE);
        CategoryRule rule1 = merchantRule(UUID.randomUUID(), "UBER", ID_TRANSPORT, "Transport");
        CategoryRule rule2 = merchantRule(UUID.randomUUID(), "EATS", ID_DINING_EATS, "Dining Out");

        CategoryMatch result = TransactionCategorizer.categorize(transaction, List.of(rule1, rule2));

        assertThat(result).isInstanceOf(NoMatch.class);
    }

    // ── @s7 — Predicado de rango: monto dentro del rango ─────────────────────

    @Test
    @DisplayName("@s7 — shouldReturnMatchedWhenAmountIsWithinRange")
    void shouldReturnMatchedWhenAmountIsWithinRange() {
        CategorizableTransaction transaction = tx("SUPERMERCADO EXITO", "80000.00",
                TransactionKind.DEBIT_PURCHASE);
        CategoryRule rule = new CategoryRule(UUID.randomUUID(), null,
                new BigDecimal("50000.00"), new BigDecimal("100000.00"),
                Collections.emptySet(), new CategoryRef(ID_GROCERIES, "Groceries"));

        CategoryMatch result = TransactionCategorizer.categorize(transaction, List.of(rule));

        assertThat(result).isInstanceOf(Matched.class);
        assertThat(((Matched) result).category().id()).isEqualTo(ID_GROCERIES);
    }

    // ── @s8 — Predicado de rango: monto fuera del rango ──────────────────────

    @Test
    @DisplayName("@s8 — shouldReturnNoMatchWhenAmountExceedsMaxAmount")
    void shouldReturnNoMatchWhenAmountExceedsMaxAmount() {
        CategorizableTransaction transaction = tx("SUPERMERCADO EXITO", "150000.00",
                TransactionKind.DEBIT_PURCHASE);
        CategoryRule rule = new CategoryRule(UUID.randomUUID(), null,
                new BigDecimal("50000.00"), new BigDecimal("100000.00"),
                Collections.emptySet(), new CategoryRef(ID_GROCERIES, "Groceries"));

        CategoryMatch result = TransactionCategorizer.categorize(transaction, List.of(rule));

        assertThat(result).isInstanceOf(NoMatch.class);
    }

    // ── @s9 — Solo minAmount: monto exactamente en el límite inferior ─────────

    @Test
    @DisplayName("@s9 — shouldReturnMatchedWhenAmountEqualsMinAmountBoundary")
    void shouldReturnMatchedWhenAmountEqualsMinAmountBoundary() {
        CategorizableTransaction transaction = tx("FARMACIA DROGUERIA", "20000.00",
                TransactionKind.DEBIT_PURCHASE);
        CategoryRule rule = new CategoryRule(UUID.randomUUID(), null,
                new BigDecimal("20000.00"), null,
                Collections.emptySet(), new CategoryRef(ID_HEALTH, "Health"));

        CategoryMatch result = TransactionCategorizer.categorize(transaction, List.of(rule));

        assertThat(result).isInstanceOf(Matched.class);
        assertThat(((Matched) result).category().id()).isEqualTo(ID_HEALTH);
    }

    // ── @s10 — Restricción puntual: minAmount == maxAmount ────────────────────

    @Test
    @DisplayName("@s10 — shouldReturnMatchedWhenAmountEqualsPointConstraint")
    void shouldReturnMatchedWhenAmountEqualsPointConstraint() {
        CategorizableTransaction transaction = tx("PEAJE NORTE", "5800.00",
                TransactionKind.DEBIT_PURCHASE);
        CategoryRule rule = new CategoryRule(UUID.randomUUID(), null,
                new BigDecimal("5800.00"), new BigDecimal("5800.00"),
                Collections.emptySet(), new CategoryRef(ID_TRANSPORT_2, "Transport"));

        CategoryMatch result = TransactionCategorizer.categorize(transaction, List.of(rule));

        assertThat(result).isInstanceOf(Matched.class);
        assertThat(((Matched) result).category().id()).isEqualTo(ID_TRANSPORT_2);
    }

    // ── @s11 — Predicado de tipo: coincide con el tipo correcto ───────────────

    @Test
    @DisplayName("@s11 — shouldReturnMatchedWhenTransactionKindMatchesRule")
    void shouldReturnMatchedWhenTransactionKindMatchesRule() {
        CategorizableTransaction transaction = tx("NETFLIX", "17900.00",
                TransactionKind.CREDIT_CARD_PURCHASE);
        CategoryRule rule = new CategoryRule(UUID.randomUUID(), "NETFLIX", null, null,
                Set.of(TransactionKind.CREDIT_CARD_PURCHASE),
                new CategoryRef(ID_ENTERTAINMENT, "Entertainment"));

        CategoryMatch result = TransactionCategorizer.categorize(transaction, List.of(rule));

        assertThat(result).isInstanceOf(Matched.class);
        assertThat(((Matched) result).category().id()).isEqualTo(ID_ENTERTAINMENT);
    }

    // ── @s12 — Predicado de tipo: tipo distinto → NoMatch ────────────────────

    @Test
    @DisplayName("@s12 — shouldReturnNoMatchWhenTransactionKindDiffersFromRule")
    void shouldReturnNoMatchWhenTransactionKindDiffersFromRule() {
        CategorizableTransaction transaction = tx("NETFLIX", "17900.00",
                TransactionKind.INCOMING_TRANSFER);
        CategoryRule rule = new CategoryRule(UUID.randomUUID(), "NETFLIX", null, null,
                Set.of(TransactionKind.CREDIT_CARD_PURCHASE),
                new CategoryRef(ID_ENTERTAINMENT, "Entertainment"));

        CategoryMatch result = TransactionCategorizer.categorize(transaction, List.of(rule));

        assertThat(result).isInstanceOf(NoMatch.class);
    }

    // ── @s13 — Predicados combinados (AND): ambos se cumplen → Matched ─────────

    @Test
    @DisplayName("@s13 — shouldReturnMatchedWhenBothMerchantPatternAndMinAmountPredicatesMet")
    void shouldReturnMatchedWhenBothMerchantPatternAndMinAmountPredicatesMet() {
        CategorizableTransaction transaction = tx("RAPPI MARKET", "75000.00",
                TransactionKind.DEBIT_PURCHASE);
        CategoryRule rule = new CategoryRule(UUID.randomUUID(), "RAPPI",
                new BigDecimal("50000.00"), null,
                Collections.emptySet(), new CategoryRef(ID_GROCERIES_2, "Groceries"));

        CategoryMatch result = TransactionCategorizer.categorize(transaction, List.of(rule));

        assertThat(result).isInstanceOf(Matched.class);
        assertThat(((Matched) result).category().id()).isEqualTo(ID_GROCERIES_2);
    }

    // ── @s14 — Predicados combinados (AND): monto debajo del umbral → NoMatch ──

    @Test
    @DisplayName("@s14 — shouldReturnNoMatchWhenMerchantMatchesButAmountBelowMinThreshold")
    void shouldReturnNoMatchWhenMerchantMatchesButAmountBelowMinThreshold() {
        CategorizableTransaction transaction = tx("RAPPI MARKET", "20000.00",
                TransactionKind.DEBIT_PURCHASE);
        CategoryRule rule = new CategoryRule(UUID.randomUUID(), "RAPPI",
                new BigDecimal("50000.00"), null,
                Collections.emptySet(), new CategoryRef(ID_GROCERIES_2, "Groceries"));

        CategoryMatch result = TransactionCategorizer.categorize(transaction, List.of(rule));

        assertThat(result).isInstanceOf(NoMatch.class);
    }

    // ── @s15 — Regla sin predicados → InvalidRuleException ────────────────────

    @Test
    @DisplayName("@s15 — shouldThrowInvalidRuleExceptionWhenRuleHasNoPredicates")
    void shouldThrowInvalidRuleExceptionWhenRuleHasNoPredicates() {
        CategorizableTransaction transaction = tx("CUALQUIER COMERCIO", "10000.00",
                TransactionKind.CREDIT_CARD_PURCHASE);
        UUID ruleId = UUID.fromString("a1b2c3d4-0010-0010-0010-000000000010");
        CategoryRule rule = new CategoryRule(ruleId, null, null, null,
                Collections.emptySet(), new CategoryRef(ID_MISC, "Miscellaneous"));

        assertThatThrownBy(() -> TransactionCategorizer.categorize(transaction, List.of(rule)))
                .isInstanceOf(InvalidRuleException.class)
                .hasMessageContaining("has no predicates");
    }

    // ── @s16 — merchantPattern en blanco (espacios) → InvalidRuleException ────

    @Test
    @DisplayName("@s16 — shouldThrowInvalidRuleExceptionWhenMerchantPatternIsBlank")
    void shouldThrowInvalidRuleExceptionWhenMerchantPatternIsBlank() {
        CategorizableTransaction transaction = tx("TIENDA ONLINE", "30000.00",
                TransactionKind.CREDIT_CARD_PURCHASE);
        CategoryRule rule = new CategoryRule(UUID.randomUUID(), "   ", null, null,
                Collections.emptySet(), new CategoryRef(ID_SHOPPING, "Shopping"));

        assertThatThrownBy(() -> TransactionCategorizer.categorize(transaction, List.of(rule)))
                .isInstanceOf(InvalidRuleException.class)
                .hasMessageContaining("merchantPattern must not be blank");
    }

    // ── @s17 — merchantPattern vacío ("") → InvalidRuleException ──────────────

    @Test
    @DisplayName("@s17 — shouldThrowInvalidRuleExceptionWhenMerchantPatternIsEmpty")
    void shouldThrowInvalidRuleExceptionWhenMerchantPatternIsEmpty() {
        CategorizableTransaction transaction = tx("TIENDA ONLINE", "30000.00",
                TransactionKind.CREDIT_CARD_PURCHASE);
        CategoryRule rule = new CategoryRule(UUID.randomUUID(), "", null, null,
                Collections.emptySet(), new CategoryRef(ID_SHOPPING, "Shopping"));

        assertThatThrownBy(() -> TransactionCategorizer.categorize(transaction, List.of(rule)))
                .isInstanceOf(InvalidRuleException.class)
                .hasMessageContaining("merchantPattern must not be blank");
    }

    // ── @s18 — minAmount > maxAmount → InvalidRuleException ───────────────────

    @Test
    @DisplayName("@s18 — shouldThrowInvalidRuleExceptionWhenMinAmountExceedsMaxAmount")
    void shouldThrowInvalidRuleExceptionWhenMinAmountExceedsMaxAmount() {
        CategorizableTransaction transaction = tx("COMPRA VARIA", "50000.00",
                TransactionKind.DEBIT_PURCHASE);
        CategoryRule rule = new CategoryRule(UUID.randomUUID(), null,
                new BigDecimal("100000.00"), new BigDecimal("50000.00"),
                Collections.emptySet(), new CategoryRef(ID_SHOPPING_2, "Shopping"));

        assertThatThrownBy(() -> TransactionCategorizer.categorize(transaction, List.of(rule)))
                .isInstanceOf(InvalidRuleException.class)
                .hasMessageContaining("minAmount must be <= maxAmount");
    }

    // ── @s19 — Idempotencia: mismo input produce mismo output ─────────────────

    @Test
    @DisplayName("@s19 — shouldReturnIdenticalResultsWhenCategorizingTwiceWithSameInput")
    void shouldReturnIdenticalResultsWhenCategorizingTwiceWithSameInput() {
        CategorizableTransaction transaction = tx("BOLD*CAFE EL RINCON", "12000.00",
                TransactionKind.CREDIT_CARD_PURCHASE);
        CategoryRule rule = merchantRule(UUID.randomUUID(), "BOLD", ID_DINING_3, "Dining Out");
        List<CategoryRule> rules = List.of(rule);

        CategoryMatch result1 = TransactionCategorizer.categorize(transaction, rules);
        CategoryMatch result2 = TransactionCategorizer.categorize(transaction, rules);

        assertThat(result1).isInstanceOf(Matched.class);
        assertThat(result2).isInstanceOf(Matched.class);
        assertThat(((Matched) result1).category().id()).isEqualTo(ID_DINING_3);
        assertThat(((Matched) result2).category().id()).isEqualTo(((Matched) result1).category().id());
        assertThat(((Matched) result2).category().name()).isEqualTo(((Matched) result1).category().name());
    }

    // ── @s20 — Monto cero es válido ───────────────────────────────────────────

    @Test
    @DisplayName("@s20 — shouldReturnMatchedWhenAmountIsZero")
    void shouldReturnMatchedWhenAmountIsZero() {
        CategorizableTransaction transaction = tx("DEVOLUCION RAPPI", "0.00",
                TransactionKind.CREDIT_CARD_PURCHASE);
        CategoryRule rule = merchantRule(UUID.randomUUID(), "DEVOLUCION", ID_REFUNDS, "Refunds");

        CategoryMatch result = TransactionCategorizer.categorize(transaction, List.of(rule));

        assertThat(result).isInstanceOf(Matched.class);
        assertThat(((Matched) result).category().id()).isEqualTo(ID_REFUNDS);
    }

    // ── @s21 — transactionKinds vacío: coincide con cualquier tipo ────────────

    @Test
    @DisplayName("@s21 — shouldReturnMatchedWhenTransactionKindsIsEmptyInRule")
    void shouldReturnMatchedWhenTransactionKindsIsEmptyInRule() {
        CategorizableTransaction transaction = tx("UBER PASS", "9900.00",
                TransactionKind.INCOMING_PAYMENT);
        CategoryRule rule = new CategoryRule(UUID.randomUUID(), "UBER PASS", null, null,
                Collections.emptySet(), new CategoryRef(ID_TRANSPORT_3, "Transport"));

        CategoryMatch result = TransactionCategorizer.categorize(transaction, List.of(rule));

        assertThat(result).isInstanceOf(Matched.class);
        assertThat(((Matched) result).category().id()).isEqualTo(ID_TRANSPORT_3);
    }

    // ── Mutation hardening — single-predicate & null-kinds ────────────────────
    //
    // These tests kill the 8 PIT survivors that escaped the original 21-scenario
    // suite because every scenario combined merchantPattern with other predicates,
    // leaving three bytecode branches un-exercised.
    //
    // Survivor map:
    //   S2, S3, S7 ← shouldMatchWhenRuleHasOnlyMaxAmountPredicate
    //   S4, S5, S8 ← shouldMatchWhenRuleHasOnlyTransactionKindsPredicate +
    //                 shouldNotMatchWhenTransactionKindsDiffersFromKindsOnlyRule
    //   S1, S6     ← shouldThrowWhenRuleHasNullTransactionKindsAndNoOtherPredicate +
    //                 shouldMatchWhenRuleHasPatternAndNullTransactionKinds

    @Nested
    @DisplayName("Single-predicate rules and null-kinds hardening (mutation hardening)")
    class SinglePredicateAndNullKindsHardeningTest {

        // ── S2, S3, S7 — Regla con solo maxAmount ────────────────────────────

        @Test
        @DisplayName("@s7 (mutation-hardening) — shouldMatchWhenRuleHasOnlyMaxAmountPredicate")
        void shouldMatchWhenRuleHasOnlyMaxAmountPredicate() {
            // Rule: only maxAmount=50000, no pattern, no min, kinds=null
            // Amount 30000 <= 50000 → Matched
            CategorizableTransaction transaction = tx("TIENDA RANDOM", "30000.00",
                    TransactionKind.DEBIT_PURCHASE);
            CategoryRule rule = new CategoryRule(UUID.randomUUID(), null,
                    null, new BigDecimal("50000.00"),
                    null, new CategoryRef(ID_MISC, "Miscellaneous"));

            CategoryMatch result = TransactionCategorizer.categorize(transaction, List.of(rule));

            assertThat(result).isInstanceOf(Matched.class);
            assertThat(((Matched) result).category().id()).isEqualTo(ID_MISC);
        }

        @Test
        @DisplayName("@s7 (mutation-hardening) — shouldNotMatchWhenAmountExceedsMaxAmountOnlyRule")
        void shouldNotMatchWhenAmountExceedsMaxAmountOnlyRule() {
            // Rule: only maxAmount=50000; amount 80000 > 50000 → NoMatch
            CategorizableTransaction transaction = tx("TIENDA RANDOM", "80000.00",
                    TransactionKind.DEBIT_PURCHASE);
            CategoryRule rule = new CategoryRule(UUID.randomUUID(), null,
                    null, new BigDecimal("50000.00"),
                    null, new CategoryRef(ID_MISC, "Miscellaneous"));

            CategoryMatch result = TransactionCategorizer.categorize(transaction, List.of(rule));

            assertThat(result).isInstanceOf(NoMatch.class);
        }

        // ── S4, S5, S8 — Regla con solo transactionKinds no-vacío ────────────

        @Test
        @DisplayName("@s11 (mutation-hardening) — shouldMatchWhenRuleHasOnlyTransactionKindsPredicate")
        void shouldMatchWhenRuleHasOnlyTransactionKindsPredicate() {
            // Rule: only kinds={DEBIT_PURCHASE}, no pattern, no min, no max
            // Transaction is DEBIT_PURCHASE → Matched
            CategorizableTransaction transaction = tx("PAGO EN TIENDA", "15000.00",
                    TransactionKind.DEBIT_PURCHASE);
            CategoryRule rule = new CategoryRule(UUID.randomUUID(), null,
                    null, null,
                    Set.of(TransactionKind.DEBIT_PURCHASE),
                    new CategoryRef(ID_SHOPPING, "Shopping"));

            CategoryMatch result = TransactionCategorizer.categorize(transaction, List.of(rule));

            assertThat(result).isInstanceOf(Matched.class);
            assertThat(((Matched) result).category().id()).isEqualTo(ID_SHOPPING);
        }

        @Test
        @DisplayName("@s12 (mutation-hardening) — shouldNotMatchWhenTransactionKindDiffersFromKindsOnlyRule")
        void shouldNotMatchWhenTransactionKindDiffersFromKindsOnlyRule() {
            // Rule: only kinds={DEBIT_PURCHASE}; transaction is CREDIT_CARD_PURCHASE → NoMatch
            CategorizableTransaction transaction = tx("PAGO EN TIENDA", "15000.00",
                    TransactionKind.CREDIT_CARD_PURCHASE);
            CategoryRule rule = new CategoryRule(UUID.randomUUID(), null,
                    null, null,
                    Set.of(TransactionKind.DEBIT_PURCHASE),
                    new CategoryRef(ID_SHOPPING, "Shopping"));

            CategoryMatch result = TransactionCategorizer.categorize(transaction, List.of(rule));

            assertThat(result).isInstanceOf(NoMatch.class);
        }

        // ── S1, S6 — Rama explicit null en transactionKinds ──────────────────

        @Test
        @DisplayName("@s15 (mutation-hardening) — shouldThrowWhenRuleHasNullTransactionKindsAndNoOtherPredicate")
        void shouldThrowWhenRuleHasNullTransactionKindsAndNoOtherPredicate() {
            // Rule: all predicates absent — merchantPattern=null, min=null, max=null,
            // transactionKinds=null (explicit null, not emptySet). This is a no-predicate
            // catch-all and must be rejected the same as @s15.
            UUID ruleId = UUID.fromString("a1b2c3d4-ffff-ffff-ffff-000000000099");
            CategorizableTransaction transaction = tx("CUALQUIER COMERCIO", "10000.00",
                    TransactionKind.CREDIT_CARD_PURCHASE);
            CategoryRule rule = new CategoryRule(ruleId, null, null, null,
                    null, new CategoryRef(ID_MISC, "Miscellaneous"));

            assertThatThrownBy(() -> TransactionCategorizer.categorize(transaction, List.of(rule)))
                    .isInstanceOf(InvalidRuleException.class)
                    .hasMessageContaining("has no predicates");
        }

        @Test
        @DisplayName("@s1 (mutation-hardening) — shouldMatchWhenRuleHasPatternAndNullTransactionKinds")
        void shouldMatchWhenRuleHasPatternAndNullTransactionKinds() {
            // Rule: merchantPattern present, transactionKinds=null (explicit null).
            // null kinds = no type restriction → any transaction kind matches.
            CategorizableTransaction transaction = tx("RAPPI EXPRESS", "20000.00",
                    TransactionKind.INCOMING_TRANSFER);
            CategoryRule rule = new CategoryRule(UUID.randomUUID(), "RAPPI",
                    null, null,
                    null, new CategoryRef(ID_DINING_1, "Dining Out"));

            CategoryMatch result = TransactionCategorizer.categorize(transaction, List.of(rule));

            assertThat(result).isInstanceOf(Matched.class);
            assertThat(((Matched) result).category().id()).isEqualTo(ID_DINING_1);
        }
    }
}
