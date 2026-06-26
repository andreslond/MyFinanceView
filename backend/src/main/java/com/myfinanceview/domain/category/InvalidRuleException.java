package com.myfinanceview.domain.category;

import com.myfinanceview.domain.DomainException;

/**
 * Thrown when a {@link CategoryRule} is structurally invalid and cannot be evaluated.
 *
 * <p>Conditions that trigger this exception (checked at evaluation time in
 * {@link TransactionCategorizer#categorize}):
 * <ul>
 *   <li>The rule has no predicates at all (would act as an implicit catch-all).</li>
 *   <li>{@code merchantPattern} is non-null but blank (would match any descriptor).</li>
 *   <li>{@code minAmount > maxAmount} (impossible range).</li>
 * </ul>
 */
public final class InvalidRuleException extends DomainException {

    public InvalidRuleException(String message) {
        super(message);
    }
}
