package com.plantcare.api.auth.ratelimit;

import com.plantcare.core.ratelimit.RedisRateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Rate limiter для {@code POST /api/v1/auth/guest} (issue #227).
 * Не более N новых гостей с одного IP за окно. Учитывается только реальное
 * создание нового гостя (не restore).
 *
 * <p>Issue #280 (эпик #277 фаза 2): мигрирован с per-instance Caffeine на общий
 * Redis-счётчик ({@link RedisRateLimiter}) — при нескольких инстансах лимит общий.
 * Caffeine — L1-fallback внутри {@link RedisRateLimiter} (fail-open). Публичный API
 * не изменился.
 */
@Component
public class GuestRateLimiter {

    private static final String SCOPE = "guest-new";

    private final RedisRateLimiter rateLimiter;
    private final int maxNew;
    private final long windowSeconds;

    public GuestRateLimiter(
            RedisRateLimiter rateLimiter,
            @Value("${plantcare.auth.guest.rate-limit.max-new:3}") int maxNew,
            @Value("${plantcare.auth.guest.rate-limit.window-seconds:3600}") long windowSeconds) {
        this.rateLimiter = rateLimiter;
        this.maxNew = maxNew;
        this.windowSeconds = windowSeconds;
    }

    /**
     * Проверяет, превышен ли лимит создания новых гостей для ключа (IP).
     * Не учитывает restore-сценарий — только реальное создание нового гостя.
     */
    public boolean isBlocked(String key) {
        return rateLimiter.isOverLimit(SCOPE, key, maxNew, Duration.ofSeconds(windowSeconds));
    }

    /**
     * Записывает факт создания нового гостя от ключа.
     */
    public void recordNew(String key) {
        rateLimiter.increment(SCOPE, key, Duration.ofSeconds(windowSeconds));
    }

    public long getWindowSeconds() {
        return windowSeconds;
    }
}
