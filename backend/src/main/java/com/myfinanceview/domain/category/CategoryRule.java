package com.myfinanceview.domain.category;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

/**
 * A deterministic categorisation rule.
 *
 * <p>A rule <em>matches</em> a transaction if and only if every non-null / non-empty
 * predicate evaluates to {@code true} simultaneously (AND semantics).
 * A predicate that is absent (null or empty collection) places no restriction.
 *
 * <table border="1">
 *   <tr><th>Field</th><th>Absent value</th><th>Evaluation when present</th></tr>
 *   <tr><td>{@code merchantPattern}</td><td>null → no descriptor filter</td>
 *       <td>{@code descriptor.toLowerCase().contains(pattern.toLowerCase())}</td></tr>
 *   <tr><td>{@code minAmount}</td><td>null → no lower bound</td>
 *       <td>{@code amount.compareTo(minAmount) >= 0}</td></tr>
 *   <tr><td>{@code maxAmount}</td><td>null → no upper bound</td>
 *       <td>{@code amount.compareTo(maxAmount) <= 0}</td></tr>
 *   <tr><td>{@code transactionKinds}</td><td>empty → any kind</td>
 *       <td>{@code transactionKinds.contains(transaction.type())}</td></tr>
 * </table>
 *
 * <p><b>Invariants checked at evaluation time</b> (throw {@link InvalidRuleException}):
 * <ul>
 *   <li>At least one predicate must be non-null / non-empty (no catch-all rules).</li>
 *   <li>If {@code merchantPattern} is non-null, it must not be blank.</li>
 *   <li>If both {@code minAmount} and {@code maxAmount} are non-null,
 *       {@code minAmount} must be &lt;= {@code maxAmount}.</li>
 * </ul>
 *
 * @param id                rule ID for traceability
 * @param merchantPattern   substring pattern (case-insensitive); null = no filter
 * @param minAmount         inclusive lower bound; null = no lower bound; scale 2
 * @param maxAmount         inclusive upper bound; null = no upper bound; scale 2
 * @param transactionKinds  allowed kinds; empty = any kind
 * @param category          the category to assign when this rule matches; not null
 */
public record CategoryRule(
        UUID id,
        String merchantPattern,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        Set<TransactionKind> transactionKinds,
        CategoryRef category
) {}
