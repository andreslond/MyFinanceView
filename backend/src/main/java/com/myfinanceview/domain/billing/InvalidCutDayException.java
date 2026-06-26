package com.myfinanceview.domain.billing;

import com.myfinanceview.domain.DomainException;

/**
 * Thrown when a {@code cutDay} value falls outside the valid range [1..31].
 * This is an anti-guessing guard: the domain never silently defaults when
 * configuration is out of range.
 */
public final class InvalidCutDayException extends DomainException {

    public InvalidCutDayException(int cutDay) {
        super("cutDay " + cutDay + " is out of valid range [1..31]. "
                + "Provide a value between 1 and 31 inclusive.");
    }
}
