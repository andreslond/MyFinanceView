package com.myfinanceview.config;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Provides a no-op {@link DSLContext} bean for the {@code test} profile. The profile excludes
 * {@code DataSourceAutoConfiguration} and {@code JooqAutoConfiguration} (see
 * application-test.yml + scaffolding D7) so the Spring context cannot auto-wire a real
 * {@link DSLContext}. The repositories added in §5 of {@code backend-mvp-readonly}
 * (Account/Category/Transaction/Merchant) declare {@link DSLContext} as a constructor
 * dependency, so the absence of any bean breaks every {@code @SpringBootTest}.
 *
 * <p>The repository tests that actually exercise SQL ({@code *RepositoryIsolationTest},
 * {@code *VisibilityGuardTest}, {@code MerchantRepositoryTest}) build their own
 * {@link DSLContext} from a live Testcontainers connection and instantiate the repository
 * directly — they do NOT load the Spring context, so they never see this bean.
 *
 * <p>For Spring-context tests that DO load the full context (health/security/actuator), the
 * disconnected {@link DSLContext} returned here is fine because those tests never hit the
 * repository methods. If a future Spring-context test needs real SQL, it should override this
 * bean with a Testcontainers-backed {@link DSLContext} via {@code @DynamicPropertySource} +
 * {@code spring.datasource.*} and re-enable the auto-configurations in its own test slice.
 */
@TestConfiguration
public class TestJooqConfig {

    @Bean
    public DSLContext dslContext() {
        return DSL.using(SQLDialect.POSTGRES);
    }
}
