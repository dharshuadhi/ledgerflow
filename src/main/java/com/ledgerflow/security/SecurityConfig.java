package com.ledgerflow.security;

import com.ledgerflow.api.web.CorrelationIdFilter;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless API security.
 *
 * <p>Roles: ADMIN (everything), OPERATOR (move money, run ops), AUDITOR
 * (read-only), SERVICE (automation). Enforced with method security on the
 * controllers; this chain handles authentication mechanics.
 *
 * <p>The settlement webhook is intentionally <em>not</em> JWT-protected: its
 * credential is the HMAC signature (like Stripe), verified before any
 * database access.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtFilter;
    private final CorrelationIdFilter correlationIdFilter;
    private final RateLimitFilter rateLimitFilter;
    private final boolean devTokenEndpoint;

    public SecurityConfig(JwtAuthFilter jwtFilter, CorrelationIdFilter correlationIdFilter,
                          RateLimitFilter rateLimitFilter,
                          @Value("${ledgerflow.auth.dev-token-endpoint:true}") boolean devTokenEndpoint) {
        this.jwtFilter = jwtFilter;
        this.correlationIdFilter = correlationIdFilter;
        this.rateLimitFilter = rateLimitFilter;
        this.devTokenEndpoint = devTokenEndpoint;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .headers(h -> h
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'"))
                        .frameOptions(f -> f.deny()))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers("/actuator/health/**", "/actuator/info").permitAll();
                    auth.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll();
                    // HMAC-signed; JWT would add nothing.
                    auth.requestMatchers(HttpMethod.POST, "/api/v1/settlements/webhook").permitAll();
                    if (devTokenEndpoint) {
                        auth.requestMatchers(HttpMethod.POST, "/api/v1/auth/token").permitAll();
                    }
                    auth.anyRequest().authenticated();
                })
                .addFilterBefore(correlationIdFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(rateLimitFilter, JwtAuthFilter.class);
        return http.build();
    }

    /** Demo users for the dev token endpoint. Passwords come from env in real use. */
    @Bean
    public List<DevUser> devUsers(
            @Value("${ledgerflow.auth.dev-users:admin:ADMIN,operator:OPERATOR,auditor:AUDITOR,service:SERVICE}")
            String spec) {
        return java.util.Arrays.stream(spec.split(","))
                .map(s -> s.split(":"))
                .filter(p -> p.length == 2)
                .map(p -> new DevUser(p[0], List.of(p[1])))
                .toList();
    }

    public record DevUser(String username, List<String> roles) {
    }
}
