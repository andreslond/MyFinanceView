package com.myfinanceview.domain.category;

/**
 * Successful categorisation result: one or more rules matched and all agreed on
 * the same category.
 *
 * @param category the resolved category; not null
 */
public record Matched(CategoryRef category) implements CategoryMatch {}
