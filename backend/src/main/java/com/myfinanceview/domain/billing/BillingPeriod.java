package com.myfinanceview.domain.billing;

import java.time.LocalDate;

/**
 * Immutable value object representing a closed billing period [start, end].
 * Both {@code start} and {@code end} are inclusive calendar dates.
 */
public record BillingPeriod(LocalDate start, LocalDate end) {}
