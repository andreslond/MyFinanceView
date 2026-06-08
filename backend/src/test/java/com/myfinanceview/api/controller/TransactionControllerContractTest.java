package com.myfinanceview.api.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Contract tests for {@code GET /api/v1/transactions} and
 * {@code PATCH /api/v1/transactions/{id}/category}.
 *
 * <p>Covers:
 * <ul>
 *   <li>200 PageDTO shape (rows, page, pageSize, hasMore), amount as quoted string</li>
 *   <li>400 for pageSize > 100, page < 1, accountId malformed</li>
 *   <li>404 when PATCH targets non-existent tx</li>
 *   <li>404 (anti-IDOR) when PATCH categoryId belongs to another user</li>
 *   <li>200 idempotent PATCH (same categoryId)</li>
 * </ul>
 */
class TransactionControllerContractTest extends ControllerIntegrationTestBase {

    private static final UUID USER_A = UUID.fromString("aa100000-0000-0000-0000-000000000001");
    private static final UUID USER_B = UUID.fromString("bb100000-0000-0000-0000-000000000001");

    private UUID accountA;
    private UUID catA;
    private UUID catB;  // private to user B
    private UUID txId;

    @BeforeEach
    void seed() {
        seedUser(USER_A);
        seedUser(USER_B);
        accountA = seedAccount(USER_A, "Main Account");
        catA = anySystemCategoryId();
        catB = seedCustomCategory(USER_B, "PrivateCatB", "Categoría Privada B");
        txId = seedTransaction(USER_A, accountA, catA, "NETFLIX.COM *1234", hoursAgo(1));
    }

    @Test
    void shouldReturn200WithPageDtoShapeWhenAuthenticated() {
        String body = withAuth(USER_A)
            .when()
            .get("/api/v1/transactions")
            .then()
            .statusCode(200)
            .body("rows", notNullValue())
            .body("page", equalTo(1))
            .body("pageSize", equalTo(25))
            .body("hasMore", is(false))
            .extract()
            .asString();

        // Amount must be a quoted string (e.g. "123.45"), not a JSON number
        assertThat(body).contains("\"123.45\"");
    }

    @Test
    void shouldReturn400WhenPageSizeAbove100() {
        String body = withAuth(USER_A)
            .queryParam("pageSize", "500")
            .when()
            .get("/api/v1/transactions")
            .then()
            .statusCode(400)
            .contentType(containsString("application/problem+json"))
            .extract()
            .asString();

        // Zero-echo: the offending value "500" must not appear in the body (design.md D11)
        assertThat(body).doesNotContain("\"500\"");
        assertThat(body).doesNotContain("500");
    }

    @Test
    void shouldReturn400WhenPageZero() {
        withAuth(USER_A)
            .queryParam("page", "0")
            .when()
            .get("/api/v1/transactions")
            .then()
            .statusCode(400)
            .contentType(containsString("application/problem+json"));
    }

    @Test
    void shouldReturn400WhenAccountIdMalformed() {
        withAuth(USER_A)
            .queryParam("accountId", "not-a-uuid")
            .when()
            .get("/api/v1/transactions")
            .then()
            .statusCode(400);
    }

    @Test
    void shouldReturn404WhenPatchTxNotExists() {
        UUID nonExistent = UUID.randomUUID();
        String body = "{\"categoryId\":\"" + catA + "\"}";

        withAuth(USER_A)
            .body(body)
            .when()
            .patch("/api/v1/transactions/" + nonExistent + "/category")
            .then()
            .statusCode(404)
            .contentType(containsString("application/problem+json"));
    }

    @Test
    void shouldReturn404WhenPatchCategoryIdBelongsToAnotherUser() {
        // USER_A tries to assign category owned by USER_B — anti-IDOR (design.md D10)
        String body = "{\"categoryId\":\"" + catB + "\"}";
        String catBStr = catB.toString();

        String responseBody = withAuth(USER_A)
            .body(body)
            .when()
            .patch("/api/v1/transactions/" + txId + "/category")
            .then()
            .statusCode(404)
            .contentType(containsString("application/problem+json"))
            .extract()
            .asString();

        // Zero-echo: the rejected UUID must NOT appear in the response body
        assertThat(responseBody).doesNotContain(catBStr);
    }

    @Test
    void shouldReturn200OnIdempotentPatch() {
        // PATCH with the same categoryId that the transaction already has — should be a no-op
        String body = "{\"categoryId\":\"" + catA + "\"}";

        String body1 = withAuth(USER_A)
            .body(body)
            .when()
            .patch("/api/v1/transactions/" + txId + "/category")
            .then()
            .statusCode(200)
            .extract()
            .asString();

        // Second identical PATCH
        String body2 = withAuth(USER_A)
            .body(body)
            .when()
            .patch("/api/v1/transactions/" + txId + "/category")
            .then()
            .statusCode(200)
            .extract()
            .asString();

        // Both responses contain same updatedAt (no DB write on idempotent call)
        // Parse updatedAt from both responses and compare
        com.jayway.jsonpath.DocumentContext doc1 = com.jayway.jsonpath.JsonPath.parse(body1);
        com.jayway.jsonpath.DocumentContext doc2 = com.jayway.jsonpath.JsonPath.parse(body2);
        String updatedAt1 = doc1.read("$.updatedAt");
        String updatedAt2 = doc2.read("$.updatedAt");
        assertThat(updatedAt1).isEqualTo(updatedAt2);
    }
}
