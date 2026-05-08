package com.plantcare.bot.admin.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.plantcare.bot.admin.config.AdminProperties;
import com.plantcare.bot.admin.config.AdminSecurityConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory rate limiter на Caffeine. Ключ — IP, значение — счётчик неудач.
 * TTL = window-seconds. Bucket4j для одного админа избыточен.
 */
@Component
@ConditionalOnExpression(AdminSecurityConfig.ADMIN_ENABLED_EXPR)
public class LoginRateLimiter {

    private final Cache<String, AtomicInteger> attempts;
    private final int maxAttempts;

    public LoginRateLimiter(AdminProperties props) {
        this.maxAttempts = props.getRateLimit().getMaxAttempts();
        this.attempts = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(props.getRateLimit().getWindowSeconds()))
                .maximumSize(10_000)
                .build();
    }

    public boolean isBlocked(String ip) {
        AtomicInteger c = attempts.getIfPresent(ip);
        return c != null && c.get() >= maxAttempts;
    }

    public void recordFailure(String ip) {
        attempts.asMap()
                .computeIfAbsent(ip, k -> new AtomicInteger(0))
                .incrementAndGet();
    }

    public void reset(String ip) {
        attempts.invalidate(ip);
    }
}
