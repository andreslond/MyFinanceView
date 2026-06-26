package com.myfinanceview.domain.billing;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Pure stateless resolver: given a {@code cutDay} and a {@code transactionDate},
 * returns the closed billing period {@code [start, end]} that contains the transaction.
 *
 * <p>No IO, no clock, no Spring, no jOOQ. The caller is responsible for
 * converting {@code occurred_at} (OffsetDateTime UTC) to LocalDate in
 * {@code America/Bogota} before invoking this method.
 */
public final class BillingPeriodResolver {

    private BillingPeriodResolver() {}

    /**
     * Resolves the billing period [start, end] (both inclusive) that contains
     * {@code transactionDate} for a card with the given {@code cutDay}.
     *
     * <p>Clamping: when {@code cutDay} exceeds the last day of a month,
     * {@code effectiveCutDay = min(cutDay, lastDayOfMonth)} is used. This is
     * deterministic and documented — not a silent fallback.
     *
     * @param cutDay          day of the billing cut, 1–31 inclusive
     * @param transactionDate calendar date of the transaction
     * @throws InvalidCutDayException if {@code cutDay} is outside [1..31]
     */
    public static BillingPeriod resolve(int cutDay, LocalDate transactionDate) {
        // Validation: cutDay must be in [1..31]
        if (cutDay < 1 || cutDay > 31) {
            throw new InvalidCutDayException(cutDay);
        }

        // Determine end month: if dayOfMonth ≤ cutDay → current month; else → next month.
        // Comparison uses literal cutDay (not effectiveCutDay): when cutDay > daysInMonth,
        // all days of that month are ≤ cutDay, so the period always closes in that month — correct.
        YearMonth endYearMonth = (transactionDate.getDayOfMonth() <= cutDay)
                ? YearMonth.from(transactionDate)
                : YearMonth.from(transactionDate).plusMonths(1);

        YearMonth prevYearMonth = endYearMonth.minusMonths(1);

        LocalDate end   = LocalDate.of(endYearMonth.getYear(),  endYearMonth.getMonthValue(),
                effectiveCutDay(cutDay, endYearMonth));
        LocalDate start = LocalDate.of(prevYearMonth.getYear(), prevYearMonth.getMonthValue(),
                effectiveCutDay(cutDay, prevYearMonth)).plusDays(1);

        return new BillingPeriod(start, end);
    }

    /**
     * Returns {@code min(cutDay, lastDayOfMonth)} for the given year-month.
     * Ensures the resolved day always falls within the calendar month.
     */
    private static int effectiveCutDay(int cutDay, YearMonth yearMonth) {
        return Math.min(cutDay, yearMonth.lengthOfMonth());
    }
}
