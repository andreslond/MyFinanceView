package com.myfinanceview.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the spec scenario "401 ProblemDetail sin echo del token" of the
 * "ProblemDetail error responses (RFC 7807) sin echo de inputs sensibles" Requirement —
 * the body of a 401 must NOT contain any substring of the rejected JWT (we assert the first
 * 16 characters as a representative slice; per the spec scenario).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({SecurityIntegrationTest.ProbeConfig.class, com.myfinanceview.config.TestJooqConfig.class})
class ProblemDetailZeroEchoTest {

    @RegisterExtension
    static final JwksWireMockExtension jwks = new JwksWireMockExtension();

    @DynamicPropertySource
    static void registerAuthProps(DynamicPropertyRegistry reg) {
        reg.add("app.auth.supabase.jwks-uri", jwks::jwksUrl);
        reg.add("app.auth.supabase.issuer", () -> SecurityIntegrationTest.ISSUER);
        reg.add("app.auth.supabase.audience", () -> "authenticated");
    }

    @Autowired TestRestTemplate rest;
    @LocalServerPort int port;

    @Test
    void should401AndNotEchoTokenInProblemDetailWhenJwtInvalid() {
        TestJwtFactory tokens = new TestJwtFactory(SecurityIntegrationTest.ISSUER);
        jwks.stubKeys(tokens.publicJwk());

        // Use a token signed with a different key so the decoder rejects it during verification.
        String token = tokens.signedWithDifferentKey(UUID.randomUUID());
        // Sanity: the token has the standard 3-segment JWT shape so the prefix is meaningful.
        assertThat(token.split("\\.")).hasSize(3);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<String> resp = rest.exchange(
            "http://localhost:" + port + "/api/v1/_probe",
            HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(401);

        String body = resp.getBody();
        assertThat(body).isNotNull();
        // The 16-char prefix of the rejected token must not appear anywhere in the body.
        String prefix = token.substring(0, 16);
        assertThat(body)
            .as("ProblemDetail body must not echo the rejected JWT prefix (design.md D11)")
            .doesNotContain(prefix);
    }
}
