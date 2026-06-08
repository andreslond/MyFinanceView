package com.myfinanceview.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myfinanceview.auth.UserIdJwtAuthenticationConverter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Security wiring for the REST API — see openspec/changes/backend-mvp-readonly/design.md D1.
 *
 * <p>Highlights:
 * <ul>
 *   <li>{@code /api/v1/**} requires a Supabase-issued JWT validated against the public JWKS.</li>
 *   <li>{@code /actuator/health} is the only public actuator endpoint (D11); the rest are
 *       individually disabled in {@code application.yml} so they 404 instead of 403.</li>
 *   <li>CSRF is disabled (stateless REST, no cookies — D1). Sessions are STATELESS.</li>
 *   <li>{@link AuthenticationEntryPoint} and {@link AccessDeniedHandler} emit RFC 7807
 *       ProblemDetail bodies with generic {@code detail} (zero-echo per D11).</li>
 *   <li>CORS rejects (preflight from unlisted origin) emit 403 with an empty body. This chunk
 *       only wires the security chain; the actual {@code CorsConfigurationSource} comes from §7.
 *       For now we register {@code cors(withDefaults())} so the chain inherits whatever bean is
 *       on the classpath; without one, no CORS handling is added and the spec scenarios for §7
 *       will pin the bean later.</li>
 * </ul>
 *
 * <p>The {@link JwtDecoder} bean is built with {@link NimbusJwtDecoder#withJwkSetUri(String)} and
 * a composite validator: timestamp (default Nimbus) + issuer + custom audience check requiring
 * {@code "authenticated"} to be present in the {@code aud} claim.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final String jwksUri;
    private final String issuer;
    private final String audience;

    public SecurityConfig(
        @Value("${app.auth.supabase.jwks-uri:}") String jwksUri,
        @Value("${app.auth.supabase.issuer:}") String issuer,
        @Value("${app.auth.supabase.audience:authenticated}") String audience
    ) {
        this.jwksUri = jwksUri;
        this.issuer = issuer;
        this.audience = audience;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
        HttpSecurity http,
        UserIdJwtAuthenticationConverter jwtAuthConverter,
        JwtDecoder jwtDecoder,
        ObjectMapper objectMapper
    ) throws Exception {
        AuthenticationEntryPoint unauthorizedEntryPoint = problemDetailAuthenticationEntryPoint(objectMapper);
        AccessDeniedHandler forbiddenHandler = problemDetailAccessDeniedHandler(objectMapper);

        http
            // STATELESS REST — no session, no CSRF token (we never read cookies for auth).
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(csrf -> csrf.disable())
            // CORS hook — CorsConfigurationSource bean (if/when registered in §7) is picked up
            // automatically by Spring Security when cors() is enabled.
            .cors(cors -> {})
            .authorizeHttpRequests(authz -> authz
                .requestMatchers(new AntPathRequestMatcher("/actuator/health")).permitAll()
                // Anything else under /api/v1/** needs a valid Bearer token.
                .requestMatchers(new AntPathRequestMatcher("/api/v1/**")).authenticated()
                // Other paths (e.g. disabled actuator endpoints, unknown routes) — let MVC return 404.
                .anyRequest().permitAll()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt
                    .decoder(jwtDecoder)
                    .jwtAuthenticationConverter(jwtAuthConverter)
                )
                .authenticationEntryPoint(unauthorizedEntryPoint)
                .accessDeniedHandler(forbiddenHandler)
            )
            // Belt-and-suspenders: even if the resource-server entry point is bypassed (e.g.
            // an exception inside the filter chain before oauth2 kicks in), unauthorised requests
            // still get a ProblemDetail body.
            .exceptionHandling(eh -> eh
                .authenticationEntryPoint(unauthorizedEntryPoint)
                .accessDeniedHandler(forbiddenHandler)
            );

        return http.build();
    }

    /**
     * JWT decoder pinned to ES256 (Supabase asymmetric signing) and validated against the JWKS
     * endpoint configured by {@code app.auth.supabase.jwks-uri}. The decoder transparently caches
     * the JWKS for 5 min (Nimbus default) and refreshes on cache miss — covers Supabase key
     * rotation without any custom code.
     *
     * <p>Validators in order: expiration (timestamp), issuer, and custom audience predicate
     * requiring {@code "authenticated"} membership.
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        if (jwksUri == null || jwksUri.isBlank()) {
            // Operator / test harness hasn't wired a JWKS yet — register a decoder that rejects
            // every token with a clear message at request-time so the application context still
            // loads (used by health-only smoke tests like MyFinanceViewApplicationTests).
            // The security chain reads /actuator/health unauthenticated, so health checks still
            // work; any /api/v1/** call returns 401 with the standard ProblemDetail body.
            return token -> {
                throw new org.springframework.security.oauth2.jwt.BadJwtException(
                    "app.auth.supabase.jwks-uri not configured");
            };
        }

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri)
            .jwsAlgorithm(SignatureAlgorithm.ES256)
            .build();

        List<OAuth2TokenValidator<Jwt>> validators = new java.util.ArrayList<>();
        validators.add(new JwtTimestampValidator());
        if (issuer != null && !issuer.isBlank()) {
            validators.add(new JwtIssuerValidator(issuer));
        }
        validators.add(audienceValidator(audience));

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }

    /**
     * Predicate validator: rejects the JWT unless its {@code aud} claim (whether scalar or list)
     * contains {@code expectedAudience}. Implemented manually because {@link JwtClaimValidator}
     * with {@code List<String>} fails when the claim is a single string (Supabase varies).
     */
    static OAuth2TokenValidator<Jwt> audienceValidator(String expectedAudience) {
        return new JwtClaimValidator<Object>("aud", value -> {
            if (value == null) return false;
            if (value instanceof String s) return expectedAudience.equals(s);
            if (value instanceof List<?> list) return list.contains(expectedAudience);
            return false;
        });
    }

    // -- ProblemDetail entry-point + access-denied handler ----------------------------------

    static AuthenticationEntryPoint problemDetailAuthenticationEntryPoint(ObjectMapper objectMapper) {
        return (request, response, authException) -> {
            ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Unauthorized");
            body.setType(URI.create("https://myfinanceview.local/errors/unauthorized"));
            body.setTitle("Unauthorized");
            body.setInstance(URI.create(request.getRequestURI()));
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            // Strip the default WWW-Authenticate header so the realm/error-description (which
            // would echo the parse error) is not surfaced to the client. design.md D11.
            response.setHeader("WWW-Authenticate", "Bearer");
            objectMapper.writeValue(response.getOutputStream(), body);
        };
    }

    static AccessDeniedHandler problemDetailAccessDeniedHandler(ObjectMapper objectMapper) {
        return (request, response, accessDeniedException) -> {
            ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Forbidden");
            body.setType(URI.create("https://myfinanceview.local/errors/forbidden"));
            body.setTitle("Forbidden");
            body.setInstance(URI.create(request.getRequestURI()));
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            objectMapper.writeValue(response.getOutputStream(), body);
        };
    }

    /**
     * Fallback entry point used only if Spring resolves the bean before oauth2 wiring kicks in.
     * Not currently referenced but kept for parity with debugging tools that scan the context.
     */
    AuthenticationEntryPoint fallbackEntryPoint() {
        return new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED);
    }
}
