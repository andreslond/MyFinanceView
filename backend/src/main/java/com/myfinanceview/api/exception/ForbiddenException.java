package com.myfinanceview.api.exception;

/**
 * Thrown when the authenticated user is identified but is not allowed to perform the action.
 *
 * <p>Currently unused by §6 (the user-scoped access pattern collapses "not visible" to 404 via
 * {@link NotFoundException} to avoid enumeration leaks — see design.md D10). Pre-created here so
 * §7 controllers and the {@code ProblemDetailAdvice} can register the mapping without an
 * additional pass.
 *
 * <p>Zero-echo discipline: same as {@link NotFoundException} — the controller advice replaces the
 * internal message with a generic {@code "Forbidden"} string on the wire.
 *
 * <p>Maps to HTTP 403 in {@code ProblemDetailAdvice}.
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String internalMessage) {
        super(internalMessage);
    }

    public ForbiddenException(String internalMessage, Throwable cause) {
        super(internalMessage, cause);
    }
}
