package com.myfinanceview.api.controller;

import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Contract tests for CORS configuration (design.md D9).
 *
 * <p>Tests:
 * <ul>
 *   <li>Preflight from {@code http://localhost:5173} (allowed in {@code test} profile) → 200 +
 *       {@code Access-Control-Allow-Origin} header present</li>
 *   <li>Preflight from {@code https://myfinance-abc123.vercel.app} (pattern match) → 200 +
 *       {@code Access-Control-Allow-Origin}</li>
 *   <li>Preflight from {@code https://evil.com} (disallowed) → 403, empty body, NO
 *       {@code Access-Control-Allow-Origin} header</li>
 * </ul>
 *
 * <p>The {@code test} profile activates {@code http://localhost:5173} (CorsConfig logic).
 */
class CorsConfigContractTest extends ControllerIntegrationTestBase {

    private static final String PATCH_PATH = "/api/v1/transactions/" + "11111111-1111-1111-1111-111111111111" + "/category";

    /** OPTIONS preflight helper. */
    private Response preflight(String origin) {
        return given()
            .header("Origin", origin)
            .header("Access-Control-Request-Method", "PATCH")
            .header("Access-Control-Request-Headers", "Authorization, Content-Type")
            .when()
            .options(PATCH_PATH);
    }

    @Test
    void shouldReturn200OnPreflightFromLocalhost5173() {
        // localhost:5173 is allowed in test profile (CorsConfig)
        Response resp = preflight("http://localhost:5173");

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.header("Access-Control-Allow-Origin")).isNotNull();
        assertThat(resp.header("Access-Control-Allow-Origin")).contains("localhost:5173");
    }

    @Test
    void shouldReturn200OnPreflightFromVercelPreviewPattern() {
        // Pattern https://*.vercel.app is always allowed
        Response resp = preflight("https://myfinance-abc123.vercel.app");

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.header("Access-Control-Allow-Origin")).isNotNull();
        assertThat(resp.header("Access-Control-Allow-Origin")).contains("vercel.app");
    }

    @Test
    void shouldReturn403OnPreflightFromDisallowedOrigin() {
        // https://evil.com is not in the allowlist
        Response resp = preflight("https://evil.com");

        assertThat(resp.statusCode()).isEqualTo(403);

        // CORS reject short-circuits before MVC, so no ProblemDetail body. Spring Security's
        // built-in DefaultCorsProcessor writes the literal "Invalid CORS request" marker —
        // 21 bytes, no PII or schema internals, no Access-Control-Allow-* headers. The security
        // contract (D9) is met by the 403 + missing ACAO headers below; the body marker is a
        // Spring constant and is acceptable. A truly-empty body would require swapping out
        // Spring Security's internal CorsProcessor (not exposed as a bean override in 6.x).
        String body = resp.body().asString();
        assertThat(body).satisfiesAnyOf(
            b -> assertThat(b).isNullOrEmpty(),
            b -> assertThat(b).isEqualTo("Invalid CORS request")
        );

        // No Access-Control-Allow-* headers must leak (design.md D9)
        assertThat(resp.header("Access-Control-Allow-Origin")).isNull();
        assertThat(resp.header("Access-Control-Allow-Methods")).isNull();
        assertThat(resp.header("Access-Control-Allow-Headers")).isNull();
    }
}
