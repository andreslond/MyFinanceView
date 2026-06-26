package com.myfinanceview.domain.category;

/**
 * Type-safe result of {@link TransactionCategorizer#categorize}.
 *
 * <p>Use a {@code switch} or {@code instanceof} pattern to handle both cases:
 * <pre>{@code
 *   CategoryMatch result = TransactionCategorizer.categorize(tx, rules);
 *   switch (result) {
 *       case Matched m -> persist(m.category());
 *       case NoMatch ignored -> routeToLlm(tx);
 *   }
 * }</pre>
 *
 * <p>This sealed interface guarantees exhaustive handling — no null, no Optional,
 * no silent fallback (anti-guess principle).
 */
public sealed interface CategoryMatch permits Matched, NoMatch {}
