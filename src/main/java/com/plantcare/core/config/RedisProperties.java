package com.plantcare.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Issue #278, эпик #277 фаза 0: настройки подключения к Redis.
 *
 * <p>Все чувствительные значения берутся из env-переменных (Railway reference variables).
 * В репо хранятся только безопасные дефолты для локального запуска.
 *
 * <p>Railway-переменные:
 * <ul>
 *   <li>{@code REDIS_URL} — полный URL в формате {@code redis://[:password@]host:port[/db]}
 *       или {@code rediss://...} (TLS), инжектится как reference variable из Redis-сервиса.
 *       Внутренняя сеть Railway: хост заканчивается на {@code .railway.internal}.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "app.redis")
public record RedisProperties(

        /**
         * Таймаут подключения к Redis. По умолчанию 2 секунды.
         * Короткий таймаут = быстрый фейл при недоступном Redis, без зависания приложения.
         */
        Duration connectTimeout,

        /**
         * Таймаут команды Redis (read timeout). По умолчанию 1 секунда.
         */
        Duration commandTimeout,

        /**
         * Префикс для всех ключей приложения. Обеспечивает namespacing при shared Redis.
         * Дефолт: {@code pc:}
         */
        String keyPrefix

) {

    public RedisProperties {
        if (connectTimeout == null) connectTimeout = Duration.ofSeconds(2);
        if (commandTimeout == null) commandTimeout = Duration.ofSeconds(1);
        if (keyPrefix == null || keyPrefix.isBlank()) keyPrefix = "pc:";
    }
}
