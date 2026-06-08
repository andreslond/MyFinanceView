package com.myfinanceview.api.controller;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests verifying that malformed {@code Authorization} headers return 401 with zero-echo
 * (design.md D11, spec §MalformedAuth).
 *
 * <p>Focus: controller-layer endpoints (not the ProbeController used in SecurityIntegrationTest).
 * This avoids duplication of the SecurityFilterChain tests while still pinning the API-path
 * behaviour for the exact 4 cases in the spec.
 */
class MalformedAuthorizationTest extends ControllerIntegrationTestBase {

    static Stream<Arguments> malformedHeaders() {
        return Stream.of(
            Arguments.of("NotBearer xyz"),
            Arguments.of("Bearer"),          // empty token after prefix
            Arguments.of("abc.def.ghi"),     // no Bearer prefix
            Arguments.of("Bearer abc.def")   // two-segment JWT — parser rejects
        );
    }

    @ParameterizedTest(name = "header=\"{0}\" → 401, no echo")
    @MethodSource("malformedHeaders")
    void shouldReturn401AndNotEchoHeaderValue(String headerValue) {
        String responseBody = given()
            .header("Authorization", headerValue)
            .when()
            .get("/api/v1/accounts")
            .then()
            .statusCode(401)
            .contentType(org.hamcrest.Matchers.containsString("application/problem+json"))
            .extract()
            .asString();

        // Zero-echo: the raw header value must not appear in the response body
        if (responseBody != null && !responseBody.isBlank()) {
            assertThat(responseBody).doesNotContain(headerValue.trim());
        }
    }
}
