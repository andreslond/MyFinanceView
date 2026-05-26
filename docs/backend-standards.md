# Backend Standards — MyFinanceView

> Specializes [base-standards.md](base-standards.md) for the Java/Spring Boot/jOOQ backend. If a rule here conflicts with base-standards, base-standards wins.

---

## 1. Stack

| Concern | Choice | Version | Why |
|---|---|---|---|
| Language | Java | 25 | Virtual Threads (Loom), Records, Pattern matching, Sealed types |
| Framework | Spring Boot | 3.4+ | Native Loom support, GraalVM-ready, Spring 6 baseline |
| Data access | jOOQ | 3.19+ | Type-safe SQL from existing schema; no ORM magic |
| DB | PostgreSQL (Supabase) | 17.6 | Existing, schema `myfinance` |
| Auth | Supabase JWT + Spring Security | — | JWT validated against Supabase JWKS |
| Build | Maven | 3.9+ | Single-developer predictability |
| Tests | JUnit 5, AssertJ, REST-assured, Testcontainers | — | See [base-standards §5](base-standards.md) |
| API doc | OpenAPI 3.1 YAML, hand-written | — | Source of truth, no codegen |

**Explicitly NOT used (introducing any requires SPEC.md amendment):**
- JPA / Hibernate
- WebFlux / Reactor
- Spring Cloud, Eureka, Config Server
- Kafka, RabbitMQ, ActiveMQ

**Lombok**: allowed. Records remain the default for DTOs; reach for Lombok (`@Getter`/`@Setter`/`@Builder`/`@Slf4j`) when it genuinely cuts boilerplate on mutable entities or complex builders. Avoid `@Data` — it hides too much.

## 2. Package Layout (modular by domain)

```
com.myfinanceview/
├── MyFinanceViewApplication.java   ← @SpringBootApplication
├── api/
│   ├── controller/                 ← @RestController, thin
│   ├── dto/                        ← Records (request/response)
│   └── exception/                  ← @ControllerAdvice → ProblemDetail
├── domain/
│   ├── transaction/                ← TransactionService, mappers, business rules
│   ├── category/                   ← CategoryService
│   ├── merchant/                   ← MerchantService + feedback loop
│   ├── billing/                    ← Billing cycle calculator
│   └── savings/                    ← SavingsGoalService, SavingsGoalCalculator
├── db/
│   ├── repository/                 ← interfaces (Spring-agnostic where possible)
│   └── jooq/                       ← jOOQ implementations of repositories
└── config/
    ├── SecurityConfig.java         ← JWT validation, CORS
    ├── JooqConfig.java             ← DSLContext, dialect
    └── OpenApiConfig.java          ← springdoc setup
```

**Rules:**
- Controllers do **only**: deserialize input → extract `user_id` from JWT → call service → return DTO. No business logic.
- Services live under `domain/{bounded-context}/`. They depend on repository **interfaces** in `db/repository/`, not on jOOQ types.
- jOOQ implementations live under `db/jooq/`. The generated code (from codegen) lives in `generated/` and is gitignored when possible (regenerated from schema).
- Cross-domain calls go service → service. Never controller → another service of a different domain.

## 3. jOOQ Conventions

- **Codegen** from the live `myfinance` schema in Supabase (read-only role). Output to `generated/` or `target/generated-sources/`.
- Regenerate after every schema migration. Commit only **if** the generated code is committed (decide once and stick to it).
- Use the DSL with `record()`, `into(Record.class)`, and explicit `select(field1, field2)` — avoid `selectFrom().fetch()` blanket queries.
- For complex aggregations (corte de facturación, breakdown por categoría), prefer jOOQ over `@Query` JPQL-style strings; jOOQ shines exactly here.
- Pagination: jOOQ `limit(size).offset(page * size)` with a stable ORDER BY. Default `size = 50`, max `100`.

## 4. Spring Security

- One filter chain (`SecurityFilterChain`) validating Supabase JWT (`Authorization: Bearer ...`) against the public JWKS endpoint.
- Permitted without auth: `/actuator/health`, `/actuator/info`, `/v3/api-docs/**` (Swagger).
- Internal webhooks (e.g. `POST /api/v1/feedback/transaction` from n8n) protected by `X-Webhook-Secret` header (constant-time comparison).
- The validated JWT's `sub` claim is the canonical `user_id`. Available via `@AuthenticationPrincipal` or `SecurityContextHolder`.

## 5. Error Handling

- `@ControllerAdvice` class converts known exceptions to `ProblemDetail` (RFC 7807).
- Mapping table:
  | Exception | Status | type URI |
  |---|---|---|
  | `IllegalArgumentException` | 400 | `/errors/bad-request` |
  | `NotFoundException` (custom) | 404 | `/errors/not-found` |
  | `UnauthorizedException` | 401 | `/errors/unauthorized` |
  | `ForbiddenException` | 403 | `/errors/forbidden` |
  | (anything else) | 500 | `/errors/internal` (no detail leak) |
- Validation errors (`@Valid`) return 400 with field-level `errors` map inside `ProblemDetail.properties`.

## 6. Logging

- SLF4J + Logback (Spring Boot default).
- `INFO` for state changes; `DEBUG` for flow detail; `ERROR` only for unexpected exceptions.
- **Never** log: JWTs, Supabase keys, passwords, full email bodies (the `raw_payload` JSONB), card numbers.
- Structured logs preferred: include `user_id`, `request_id`, `endpoint`. Use MDC.

## 7. Configuration

- `application.yml` for defaults; `application-{profile}.yml` for overrides (`local`, `test`, `prod`).
- All secrets via env vars (`${SUPABASE_JWT_SECRET}`).
- Never commit `application-prod.yml` with real values.
- See [development-guide.md §Environment](development-guide.md) for the full env var list.

## 8. Performance Defaults

- Connection pool: HikariCP, max 5 (Supabase free tier limit; document if raised).
- Virtual Threads: `spring.threads.virtual.enabled=true`.
- HTTP timeouts: 5s connect, 30s read for outbound calls (none for now, but document if added).
- Pagination required on any list endpoint; default size 50, max 100.

## 9. Testing Recipes

### Contract test (REST-assured)
```java
@Test
void shouldReturn200WhenFetchingAccounts() {
    given()
        .header("Authorization", "Bearer " + validJwt())
        .when().get("/api/v1/accounts")
        .then().statusCode(200).body("$", hasSize(greaterThan(0)));
}
```

### Integration test (Testcontainers)
```java
@SpringBootTest
@Testcontainers
class TransactionIntegrationTest {
    @Container static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17")
        .withInitScript("migrations/V001__initial_schema.sql"); // + V002, V003
    // …
}
```

### Unit test (pure logic)
```java
@Test
void shouldComputeMonthlySuggestedWithHalfEven() {
    var saved = new BigDecimal("100.00");
    var target = new BigDecimal("400.00");
    var months = 3;
    var result = SavingsGoalCalculator.monthlySuggested(target, saved, months);
    assertThat(result).isEqualByComparingTo("100.00");
}
```

## 10. Common Anti-Patterns to Reject

- `Optional<X>` as a method parameter (use overloads or default values).
- `@Autowired` field injection (use constructor injection — Records-ish style).
- Catching `Exception` to swallow. If you don't know what could throw, don't catch.
- `Map<String, Object>` for structured data — use a Record.
- `LocalDateTime` for timestamps with timezone meaning (use `OffsetDateTime`).
- Mocking the database — see [base-standards §5](base-standards.md).
- Building SQL with string concatenation, even via jOOQ's escape hatches.
