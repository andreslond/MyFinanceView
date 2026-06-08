package com.myfinanceview.auth;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.Arrays;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

/**
 * JUnit 5 extension that boots a {@link WireMockServer} on a random free port, publishes a JWKS
 * document at {@code /jwks.json}, and exposes hooks to rotate the served keys (used by the
 * JWKS-rotation scenario of the auth spec — see {@code backend-mvp-readonly/spec.md}).
 *
 * <p>The extension is class-scoped (instantiated by {@code @RegisterExtension static}). The
 * server URL is fixed at startup and intended to be wired to {@code app.auth.supabase.jwks-uri}
 * via a {@code @DynamicPropertySource} on the host test class — see
 * {@code SecurityIntegrationTest} for the pattern.
 *
 * <p>Tests that need to verify a fresh JWKS fetch (e.g. rotation) can call
 * {@link #stubKeys(JWK...)} to swap the published key set and {@link #wireMock()} to get the
 * underlying {@code WireMockServer} for verification (e.g.
 * {@code wireMock.verify(moreThan(1), getRequestedFor(urlPathEqualTo("/jwks.json")))}).
 */
public final class JwksWireMockExtension implements BeforeAllCallback, AfterAllCallback {

    public static final String JWKS_PATH = "/jwks.json";

    private final WireMockServer server;

    public JwksWireMockExtension() {
        this.server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        if (!server.isRunning()) {
            server.start();
            WireMock.configureFor("localhost", server.port());
        }
    }

    @Override
    public void afterAll(ExtensionContext context) {
        if (server.isRunning()) {
            server.stop();
        }
    }

    public WireMockServer wireMock() {
        return server;
    }

    public String jwksUrl() {
        return server.baseUrl() + JWKS_PATH;
    }

    public int port() {
        return server.port();
    }

    /**
     * Replace the JWKS document served at {@link #JWKS_PATH} with one containing exactly the
     * given JWKs (public form only). Wipes prior stubs at this path so the decoder must
     * re-fetch to learn the new key set.
     */
    public void stubKeys(JWK... keys) {
        // Reset only mappings (not call counts) so the rotation scenario can assert request count
        server.resetMappings();
        JWKSet set = new JWKSet(Arrays.stream(keys)
            .map(k -> (k instanceof ECKey ec) ? ec.toPublicJWK() : k.toPublicJWK())
            .toList());
        server.stubFor(get(urlPathEqualTo(JWKS_PATH))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(set.toString())));
    }

    /**
     * Convenience overload accepting a list — kept so existing call sites that already build a
     * {@code List<JWK>} do not have to expand it.
     */
    public void stubKeys(List<JWK> keys) {
        stubKeys(keys.toArray(JWK[]::new));
    }

    public void resetRequestCount() {
        server.resetRequests();
    }
}
