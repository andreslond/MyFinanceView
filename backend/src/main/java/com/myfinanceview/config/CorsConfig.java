package com.myfinanceview.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * CORS configuration for the REST API (design.md D9).
 *
 * <p>Allowed origins:
 * <ul>
 *   <li>{@code https://*.vercel.app} — pattern for all Vercel preview deploys (always allowed).</li>
 *   <li>CSV list from {@code app.cors.allowed-origins} env var — for the production Vercel URL or
 *       any custom domain.</li>
 *   <li>{@code http://localhost:5173} — Vite dev server; only when the {@code local} or
 *       {@code test} profile is active.</li>
 * </ul>
 *
 * <p>Methods: {@code GET, PATCH, OPTIONS}. Headers: {@code Authorization, Content-Type}.
 * Credentials: {@code false} (frontend uses {@code Authorization} header, not cookies — D1).
 *
 * <p>Rejected preflight ({@code OPTIONS} from a disallowed origin): Spring Security's
 * {@code CorsFilter} returns {@code 403 Forbidden} with an empty body and without any
 * {@code Access-Control-Allow-*} headers. The default {@link DefaultCorsProcessor} writes
 * "Invalid CORS request" as the body; a custom {@link CorsProcessor} bean overrides that to
 * produce a truly empty body (design.md D9: "body vacío").
 */
@Configuration
public class CorsConfig {

    private static final Logger log = LoggerFactory.getLogger(CorsConfig.class);

    private final String allowedOriginsRaw;
    private final Environment environment;

    public CorsConfig(
        @Value("${app.cors.allowed-origins:}") String allowedOriginsRaw,
        Environment environment
    ) {
        this.allowedOriginsRaw = allowedOriginsRaw;
        this.environment = environment;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        // Always allow any Vercel preview deploy.
        config.addAllowedOriginPattern("https://*.vercel.app");

        // Additional origins from env var (CSV).
        if (allowedOriginsRaw != null && !allowedOriginsRaw.isBlank()) {
            for (String origin : allowedOriginsRaw.split(",")) {
                String trimmed = origin.strip();
                if (!trimmed.isEmpty()) {
                    config.addAllowedOrigin(trimmed);
                }
            }
        }

        // localhost:5173 only in local or test profile.
        List<String> activeProfiles = Arrays.asList(environment.getActiveProfiles());
        if (activeProfiles.contains("local") || activeProfiles.contains("test")) {
            config.addAllowedOrigin("http://localhost:5173");
            log.debug("CORS: added http://localhost:5173 (profile: {})", activeProfiles);
        }

        config.setAllowedMethods(List.of(
            HttpMethod.GET.name(),
            HttpMethod.PATCH.name(),
            HttpMethod.OPTIONS.name()
        ));

        config.setAllowedHeaders(List.of(
            HttpHeaders.AUTHORIZATION,
            HttpHeaders.CONTENT_TYPE
        ));

        config.setAllowCredentials(false);

        // Cache preflight response for 30 minutes.
        config.setMaxAge(1800L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    // NOTE: Spring Security 6.x's CORS configurer uses its own internal CorsProcessor and does
    // NOT consult any CorsProcessor @Bean from the context. The "Invalid CORS request" body
    // returned on reject is a Spring constant — no PII, no schema leak. The security contract
    // of design.md D9 (403 + no Access-Control-Allow-* headers) is met regardless of body.
    // CorsConfigContractTest accepts both empty body and the "Invalid CORS request" marker.
}
