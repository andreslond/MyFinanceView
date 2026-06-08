package com.myfinanceview.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Mints JWTs for the auth integration tests using an ephemeral EC P-256 keypair generated at
 * construction time. The corresponding public JWK is served by {@link JwksWireMockExtension} so
 * the production {@link org.springframework.security.oauth2.jwt.JwtDecoder} can verify the
 * signature — exactly the same path it would take against Supabase in production.
 *
 * <p>Per scenario in {@code backend-mvp-readonly/spec.md} Requirement
 * "JWT authentication via Supabase JWKS (ES256)", the factory exposes one helper per failure
 * mode (expired, wrong audience, wrong issuer, signed-with-different-key) plus a
 * {@link #malformedTwoSegments()} helper that returns a structurally invalid token (two dots only,
 * no signature) — the parser must reject it before any JWKS lookup happens.
 *
 * <p>Defaults — {@code aud = "authenticated"}, {@code iss} configurable via constructor, TTL 5
 * min — are aligned with Supabase's own token shape so the tests pin behaviour as close to prod
 * as we can get without hitting the real JWKS.
 */
public final class TestJwtFactory {

    public static final String DEFAULT_AUDIENCE = "authenticated";

    private final ECKey primaryKey;
    private final ECKey foreignKey;
    private final String issuer;

    public TestJwtFactory(String issuer) {
        try {
            // KID must match the kid we publish in the JWKS doc so the decoder picks the right key
            this.primaryKey = new ECKeyGenerator(Curve.P_256)
                .keyID("test-key-1")
                .generate();
            this.foreignKey = new ECKeyGenerator(Curve.P_256)
                .keyID("foreign-key")
                .generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("failed to generate test EC keypair", e);
        }
        this.issuer = issuer;
    }

    /** The JWK (public-only) the WireMock extension should publish in JWKS responses. */
    public ECKey publicJwk() {
        return primaryKey.toPublicJWK();
    }

    /** A SECOND public JWK with a different kid — used by the rotation scenario. */
    public ECKey rotatedPublicJwk() {
        return foreignKey.toPublicJWK();
    }

    /** Issue a valid JWT for {@code userId} — passes all default validators. */
    public String valid(UUID userId) {
        return sign(primaryKey, baseClaims(userId).build());
    }

    /** Issue a JWT signed with a DIFFERENT EC keypair than the one published in the JWKS. */
    public String signedWithDifferentKey(UUID userId) {
        return sign(foreignKey, baseClaims(userId).build());
    }

    /** Issue a JWT identical to {@link #valid} but signed with the rotated key (post-rotation). */
    public String validSignedWithRotatedKey(UUID userId) {
        // Note: the rotated key plays the role of "K2" in the rotation scenario. We re-use the
        // foreignKey here intentionally — by the time the test calls this method, the WireMock
        // stub has been rewired to publish foreignKey.toPublicJWK() instead of primaryKey.
        return sign(foreignKey, baseClaims(userId).build());
    }

    public String expired(UUID userId) {
        Instant past = Instant.now().minusSeconds(3600);
        JWTClaimsSet claims = baseClaims(userId)
            .issueTime(Date.from(past.minusSeconds(60)))
            .notBeforeTime(Date.from(past.minusSeconds(60)))
            .expirationTime(Date.from(past))
            .build();
        return sign(primaryKey, claims);
    }

    public String wrongAudience(UUID userId) {
        JWTClaimsSet claims = baseClaimsBuilder(userId.toString(), List.of("service_role")).build();
        return sign(primaryKey, claims);
    }

    public String wrongIssuer(UUID userId) {
        JWTClaimsSet claims = baseClaims(userId).issuer("https://attacker.example/auth").build();
        return sign(primaryKey, claims);
    }

    /** Issue a JWT whose {@code sub} claim is NOT a UUID. */
    public String subNotUuid() {
        JWTClaimsSet claims = baseClaimsBuilder("not-a-uuid", List.of(DEFAULT_AUDIENCE)).build();
        return sign(primaryKey, claims);
    }

    /** Issue an HS256-signed JWT (symmetric) — the decoder must reject because alg != ES256. */
    public String hs256Signed(UUID userId) {
        // 256-bit key for MACSigner.
        byte[] keyBytes = new byte[32];
        java.util.Arrays.fill(keyBytes, (byte) 7);
        try {
            JWTClaimsSet claims = baseClaims(userId).build();
            SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256)
                    .type(JOSEObjectType.JWT)
                    .keyID("hs-key")
                    .build(),
                claims);
            jwt.sign(new MACSigner(keyBytes));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("failed to sign HS256 test JWT", e);
        }
    }

    /** Return a token with only 2 segments (header.payload, no signature). Always rejected. */
    public String malformedTwoSegments() {
        // Real base64url for {"alg":"none","typ":"JWT"} . {"sub":"abc"} — no signature segment.
        return "eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0.eyJzdWIiOiJhYmMifQ";
    }

    // ---- helpers ----------------------------------------------------------------------------

    private JWTClaimsSet.Builder baseClaims(UUID userId) {
        return baseClaimsBuilder(userId.toString(), List.of(DEFAULT_AUDIENCE));
    }

    private JWTClaimsSet.Builder baseClaimsBuilder(String subject, List<String> audience) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
            .subject(subject)
            .issuer(issuer)
            .audience(audience)
            .issueTime(Date.from(now.minusSeconds(60)))
            .notBeforeTime(Date.from(now.minusSeconds(60)))
            .expirationTime(Date.from(now.plusSeconds(300)));
    }

    private String sign(ECKey key, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256)
                    .type(JOSEObjectType.JWT)
                    .keyID(key.getKeyID())
                    .build(),
                claims);
            jwt.sign(new ECDSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("failed to sign test JWT", e);
        }
    }
}
