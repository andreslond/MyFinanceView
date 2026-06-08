package com.myfinanceview.auth;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Bridges a successfully-validated {@link Jwt} into an {@link AbstractAuthenticationToken} whose
 * {@code principal} is the {@link UUID} extracted from the {@code sub} claim.
 *
 * <p>Controllers consume it via {@code @AuthenticationPrincipal UUID userId}. If the {@code sub}
 * is missing or not a valid UUID we throw {@link InvalidBearerTokenException} so the
 * resource-server filter chain renders the standard 401 ProblemDetail. We deliberately do <em>not</em>
 * echo the offending {@code sub} value into the exception message (zero-echo per design.md D11);
 * the message is generic and the JWT is logged by the framework only at DEBUG level.
 *
 * <p>Authorities are left empty — the project is single-user and authorisation is enforced inside
 * services by {@code WHERE user_id = ?} (D2), not by Spring Security role checks.
 */
@Component
public final class UserIdJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String sub = jwt.getSubject();
        if (sub == null || sub.isBlank()) {
            // Generic message — no echo of the (absent) sub value.
            throw new InvalidBearerTokenException("invalid_subject");
        }
        final UUID userId;
        try {
            userId = UUID.fromString(sub);
        } catch (IllegalArgumentException ex) {
            // Do NOT include `sub` in the exception message — zero-echo (design.md D11).
            throw new InvalidBearerTokenException("invalid_subject");
        }
        return new JwtAuthenticationToken(jwt, List.of(), userId.toString()) {
            @Override
            public Object getPrincipal() {
                return userId;
            }
        };
    }
}
