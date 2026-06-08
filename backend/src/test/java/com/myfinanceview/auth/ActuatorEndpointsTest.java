package com.myfinanceview.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the Spring Boot Actuator surface to {@code /actuator/health} only — every other
 * actuator endpoint MUST return 404 (not 403, per design.md D11). Health responses MUST NOT
 * include {@code components} / {@code details} so unauth callers cannot probe DB state.
 *
 * <p>JWKS auth properties are injected (same WireMock harness as
 * {@link SecurityIntegrationTest}) only because the {@code JwtDecoder} bean is in the context;
 * none of these tests actually exchange a JWT — actuator paths are tested unauthenticated.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(com.myfinanceview.config.TestJooqConfig.class)
class ActuatorEndpointsTest {

    @RegisterExtension
    static final JwksWireMockExtension jwks = new JwksWireMockExtension();

    @DynamicPropertySource
    static void registerAuthProps(DynamicPropertyRegistry reg) {
        reg.add("app.auth.supabase.jwks-uri", jwks::jwksUrl);
        reg.add("app.auth.supabase.issuer", () -> "https://issuer.test.local/auth/v1");
        reg.add("app.auth.supabase.audience", () -> "authenticated");
    }

    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper objectMapper;
    @LocalServerPort int port;

    @Test
    void should200OnHealthWithoutAuth() throws Exception {
        ResponseEntity<String> resp = rest.getForEntity(url("/actuator/health"), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);

        JsonNode node = objectMapper.readTree(resp.getBody());
        assertThat(node.path("status").asText()).isEqualTo("UP");
    }

    @Test
    void shouldHealthBodyBeStatusUpOnly() throws Exception {
        ResponseEntity<String> resp = rest.getForEntity(url("/actuator/health"), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);

        JsonNode node = objectMapper.readTree(resp.getBody());
        // Must not leak DB connection state or other components — design.md D11.
        assertThat(node.has("components")).isFalse();
        assertThat(node.has("details")).isFalse();
        assertThat(node.has("db")).isFalse();
        // status = "UP" and nothing else of consequence
        assertThat(node.fieldNames()).toIterable().containsExactly("status");
    }

    @Test
    void should404OnInfo() {
        assertThat(rest.getForEntity(url("/actuator/info"), String.class).getStatusCode().value())
            .isEqualTo(404);
    }

    @Test
    void should404OnEnv() {
        assertThat(rest.getForEntity(url("/actuator/env"), String.class).getStatusCode().value())
            .isEqualTo(404);
    }

    @Test
    void should404OnConfigprops() {
        assertThat(rest.getForEntity(url("/actuator/configprops"), String.class).getStatusCode().value())
            .isEqualTo(404);
    }

    @Test
    void should404OnHeapdump() {
        assertThat(rest.getForEntity(url("/actuator/heapdump"), String.class).getStatusCode().value())
            .isEqualTo(404);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
