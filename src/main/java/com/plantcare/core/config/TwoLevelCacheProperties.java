package com.plantcare.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Issue #281, эпик #277 фаза 3: настройки двухуровневого кеша справочников
 * (Caffeine L1 + Redis L2).
 *
 * <p>Профиль чтения: L1 (локальный, быстрый) → L2 (общий между инстансами) → БД.
 * Инвалидация в одном инстансе рассылается по всем через Redis Pub/Sub-канал
 * {@link #invalidationChannel()}.
 *
 * <p>Все значения имеют безопасные дефолты для локального запуска; на проде
 * переопределяются env-переменными (Railway).
 */
@ConfigurationProperties(prefix = "app.cache.two-level")
public record TwoLevelCacheProperties(

        /**
         * Главный выключатель. {@code false} — фоллбэк на чистый локальный Caffeine
         * (поведение до issue #281), Redis L2 и Pub/Sub не используются.
         * Дефолт: {@code true}.
         */
        Boolean enabled,

        /**
         * TTL записей в локальном L1 (Caffeine, expireAfterWrite). По умолчанию короче L2:
         * L1 быстро устаревает, но согласованность держит L2 и инвалидация по Pub/Sub.
         * Дефолт: 5 минут.
         */
        Duration l1Ttl,

        /**
         * Максимальный размер L1 (maximumSize Caffeine) на каждый справочник.
         * Дефолт: 10000 записей.
         */
        Long l1MaxSize,

        /**
         * TTL записей в общем L2 (Redis). По умолчанию длиннее L1.
         * Дефолт: 1 час.
         */
        Duration l2Ttl,

        /**
         * Имя Redis Pub/Sub-канала для рассылки сообщений инвалидации между инстансами.
         * Дефолт: {@code pc:cache:invalidate}.
         */
        String invalidationChannel,

        /**
         * Интервал восстановления подписки listener-контейнера ({@code recoveryInterval}
         * у {@link org.springframework.data.redis.listener.RedisMessageListenerContainer}).
         * Если соединение с Redis рвётся, контейнер пытается переподписаться через этот интервал.
         * Дефолт: 5 секунд.
         */
        Duration recoveryInterval,

        /**
         * Интервал повторных попыток запуска listener-контейнера, когда Redis недоступен на
         * старте приложения (fail-open, issue #281). Резильентный стартер периодически проверяет
         * {@code !container.isRunning()} и пробует {@code start()} — так листенер поднимется,
         * как только Redis вернётся, не роняя ApplicationContext.
         * Дефолт: 30 секунд.
         */
        Duration listenerRetryInterval

) {

    public TwoLevelCacheProperties {
        if (enabled == null) enabled = Boolean.TRUE;
        if (l1Ttl == null) l1Ttl = Duration.ofMinutes(5);
        if (l1MaxSize == null || l1MaxSize <= 0) l1MaxSize = 10_000L;
        if (l2Ttl == null) l2Ttl = Duration.ofHours(1);
        if (invalidationChannel == null || invalidationChannel.isBlank()) {
            invalidationChannel = "pc:cache:invalidate";
        }
        if (recoveryInterval == null) recoveryInterval = Duration.ofSeconds(5);
        if (listenerRetryInterval == null) listenerRetryInterval = Duration.ofSeconds(30);
    }
}
