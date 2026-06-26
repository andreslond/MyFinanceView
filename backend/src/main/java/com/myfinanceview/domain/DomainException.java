package com.myfinanceview.domain;

/**
 * Base class for all unchecked domain exceptions in MyFinanceView.
 * Domain exceptions signal broken invariants or missing knowledge within the
 * pure domain layer. They carry explicit messages for traceability and are
 * expected to be translated to RFC 7807 ProblemDetail by the controller advice.
 */
public abstract class DomainException extends RuntimeException {

    protected DomainException(String message) {
        super(message);
    }

    protected DomainException(String message, Throwable cause) {
        super(message, cause);
    }
}
