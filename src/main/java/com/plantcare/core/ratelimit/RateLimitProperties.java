package com.plantcare.core.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Issue #280, эпик #277 фаза 2: настройки распределённого rate-limit на Redis.
 *
 * <p>Сам лимит (окно/количество) задаётся на каждой точке вызова (admin-login,
 * magic-link, guest) — он живёт в соответствующих {@code @ConfigurationProperties}
 * этих фич. Здесь только инфраструктурные тумблеры распределённого счётчика.
 *
 * <p>Префикс ключей берётся из {@link com.plantcare.core.config.RedisProperties#keyPrefix()}
 * (общий namespacing), здесь не дублируется.
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(

        /**
         * Использовать ли Redis как общий (shared) счётчик. Если {@code false} —
         * считаем только локально через Caffeine L1 (поведение до issue #280,
         * per-instance). Дефолт {@code true}.
         */
        Boolean redisEnabled,

        /**
         * Максимальный размер локального L1-кэша Caffeine (fallback при недоступном
         * Redis). Дефолт 100_000 ключей.
         */
        Long l1MaxKeys

) {

    public RateLimitProperties {
        if (redisEnabled == null) redisEnabled = Boolean.TRUE;
        if (l1MaxKeys == null || l1MaxKeys <= 0) l1MaxKeys = 100_000L;
    }
}
