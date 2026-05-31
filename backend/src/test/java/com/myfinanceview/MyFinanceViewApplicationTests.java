package com.myfinanceview;

import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasKey;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MyFinanceViewApplicationTests {

    @LocalServerPort
    int port;

    @Autowired
    ApplicationContext context;

    @Autowired
    Environment environment;

    @Test
    void shouldLoadSpringContextWhenStarting() {
        assertThat(context).isNotNull();
        assertThat(context.getBeanDefinitionCount()).isGreaterThan(0);
    }

    @Test
    void shouldReturn200WhenCallingHealth() {
        RestAssured.port = port;
        given()
            .when().get("/actuator/health")
            .then()
                .statusCode(200)
                .body("status", equalTo("UP"))
                .body("$", not(hasKey("components")))
                .body("$", not(hasKey("details")));
    }

    @Test
    void shouldHaveVirtualThreadsEnabledWhenStartingContext() {
        assertThat(environment.getProperty("spring.threads.virtual.enabled")).isEqualTo("true");
    }

    @Test
    void shouldHaveHikariMaximumPoolSizeOfFiveWhenStartingContext() {
        assertThat(environment.getProperty("spring.datasource.hikari.maximum-pool-size")).isEqualTo("5");
    }
}
