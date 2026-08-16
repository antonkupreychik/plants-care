package com.plantcare.api.auth.ratelimit;

import com.plantcare.core.ratelimit.RedisRateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Rate limiter для {@code POST /api/v1/auth/email/request} (issue #88).
 * Ключи — IP и email — считаются независимо.
 *
 * <p>Issue #280 (эпик #277 фаза 2): мигрирован с per-instance Caffeine на общий
 * Redis-счётчик ({@link RedisRateLimiter}) — при нескольких инстансах лимит общий,
 * а не ×N. Caffeine сохранён как L1-fallback внутри {@link RedisRateLimiter}
 * (fail-open при недоступном Redis). Публичный API метода не изменился.
 */
@Component
public class MagicLinkRateLimiter {

    private static final String SCOPE = "magic-link";

    private final RedisRateLimiter rateLimiter;
    private final int maxAttempts;
    private final long windowSeconds;

    public MagicLinkRateLimiter(
            RedisRateLimiter rateLimiter,
            @Value("${plantcare.auth.magic-link.rate-limit.max-attempts:5}") int maxAttempts,
            @Value("${plantcare.auth.magic-link.rate-limit.window-seconds:300}") long windowSeconds) {
        this.rateLimiter = rateLimiter;
        this.maxAttempts = maxAttempts;
        this.windowSeconds = windowSeconds;
    }

    public boolean isBlocked(String key) {
        return rateLimiter.isOverLimit(SCOPE, key, maxAttempts, Duration.ofSeconds(windowSeconds));
    }

    public void record(String key) {
        rateLimiter.increment(SCOPE, key, Duration.ofSeconds(windowSeconds));
    }

    public long getWindowSeconds() {
        return windowSeconds;
    }
}
