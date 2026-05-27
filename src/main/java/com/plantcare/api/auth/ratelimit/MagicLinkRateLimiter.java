package com.plantcare.api.auth.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory rate limiter для {@code POST /api/v1/auth/email/request} (issue #88).
 * Тот же подход, что у admin-логина: Caffeine со временем жизни окна. Ключи —
 * IP и email — считаются независимо.
 */
@Component
public class MagicLinkRateLimiter {

    private final Cache<String, AtomicInteger> attempts;
    private final int maxAttempts;
    private final long windowSeconds;

    public MagicLinkRateLimiter(
            @Value("${plantcare.auth.magic-link.rate-limit.max-attempts:5}") int maxAttempts,
            @Value("${plantcare.auth.magic-link.rate-limit.window-seconds:300}") long windowSeconds) {
        this.maxAttempts = maxAttempts;
        this.windowSeconds = windowSeconds;
        this.attempts = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(windowSeconds))
                .maximumSize(50_000)
                .build();
    }

    public boolean isBlocked(String key) {
        AtomicInteger c = attempts.getIfPresent(key);
        return c != null && c.get() >= maxAttempts;
    }

    public void record(String key) {
        attempts.asMap()
                .computeIfAbsent(key, k -> new AtomicInteger(0))
                .incrementAndGet();
    }

    public long getWindowSeconds() {
        return windowSeconds;
    }
}
