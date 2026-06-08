package com.myfinanceview.auth;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Tiny test-only controller used by {@code SecurityIntegrationTest} to verify that the JWT
 * authentication chain reaches a controller and that the {@code sub} claim has been parsed into
 * a {@link UUID} principal.
 *
 * <p>Lives under {@code src/test/java} so it is never bundled in the production jar.
 */
@RestController
public class ProbeController {

    @GetMapping("/api/v1/_probe")
    public String probe(@AuthenticationPrincipal UUID userId) {
        return userId == null ? "null" : userId.toString();
    }
}
