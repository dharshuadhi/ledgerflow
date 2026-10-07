package com.ledgerflow.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

/**
 * JWT issuance and validation.
 *
 * <p>Default mode signs with a local HS256 secret (dev/test). For production,
 * point {@code ledgerflow.auth.jwks-url} at an OIDC provider and swap this
 * service's validator — the rest of the security chain only depends on the
 * {@link UserDetails} it produces. See ADR-010 and docs/security.md.
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long ttlSeconds;

    public JwtService(
            @Value("${ledgerflow.auth.jwt-secret:dev-secret-change-me-in-production-please}") String secret,
            @Value("${ledgerflow.auth.jwt-ttl-seconds:3600}") long ttlSeconds) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlSeconds = ttlSeconds;
    }

    public String issueToken(String username, List<String> roles) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .claim("roles", roles)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(key)
                .compact();
    }

    /** Returns the authenticated user, or null if the token is invalid. */
    public UserDetails validate(String token) {
        try {
            Jws<Claims> jws = Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
            Claims claims = jws.getPayload();
            @SuppressWarnings("unchecked")
            List<String> roles = (List<String>) claims.getOrDefault("roles", List.of());
            List<SimpleGrantedAuthority> authorities = roles.stream()
                    .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                    .toList();
            return new User(claims.getSubject(), "", authorities);
        } catch (Exception e) {
            return null;
        }
    }
}
