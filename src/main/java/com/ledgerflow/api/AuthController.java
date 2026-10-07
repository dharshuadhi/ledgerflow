package com.ledgerflow.api;

import com.ledgerflow.security.JwtService;
import com.ledgerflow.security.SecurityConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Development-only token issuer.
 *
 * <p>Enabled by {@code ledgerflow.auth.dev-token-endpoint} (default true for
 * local development). <strong>Disable in any shared environment</strong> and
 * use a real OIDC provider instead — see docs/security.md.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth")
@ConditionalOnProperty(name = "ledgerflow.auth.dev-token-endpoint", havingValue = "true", matchIfMissing = true)
public class AuthController {

    private final JwtService jwt;
    private final List<SecurityConfig.DevUser> devUsers;

    public AuthController(JwtService jwt, List<SecurityConfig.DevUser> devUsers) {
        this.jwt = jwt;
        this.devUsers = devUsers;
    }

    public record TokenRequest(String username) {
    }

    public record TokenResponse(String tokenType, String accessToken, List<String> roles) {
    }

    @PostMapping("/token")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Issue a dev JWT for a demo user (local development only)")
    public TokenResponse token(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        SecurityConfig.DevUser user = devUsers.stream()
                .filter(u -> u.username().equals(username))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "unknown demo user"));
        return new TokenResponse("Bearer", jwt.issueToken(user.username(), user.roles()), user.roles());
    }
}
