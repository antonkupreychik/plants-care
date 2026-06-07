package com.plantcare.api.auth.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory rate limiter для {@code POST /api/v1/auth/guest} (issue #227).
 * Не более 3 новых гостей с одного IP за час. Caffeine со sliding-window
 * через expireAfterWrite (аналогично {@link MagicLinkRateLimiter}).
 */
@Component
public class GuestRateLimiter {

    private final Cache<String, AtomicInteger> attempts;
    private final int maxNew;
    private final long windowSeconds;

    public GuestRateLimiter(
            @Value("${plantcare.auth.guest.rate-limit.max-new:3}") int maxNew,
            @Value("${plantcare.auth.guest.rate-limit.window-seconds:3600}") long windowSeconds) {
        this.maxNew = maxNew;
        this.windowSeconds = windowSeconds;
        this.attempts = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(windowSeconds))
                .maximumSize(50_000)
                .build();
    }

    /**
     * Проверяет, превышен ли лимит создания новых гостей для ключа (IP).
     * Не учитывает restore-сценарий — только реальное создание нового гостя.
     */
    public boolean isBlocked(String key) {
        AtomicInteger c = attempts.getIfPresent(key);
        return c != null && c.get() >= maxNew;
    }

    /**
     * Записывает факт создания нового гостя от ключа.
     */
    public void recordNew(String key) {
        attempts.asMap()
                .computeIfAbsent(key, k -> new AtomicInteger(0))
                .incrementAndGet();
    }

    public long getWindowSeconds() {
        return windowSeconds;
    }
}
