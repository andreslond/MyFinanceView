package com.myfinanceview.domain.category;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Pure stateless categoriser: applies a list of {@link CategoryRule rules} to a
 * {@link CategorizableTransaction} and returns a deterministic {@link CategoryMatch}.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Validate each rule in order; throw {@link InvalidRuleException} on first violation.</li>
 *   <li>Collect all rules that match the transaction.</li>
 *   <li>If no rule matches → {@link NoMatch}.</li>
 *   <li>If all matching rules point to the same {@link CategoryRef#id()} → {@link Matched}.</li>
 *   <li>If matching rules point to different category UUIDs → {@link NoMatch} (ambiguity;
 *       anti-guess principle: never invent a winner).</li>
 * </ol>
 *
 * <p>No IO, no clock, no Spring, no jOOQ.
 */
public final class TransactionCategorizer {

    private TransactionCategorizer() {}

    /**
     * Categorises {@code transaction} using {@code rules}.
     *
     * @param transaction normalised transaction; not null
     * @param rules       ordered list of rules; may be empty (→ NoMatch); not null
     * @return {@link Matched} or {@link NoMatch}; never null
     * @throws InvalidRuleException if any rule has no predicates, has a blank
     *                              merchantPattern, or has minAmount &gt; maxAmount
     */
    public static CategoryMatch categorize(
            CategorizableTransaction transaction,
            List<CategoryRule> rules
    ) {
        List<CategoryRule> matched = new ArrayList<>();

        for (CategoryRule rule : rules) {
            validateRule(rule);
            if (ruleMatches(rule, transaction)) {
                matched.add(rule);
            }
        }

        if (matched.isEmpty()) {
            return new NoMatch();
        }

        UUID firstCategoryId = matched.get(0).category().id();
        boolean allSameCategory = matched.stream()
                .allMatch(r -> r.category().id().equals(firstCategoryId));

        if (allSameCategory) {
            return new Matched(matched.get(0).category());
        }

        // Two or more matching rules point to different categories — ambiguity.
        // Anti-guess principle: return NoMatch instead of picking one arbitrarily.
        return new NoMatch();
    }

    // ── Rule validation ────────────────────────────────────────────────────────

    private static void validateRule(CategoryRule rule) {
        // Check 1: merchantPattern non-null but blank → invalid
        if (rule.merchantPattern() != null && rule.merchantPattern().isBlank()) {
            throw new InvalidRuleException(
                    "Rule " + rule.id() + ": merchantPattern must not be blank when non-null.");
        }

        // Check 2: minAmount > maxAmount → impossible range
        if (rule.minAmount() != null && rule.maxAmount() != null
                && rule.minAmount().compareTo(rule.maxAmount()) > 0) {
            throw new InvalidRuleException(
                    "Rule " + rule.id() + ": minAmount must be <= maxAmount.");
        }

        // Check 3: rule with no predicates at all → implicit catch-all, forbidden
        boolean hasPattern = rule.merchantPattern() != null;
        boolean hasMin     = rule.minAmount() != null;
        boolean hasMax     = rule.maxAmount() != null;
        boolean hasKinds   = rule.transactionKinds() != null && !rule.transactionKinds().isEmpty();

        if (!hasPattern && !hasMin && !hasMax && !hasKinds) {
            throw new InvalidRuleException(
                    "Rule " + rule.id() + " has no predicates — at least one predicate is required.");
        }
    }

    // ── Rule matching ──────────────────────────────────────────────────────────

    private static boolean ruleMatches(CategoryRule rule, CategorizableTransaction tx) {
        // merchantPattern predicate (case-insensitive substring)
        if (rule.merchantPattern() != null) {
            if (!tx.descriptor().toLowerCase().contains(rule.merchantPattern().toLowerCase())) {
                return false;
            }
        }

        // minAmount predicate (inclusive lower bound, compareTo — never equals)
        if (rule.minAmount() != null) {
            if (tx.amount().compareTo(rule.minAmount()) < 0) {
                return false;
            }
        }

        // maxAmount predicate (inclusive upper bound)
        if (rule.maxAmount() != null) {
            if (tx.amount().compareTo(rule.maxAmount()) > 0) {
                return false;
            }
        }

        // transactionKinds predicate (empty = no restriction)
        Set<TransactionKind> kinds = rule.transactionKinds();
        if (kinds != null && !kinds.isEmpty()) {
            if (!kinds.contains(tx.type())) {
                return false;
            }
        }

        return true;
    }
}
