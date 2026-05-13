package com.myfinanceview;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.util.Arrays;

@SpringBootApplication
public class MyFinanceViewApplication {

    private static final Logger log = LoggerFactory.getLogger(MyFinanceViewApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(MyFinanceViewApplication.class, args);
    }

    /**
     * One-shot startup banner so a developer (or CI log scraper) can confirm the active profile,
     * JDK, and virtual-thread switch without grepping for the right line.
     */
    @Bean
    ApplicationRunner startupBanner(Environment env) {
        return args -> {
            String[] activeProfiles = env.getActiveProfiles();
            String profile = activeProfiles.length == 0 ? "default" : String.join(",", Arrays.asList(activeProfiles));
            String javaVersion = System.getProperty("java.specification.version");
            String virtualThreads = env.getProperty("spring.threads.virtual.enabled", "false");
            log.info("MyFinanceView starting · profile={} · java={} · virtualThreads={}",
                profile, javaVersion, virtualThreads);
        };
    }
}
