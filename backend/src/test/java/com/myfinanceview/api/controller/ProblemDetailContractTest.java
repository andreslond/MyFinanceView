package com.myfinanceview.api.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Contract tests verifying RFC 7807 ProblemDetail format and zero-echo discipline (design.md D11).
 *
 * <p>Tests:
 * <ul>
 *   <li>404 with correct RFC 7807 fields, body does NOT echo the rejected UUID</li>
 *   <li>400 with correct fields, body does NOT echo the offending query-param value</li>
 *   <li>{@code Content-Type: application/problem+json} on all error responses</li>
 * </ul>
 */
class ProblemDetailContractTest extends ControllerIntegrationTestBase {

    private static final UUID USER_A = UUID.fromString("aa200000-0000-0000-0000-000000000001");
    private static final UUID USER_B = UUID.fromString("bb200000-0000-0000-0000-000000000001");

    private UUID txId;
    private UUID catA;
    private UUID catBPrivate;

    @BeforeEach
    void seed() {
        seedUser(USER_A);
        seedUser(USER_B);
        UUID accountA = seedAccount(USER_A, "Account A");
        catA = anySystemCategoryId();
        catBPrivate = seedCustomCategory(USER_B, "PrivateCatB", "Cat Privada B");
        txId = seedTransaction(USER_A, accountA, catA, "Merchant description", hoursAgo(2));
    }

    @Test
    void shouldReturn404WithoutEchoingRejectedUuid() {
        // PATCH with categoryId of another user → 404 (anti-IDOR D10)
        String rejectedUuid = catBPrivate.toString();
        String body = "{\"categoryId\":\"" + rejectedUuid + "\"}";

        String responseBody = withAuth(USER_A)
            .body(body)
            .when()
            .patch("/api/v1/transactions/" + txId + "/category")
            .then()
            .statusCode(404)
            .contentType(containsString("application/problem+json"))
            .body("type", containsString("/errors/not-found"))
            .body("title", equalTo("Resource not found"))
            .body("status", equalTo(404))
            .body("detail", equalTo("Resource not found"))
            .body("instance", notNullValue())
            .extract()
            .asString();

        // Zero-echo: rejected UUID must not appear anywhere in the response body
        assertThat(responseBody).doesNotContain(rejectedUuid);
    }

    @Test
    void shouldReturn400WithoutEchoingPageSize() {
        // pageSize=500 violates @Max(100) — the value must not be echoed in the body
        String responseBody = withAuth(USER_A)
            .queryParam("pageSize", "500")
            .when()
            .get("/api/v1/transactions")
            .then()
            .statusCode(400)
            .contentType(containsString("application/problem+json"))
            .body("type", containsString("/errors/bad-request"))
            .body("status", equalTo(400))
            .body("detail", equalTo("Validation failed"))
            .extract()
            .asString();

        // Zero-echo: the offending value must not appear in the response
        assertThat(responseBody).doesNotContain("\"500\"");
        assertThat(responseBody).doesNotContain(":500");
        assertThat(responseBody).doesNotContain("500");
    }

    @Test
    void shouldHaveContentTypeApplicationProblemJson() {
        // 401 from missing auth
        withNoAuth()
            .when()
            .get("/api/v1/accounts")
            .then()
            .statusCode(401)
            .contentType(containsString("application/problem+json"));

        // 404 from unknown tx
        UUID unknownTx = UUID.randomUUID();
        withAuth(USER_A)
            .body("{\"categoryId\":\"" + catA + "\"}")
            .when()
            .patch("/api/v1/transactions/" + unknownTx + "/category")
            .then()
            .statusCode(404)
            .contentType(containsString("application/problem+json"));

        // 400 from validation failure
        withAuth(USER_A)
            .queryParam("page", "0")
            .when()
            .get("/api/v1/transactions")
            .then()
            .statusCode(400)
            .contentType(containsString("application/problem+json"));
    }
}
