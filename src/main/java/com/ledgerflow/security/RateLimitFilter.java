package com.ledgerflow.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Token-bucket rate limiter backed by Redis.
 *
 * <p>The bucket state lives in Redis (atomic Lua update) so limits hold across
 * replicas. Keyed by authenticated principal, falling back to client IP for
 * anonymous calls. If Redis is unreachable the filter <strong>fails open</strong>
 * — rate limiting must never take down money movement; the outage is logged
 * and metered.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final String LUA = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill_per_sec = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local data = redis.call('HMGET', key, 'tokens', 'ts')
            local tokens = tonumber(data[1])
            if tokens == nil then tokens = capacity end
            local ts = tonumber(data[2])
            if ts == nil then ts = now end
            local elapsed = math.max(0, now - ts)
            tokens = math.min(capacity, tokens + elapsed * refill_per_sec)
            local allowed = 0
            if tokens >= 1 then
              tokens = tokens - 1
              allowed = 1
            end
            redis.call('HMSET', key, 'tokens', tokens, 'ts', now)
            redis.call('EXPIRE', key, 300)
            return allowed
            """;

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> script;
    private final double permitsPerSecond;
    private final int capacity;

    public RateLimitFilter(StringRedisTemplate redis,
                           @Value("${ledgerflow.ratelimit.per-minute:120}") int perMinute) {
        this.redis = redis;
        this.script = new DefaultRedisScript<>(LUA, Long.class);
        this.permitsPerSecond = perMinute / 60.0;
        this.capacity = Math.max(perMinute, 10);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // Webhooks carry their own HMAC auth and must never be throttled away.
        if (request.getRequestURI().startsWith("/api/v1/settlements/webhook")) {
            chain.doFilter(request, response);
            return;
        }
        String subject = "ip:" + request.getRemoteAddr();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            subject = "user:" + auth.getName();
        }
        if (!allow(subject)) {
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"type\":\"https://ledgerflow.dev/problems/rate-limited\","
                            + "\"title\":\"Rate limit exceeded\",\"status\":429}");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean allow(String subject) {
        try {
            Long allowed = redis.execute(script, List.of("ratelimit:" + subject),
                    String.valueOf(capacity),
                    String.valueOf(permitsPerSecond),
                    String.valueOf(Instant.now().toEpochMilli() / 1000.0));
            return allowed != null && allowed == 1L;
        } catch (Exception e) {
            log.warn("rate limiter Redis unavailable, failing open", e);
            return true;
        }
    }
}
