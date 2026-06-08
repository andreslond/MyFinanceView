package com.myfinanceview.api.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Global exception mapper that converts domain + validation exceptions to RFC 7807
 * {@code application/problem+json} responses. All {@code detail} fields are generic — no echo of
 * tokens, rejected UUIDs, query-param values, descriptions, or stack traces (design.md D11).
 *
 * <p>Mapping table:
 * <ul>
 *   <li>{@link NotFoundException} → 404 not-found</li>
 *   <li>{@link ForbiddenException} → 403 forbidden</li>
 *   <li>{@link MethodArgumentNotValidException} → 400 bad-request (includes {@code errors} list
 *       with {@code field} + generic {@code message} — value never echoed)</li>
 *   <li>{@link ConstraintViolationException} → 400 bad-request (same shape)</li>
 *   <li>{@link HttpMessageNotReadableException} → 400 bad-request</li>
 *   <li>{@link AuthenticationException} → 401 unauthorized</li>
 *   <li>{@link AccessDeniedException} → 403 forbidden</li>
 *   <li>Default → 500 internal</li>
 * </ul>
 *
 * <p>Note: {@link AuthenticationException} / {@link AccessDeniedException} thrown inside the
 * Spring Security filter chain (before MVC dispatching) are handled by the entry-point and access
 * denied handlers wired in {@code SecurityConfig} — those produce ProblemDetail directly without
 * going through this advice. This advice catches exceptions re-thrown from controller methods
 * (rare, but possible if a filter delegates to the MVC exception handler).
 */
@RestControllerAdvice
public class ProblemDetailAdvice {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailAdvice.class);

    private static final String BASE = "https://myfinanceview.local/errors/";

    // ---- 404 Not Found ----------------------------------------------------------------

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(NotFoundException ex, HttpServletRequest req) {
        // Log the internal message (may contain UUID / context) but do NOT surface it.
        log.debug("NotFoundException: {}", ex.getMessage());
        return problem(HttpStatus.NOT_FOUND, "not-found", "Resource not found", "Resource not found", req);
    }

    // ---- 403 Forbidden ----------------------------------------------------------------

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ProblemDetail> handleForbidden(ForbiddenException ex, HttpServletRequest req) {
        log.debug("ForbiddenException: {}", ex.getMessage());
        return problem(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "Forbidden", req);
    }

    // ---- 400 Validation ---------------------------------------------------------------

    /**
     * Handles {@code @Valid @RequestBody} violations. Field messages come from the constraint
     * annotation message but the received value is NEVER included.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleMethodArgumentNotValid(
        MethodArgumentNotValidException ex, HttpServletRequest req
    ) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> Map.of(
                "field", fe.getField(),
                "message", fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "invalid"
            ))
            .toList();
        return validationProblem(req, errors);
    }

    /**
     * Handles {@code @Validated} constraint violations on {@code @RequestParam} / path variables.
     * The offending value is NOT echoed (zero-echo D11).
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(
        ConstraintViolationException ex, HttpServletRequest req
    ) {
        List<Map<String, String>> errors = ex.getConstraintViolations().stream()
            .map(cv -> {
                // propertyPath looks like "methodName.paramName" — extract last segment.
                String path = cv.getPropertyPath().toString();
                String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
                return Map.of(
                    "field", field,
                    "message", cv.getMessage() != null ? cv.getMessage() : "invalid"
                );
            })
            .toList();
        return validationProblem(req, errors);
    }

    /** Handles malformed JSON body (unreadable, wrong type, etc.). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleNotReadable(
        HttpMessageNotReadableException ex, HttpServletRequest req
    ) {
        log.debug("HttpMessageNotReadableException at {}: {}", req.getRequestURI(), ex.getMessage());
        return problem(HttpStatus.BAD_REQUEST, "bad-request", "Bad Request", "Validation failed", req);
    }

    /**
     * Handles type-mismatch on request parameters (e.g. {@code accountId=not-a-uuid} when
     * the param type is {@code UUID}). The offending value is NOT echoed (zero-echo D11).
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(
        MethodArgumentTypeMismatchException ex, HttpServletRequest req
    ) {
        log.debug("MethodArgumentTypeMismatchException for param '{}' at {}", ex.getName(), req.getRequestURI());
        List<Map<String, String>> errors = List.of(
            Map.of("field", ex.getName(), "message", "invalid value")
        );
        return validationProblem(req, errors);
    }

    // ---- Auth (MVC layer — filter-chain auth failures go through SecurityConfig) -------

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthentication(
        AuthenticationException ex, HttpServletRequest req
    ) {
        log.debug("AuthenticationException at {}", req.getRequestURI());
        return problem(HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized", "Unauthorized", req);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(
        AccessDeniedException ex, HttpServletRequest req
    ) {
        log.debug("AccessDeniedException at {}", req.getRequestURI());
        return problem(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "Forbidden", req);
    }

    // ---- 500 Default ------------------------------------------------------------------

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> handleNoResource(NoResourceFoundException ex, HttpServletRequest req) {
        // Spring Boot throws NoResourceFoundException for unmapped paths — disabled actuator
        // endpoints (info/env/configprops/heapdump/etc per D11) and any unknown route. Maps to
        // 404 without leaking the requested path (req.getRequestURI() is internal log only).
        return problem(HttpStatus.NOT_FOUND, "not-found",
            "Resource not found", "Resource not found", req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleDefault(Exception ex, HttpServletRequest req) {
        // Log full stack for server-side diagnostics; nothing beyond the generic detail escapes.
        log.error("Unhandled exception at {}", req.getRequestURI(), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal",
            "Internal Server Error", "An unexpected error occurred", req);
    }

    // ---- Helpers ----------------------------------------------------------------------

    private ResponseEntity<ProblemDetail> validationProblem(
        HttpServletRequest req, List<Map<String, String>> errors
    ) {
        ProblemDetail pd = buildProblemDetail(HttpStatus.BAD_REQUEST, "bad-request",
            "Bad Request", "Validation failed", req);
        pd.setProperty("errors", errors);
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(pd);
    }

    private ResponseEntity<ProblemDetail> problem(
        HttpStatus status, String typeSlug, String title, String detail, HttpServletRequest req
    ) {
        return ResponseEntity
            .status(status)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(buildProblemDetail(status, typeSlug, title, detail, req));
    }

    private ProblemDetail buildProblemDetail(
        HttpStatus status, String typeSlug, String title, String detail, HttpServletRequest req
    ) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create(BASE + typeSlug));
        pd.setTitle(title);
        pd.setInstance(URI.create(req.getRequestURI()));
        return pd;
    }
}
