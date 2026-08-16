package com.plantcare.admin.ratelimit;

import com.plantcare.admin.config.AdminProperties;
import com.plantcare.admin.config.AdminSecurityConfig;
import com.plantcare.core.ratelimit.RedisRateLimiter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Rate limiter админ-логина. Ключ — IP, значение — счётчик неудачных попыток,
 * TTL = window-seconds.
 *
 * <p>Issue #280 (эпик #277 фаза 2): мигрирован с per-instance Caffeine на общий
 * Redis-счётчик ({@link RedisRateLimiter}) — при нескольких инстансах лимит брутфорса
 * админ-логина общий, а не ×N. Caffeine — L1-fallback внутри {@link RedisRateLimiter}
 * (fail-open при недоступном Redis). Публичный API не изменился.
 */
@Component
@ConditionalOnExpression(AdminSecurityConfig.ADMIN_ENABLED_EXPR)
public class LoginRateLimiter {

    private static final String SCOPE = "admin-login";

    private final RedisRateLimiter rateLimiter;
    private final int maxAttempts;
    private final Duration window;

    public LoginRateLimiter(AdminProperties props, RedisRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
        this.maxAttempts = props.getRateLimit().getMaxAttempts();
        this.window = Duration.ofSeconds(props.getRateLimit().getWindowSeconds());
    }

    public boolean isBlocked(String ip) {
        return rateLimiter.isOverLimit(SCOPE, ip, maxAttempts, window);
    }

    public void recordFailure(String ip) {
        rateLimiter.increment(SCOPE, ip, window);
    }

    public void reset(String ip) {
        rateLimiter.reset(SCOPE, ip);
    }
}
