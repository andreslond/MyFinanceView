package com.myfinanceview.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.moreThanOrExactly;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the OAuth2 Resource Server wiring — covers all scenarios of the
 * "JWT authentication via Supabase JWKS (ES256)" Requirement in
 * {@code archive/openspec-legacy/changes/archive/2026-06-08-backend-mvp-readonly/specs/backend-rest-api/spec.md}.
 *
 * <p>Strategy (per design.md D1):
 * <ul>
 *   <li>{@link JwksWireMockExtension} hosts a mock JWKS endpoint with the public key of an
 *       in-process EC P-256 keypair owned by {@link TestJwtFactory}.</li>
 *   <li>The host class wires {@code app.auth.supabase.jwks-uri} / {@code .issuer} via
 *       {@link DynamicPropertySource} so the production {@link
 *       org.springframework.security.oauth2.jwt.JwtDecoder} bean targets WireMock instead of
 *       Supabase.</li>
 *   <li>Each test hits {@code /api/v1/_probe} (a test-only controller — {@link ProbeController})
 *       and asserts status / zero-echo.</li>
 * </ul>
 *
 * <p>We use {@code TestRestTemplate} on a random server port rather than {@code MockMvc} because
 * the resource-server filter chain behaves slightly differently behind the wire (e.g.
 * {@code WWW-Authenticate} header handling) and we want to pin the real HTTP behaviour.
 *
 * <p>The {@code spring.autoconfigure.exclude} keys in {@code application-test.yml} keep the
 * datasource / jOOQ auto-configuration out so this test stays infra-light — no Testcontainers
 * needed for the auth chunk.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({SecurityIntegrationTest.ProbeConfig.class, com.myfinanceview.config.TestJooqConfig.class})
class SecurityIntegrationTest {

    static final String ISSUER = "https://issuer.test.local/auth/v1";
    static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @RegisterExtension
    static final JwksWireMockExtension jwks = new JwksWireMockExtension();

    static TestJwtFactory tokens;

    @DynamicPropertySource
    static void registerAuthProps(DynamicPropertyRegistry reg) {
        reg.add("app.auth.supabase.jwks-uri", jwks::jwksUrl);
        reg.add("app.auth.supabase.issuer", () -> ISSUER);
        reg.add("app.auth.supabase.audience", () -> "authenticated");
    }

    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper objectMapper;
    @LocalServerPort int port;

    @BeforeEach
    void seedKeys() {
        if (tokens == null) {
            tokens = new TestJwtFactory(ISSUER);
        }
        // Reset stubs + request count so the rotation scenario starts from a known baseline.
        jwks.stubKeys(tokens.publicJwk());
        jwks.resetRequestCount();
    }

    // ------------------------------------------------------------------------------------
    // Happy path & ProblemDetail shape
    // ------------------------------------------------------------------------------------

    @Test
    void should200WhenJwtValid() {
        ResponseEntity<String> resp = call("/api/v1/_probe", tokens.valid(USER_ID));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).isEqualTo(USER_ID.toString());
    }

    @Test
    void should401WhenNoAuthHeader() throws Exception {
        ResponseEntity<String> resp = call("/api/v1/_probe", null);
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        assertUnauthorizedProblemDetail(resp.getBody());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "NotBearer xyz",
        "Bearer ",
        "abc.def.ghi",            // no "Bearer" prefix
        "Bearer abc.def"          // two-segment "JWT" — parser rejects
    })
    void should401WhenAuthHeaderMalformed(String authValue) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.AUTHORIZATION, authValue);
        ResponseEntity<String> resp = rest.exchange(
            "http://localhost:" + port + "/api/v1/_probe",
            HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        // Zero-echo: the malformed header value must not appear in the body.
        if (resp.getBody() != null) {
            assertThat(resp.getBody()).doesNotContain(authValue);
        }
    }

    @Test
    void should401WhenJwtSignedWithWrongKey() {
        String token = tokens.signedWithDifferentKey(USER_ID);
        ResponseEntity<String> resp = call("/api/v1/_probe", token);
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        // Zero-echo: 16-char prefix of the token must not appear in the body.
        assertThat(resp.getBody()).doesNotContain(token.substring(0, 16));
    }

    @Test
    void should401WhenJwtAlgIsHS256() {
        String token = tokens.hs256Signed(USER_ID);
        ResponseEntity<String> resp = call("/api/v1/_probe", token);
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void should401WhenJwtExpired() {
        ResponseEntity<String> resp = call("/api/v1/_probe", tokens.expired(USER_ID));
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void should401WhenJwtWrongIssuer() {
        ResponseEntity<String> resp = call("/api/v1/_probe", tokens.wrongIssuer(USER_ID));
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void should401WhenJwtWrongAudience() {
        ResponseEntity<String> resp = call("/api/v1/_probe", tokens.wrongAudience(USER_ID));
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void should401WhenJwtSubNotUuid() {
        ResponseEntity<String> resp = call("/api/v1/_probe", tokens.subNotUuid());
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void should200WhenJwksRotated() {
        // Phase 1: stub already serves primaryKey (set in @BeforeEach).
        String firstToken = tokens.valid(USER_ID);
        ResponseEntity<String> first = call("/api/v1/_probe", firstToken);
        assertThat(first.getStatusCode().value()).isEqualTo(200);

        // Re-zero the WireMock request counter — we want to count ONLY the post-rotation fetches
        // so the assertion is robust whether or not the decoder cache was warm from prior tests
        // (the @SpringBootTest context is reused across test methods in the class).
        jwks.resetRequestCount();

        // Phase 2: rotate — JWKS now publishes ONLY the rotated key. The decoder must invalidate
        // its cache and fetch /jwks.json again to learn the new key set.
        jwks.stubKeys(tokens.rotatedPublicJwk());

        String rotatedToken = tokens.validSignedWithRotatedKey(USER_ID);
        ResponseEntity<String> second = call("/api/v1/_probe", rotatedToken);
        assertThat(second.getStatusCode().value())
            .as("post-rotation request must succeed once the decoder re-fetches the JWKS")
            .isEqualTo(200);

        // Post-rotation, the decoder MUST have fetched the JWKS at least once to learn K2.
        // The K1 fetch (which may or may not have happened depending on cache state) is
        // intentionally excluded — see resetRequestCount() above.
        jwks.wireMock().verify(moreThanOrExactly(1), getRequestedFor(urlPathEqualTo(JwksWireMockExtension.JWKS_PATH)));
    }

    // ------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------

    private ResponseEntity<String> call(String path, String bearerOrNull) {
        HttpHeaders headers = new HttpHeaders();
        if (bearerOrNull != null) {
            headers.setBearerAuth(bearerOrNull);
        }
        return rest.exchange(
            "http://localhost:" + port + path,
            HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private void assertUnauthorizedProblemDetail(String body) throws Exception {
        assertThat(body).isNotNull();
        JsonNode node = objectMapper.readTree(body);
        assertThat(node.path("type").asText())
            .isEqualTo("https://myfinanceview.local/errors/unauthorized");
        assertThat(node.path("status").asInt()).isEqualTo(401);
        assertThat(node.path("title").asText()).isEqualTo("Unauthorized");
        assertThat(node.path("detail").asText()).isEqualTo("Unauthorized");
    }

    /**
     * Brings in the test-only {@link ProbeController} so {@code @SpringBootTest} scans it. Kept
     * as an inner config to avoid leaking the controller into other tests in the package.
     */
    @TestConfiguration
    static class ProbeConfig {
        @org.springframework.context.annotation.Bean
        ProbeController probeController() {
            return new ProbeController();
        }
    }
}
