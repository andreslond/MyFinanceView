package com.myfinanceview.api.controller;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Contract tests for {@code GET /api/v1/accounts}.
 *
 * <p>Asserts:
 * <ul>
 *   <li>200 with accounts list where {@code name} is present (mapped from {@code nickname} — D6)</li>
 *   <li>401 when no Authorization header</li>
 * </ul>
 */
class AccountControllerContractTest extends ControllerIntegrationTestBase {

    private static final UUID USER = UUID.fromString("aa000000-0000-0000-0000-000000000001");

    @Test
    void shouldReturn200WithAccountsMappingNicknameToName() {
        seedUser(USER);
        seedAccount(USER, "Cuenta de Ahorro BBVA");

        withAuth(USER)
            .when()
            .get("/api/v1/accounts")
            .then()
            .statusCode(200)
            .body("$", is(not(empty())))
            // DTO must contain "name", NOT "nickname" (design.md D6 mapper rename)
            .body("[0].name", notNullValue())
            .body("[0].id", notNullValue())
            .body("[0].type", notNullValue())
            .body("[0].currency", equalTo("COP"));
    }

    @Test
    void shouldReturn401WithoutAuth() {
        withNoAuth()
            .when()
            .get("/api/v1/accounts")
            .then()
            .statusCode(401)
            .contentType(containsString("application/problem+json"));
    }

    @Test
    void shouldNotExposeNicknameField() {
        seedUser(USER);
        seedAccount(USER, "My Checking");

        String body = withAuth(USER)
            .when()
            .get("/api/v1/accounts")
            .then()
            .statusCode(200)
            .extract()
            .asString();

        // The raw JSON body must not contain the key "nickname"
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("\"nickname\"");
    }
}
