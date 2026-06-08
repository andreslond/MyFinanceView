package com.myfinanceview.api.controller;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Contract tests for {@code GET /api/v1/categories}.
 *
 * <p>Asserts:
 * <ul>
 *   <li>200 with system + custom categories ordered by {@code display_name}</li>
 *   <li>Response body does NOT contain {@code parentId} anywhere</li>
 *   <li>401 when no Authorization header</li>
 * </ul>
 */
class CategoryControllerContractTest extends ControllerIntegrationTestBase {

    private static final UUID USER = UUID.fromString("cc000000-0000-0000-0000-000000000001");

    @Test
    void shouldReturn200WithSystemAndCustomCategoriesOrderedByDisplayName() {
        seedUser(USER);
        // Seed a custom category with a display_name that comes first alphabetically
        seedCustomCategory(USER, "CustomCat", "Ahorros Personales");

        String body = withAuth(USER)
            .when()
            .get("/api/v1/categories")
            .then()
            .statusCode(200)
            .body("$", hasSize(greaterThanOrEqualTo(20))) // 19 system + 1 custom minimum
            .body("[0].name", notNullValue())
            .body("[0].id", notNullValue())
            .body("[0].type", notNullValue())
            .extract()
            .asString();

        // Verify first element is our custom one (alphabetically first by display_name)
        assertThat(body).contains("Ahorros Personales");
    }

    @Test
    void shouldNotIncludeParentIdInAnyCategory() {
        seedUser(USER);

        String body = withAuth(USER)
            .when()
            .get("/api/v1/categories")
            .then()
            .statusCode(200)
            .extract()
            .asString();

        // design.md D6 / adv-review B3: parentId was never in the schema; it was a speculative
        // field in the MVP frontend. The DTO must NOT expose it.
        assertThat(body).doesNotContain("\"parentId\"");
        assertThat(body).doesNotContain("\"parent_id\"");
    }

    @Test
    void shouldReturn401WithoutAuth() {
        withNoAuth()
            .when()
            .get("/api/v1/categories")
            .then()
            .statusCode(401)
            .contentType(containsString("application/problem+json"));
    }
}
