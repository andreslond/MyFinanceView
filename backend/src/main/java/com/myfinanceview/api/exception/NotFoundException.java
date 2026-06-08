package com.myfinanceview.api.exception;

/**
 * Thrown when a resource cannot be located OR when the authenticated user lacks visibility on it.
 *
 * <p><b>Zero-echo discipline</b> (design.md D11 / spec.md "ProblemDetail error responses"): the
 * {@code internalMessage} is meant for logs only — the {@code @RestControllerAdvice} mapper SHALL
 * return a generic {@code detail} ({@code "Resource not found"}) to the client. Callers MAY put
 * loggable context here (e.g. the rejected UUID, the user id) without risking a leak — the
 * controller advice strips it.
 *
 * <p>Maps to HTTP 404 in {@code ProblemDetailAdvice}.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String internalMessage) {
        super(internalMessage);
    }

    public NotFoundException(String internalMessage, Throwable cause) {
        super(internalMessage, cause);
    }
}
