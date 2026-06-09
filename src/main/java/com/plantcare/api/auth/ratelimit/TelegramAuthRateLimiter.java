package com.plantcare.api.auth.ratelimit;

import com.plantcare.core.ratelimit.RedisRateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Issue #318: rate limiter для {@code POST /api/v1/auth/telegram/start} и
 * {@code /verify}. По образцу {@link MagicLinkRateLimiter}/{@code GuestRateLimiter}:
 * общий Redis-счётчик ({@link RedisRateLimiter}) с fail-open + Caffeine L1.
 *
 * <p>Ключ — IP клиента; start и verify считаются под одним скоупом
 * ({@value #SCOPE}), чтобы абьюз одного эндпоинта не обходился переключением
 * на другой.
 */
@Component
public class TelegramAuthRateLimiter {

    private static final String SCOPE = "telegram-auth";

    private final RedisRateLimiter rateLimiter;
    private final int maxAttempts;
    private final long windowSeconds;

    public TelegramAuthRateLimiter(
            RedisRateLimiter rateLimiter,
            @Value("${plantcare.auth.telegram.rate-limit.max-attempts:10}") int maxAttempts,
            @Value("${plantcare.auth.telegram.rate-limit.window-seconds:300}") long windowSeconds) {
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
