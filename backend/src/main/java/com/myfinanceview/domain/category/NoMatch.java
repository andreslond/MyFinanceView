package com.myfinanceview.domain.category;

/**
 * Indicates that no single unambiguous category could be determined:
 * <ul>
 *   <li>no rule in the list matched the transaction, or</li>
 *   <li>the rules list was empty, or</li>
 *   <li>two or more rules matched but pointed to different categories (ambiguity).</li>
 * </ul>
 *
 * <p>Anti-guess principle: the domain never invents a default category.
 * The caller (service layer) decides the next step (e.g. route to LLM).
 */
public record NoMatch() implements CategoryMatch {}
