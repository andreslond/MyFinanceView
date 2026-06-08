package com.myfinanceview.api.controller;

import com.myfinanceview.auth.JwksWireMockExtension;
import com.myfinanceview.auth.TestJwtFactory;
import com.myfinanceview.config.PostgresJooqIntegrationTestBase;
import com.myfinanceview.jooq.generated.enums.AccountType;
import com.myfinanceview.jooq.generated.enums.CategoryType;
import com.myfinanceview.jooq.generated.enums.TransactionType;
import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static com.myfinanceview.jooq.generated.Tables.*;
import static io.restassured.RestAssured.given;

/**
 * Base class for controller-level integration tests that need:
 * <ul>
 *   <li>A real Postgres (Testcontainers) via {@link PostgresJooqIntegrationTestBase}</li>
 *   <li>A WireMock JWKS endpoint via {@link JwksWireMockExtension}</li>
 *   <li>REST-assured configured with the random Spring Boot port</li>
 * </ul>
 *
 * <p>Subclasses annotate with {@code @SpringBootTest(webEnvironment = RANDOM_PORT)} and
 * {@code @ActiveProfiles({"test", "service"})}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "service"})
abstract class ControllerIntegrationTestBase extends PostgresJooqIntegrationTestBase {

    static final String ISSUER = "https://issuer.controller-test.local/auth/v1";

    @RegisterExtension
    static final JwksWireMockExtension jwks = new JwksWireMockExtension();

    static TestJwtFactory tokens;

    @DynamicPropertySource
    static void registerAuthProps(DynamicPropertyRegistry reg) {
        reg.add("app.auth.supabase.jwks-uri", jwks::jwksUrl);
        reg.add("app.auth.supabase.issuer", () -> ISSUER);
        reg.add("app.auth.supabase.audience", () -> "authenticated");
    }

    @LocalServerPort
    int port;

    @Autowired
    DSLContext dsl;

    @BeforeEach
    void setUpRestAssured() {
        if (tokens == null) {
            tokens = new TestJwtFactory(ISSUER);
        }
        jwks.stubKeys(tokens.publicJwk());
        RestAssured.port = port;
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
    }

    /** REST-assured spec with a valid Bearer token for the given userId. */
    RequestSpecification withAuth(UUID userId) {
        return given()
            .header("Authorization", "Bearer " + tokens.valid(userId))
            .header("Content-Type", "application/json");
    }

    /** REST-assured spec with no Authorization header. */
    RequestSpecification withNoAuth() {
        return given().header("Content-Type", "application/json");
    }

    // ---- Seeding helpers (mirrors TransactionServiceTestBase) ---

    void seedUser(UUID userId) {
        dsl.execute("INSERT INTO auth.users(id) VALUES (?) ON CONFLICT DO NOTHING", userId);
    }

    UUID anyBankId() {
        return dsl.select(BANKS.ID).from(BANKS).limit(1).fetchOne(BANKS.ID);
    }

    UUID anySystemCategoryId() {
        return dsl.select(CATEGORIES.ID)
            .from(CATEGORIES)
            .where(CATEGORIES.USER_ID.isNull())
            .limit(1)
            .fetchOne(CATEGORIES.ID);
    }

    UUID anotherSystemCategoryId(UUID different) {
        return dsl.select(CATEGORIES.ID)
            .from(CATEGORIES)
            .where(CATEGORIES.USER_ID.isNull())
            .and(CATEGORIES.ID.notEqual(different))
            .limit(1)
            .fetchOne(CATEGORIES.ID);
    }

    UUID seedCustomCategory(UUID userId, String englishName, String displayName) {
        UUID id = UUID.randomUUID();
        dsl.insertInto(CATEGORIES)
            .set(CATEGORIES.ID, id)
            .set(CATEGORIES.USER_ID, userId)
            .set(CATEGORIES.NAME, englishName)
            .set(CATEGORIES.DISPLAY_NAME, displayName)
            .set(CATEGORIES.TYPE, CategoryType.expense)
            .execute();
        return id;
    }

    UUID seedAccount(UUID userId, String nickname) {
        UUID id = UUID.randomUUID();
        dsl.insertInto(ACCOUNTS)
            .set(ACCOUNTS.ID, id)
            .set(ACCOUNTS.USER_ID, userId)
            .set(ACCOUNTS.BANK_ID, anyBankId())
            .set(ACCOUNTS.TYPE, AccountType.checking)
            .set(ACCOUNTS.CURRENCY, "COP")
            .set(ACCOUNTS.NICKNAME, nickname)
            .execute();
        return id;
    }

    UUID seedTransaction(UUID userId, UUID accountId, UUID categoryId, String description,
                         OffsetDateTime occurredAt) {
        UUID id = UUID.randomUUID();
        var step = dsl.insertInto(TRANSACTIONS)
            .set(TRANSACTIONS.ID, id)
            .set(TRANSACTIONS.USER_ID, userId)
            .set(TRANSACTIONS.ACCOUNT_ID, accountId)
            .set(TRANSACTIONS.TYPE, TransactionType.debit_purchase)
            .set(TRANSACTIONS.AMOUNT, new BigDecimal("123.45"))
            .set(TRANSACTIONS.CURRENCY, "COP")
            .set(TRANSACTIONS.AMOUNT_BASE_CURRENCY, new BigDecimal("123.45"))
            .set(TRANSACTIONS.DESCRIPTION, description);
        if (categoryId != null) step = step.set(TRANSACTIONS.CATEGORY_ID, categoryId);
        step.set(TRANSACTIONS.OCCURRED_AT, occurredAt);
        step.execute();
        return id;
    }

    static OffsetDateTime hoursAgo(int hours) {
        return OffsetDateTime.now(ZoneOffset.UTC).minusHours(hours);
    }
}
