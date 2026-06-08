package com.plantcare.core.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.plantcare.core.cache.CacheInvalidationListener;
import com.plantcare.core.cache.TwoLevelCacheManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Issue #281, эпик #277 фаза 3: конфигурация кеша справочников.
 *
 * <p>Два режима:
 * <ul>
 *   <li><b>two-level</b> (дефолт, {@code app.cache.two-level.enabled=true}) — Caffeine L1 +
 *       Redis L2 c кросс-инстансной инвалидацией через Pub/Sub. Менеджер —
 *       {@link TwoLevelCacheManager}.</li>
 *   <li><b>фоллбэк</b> ({@code enabled=false}) — чистый локальный Caffeine
 *       ({@link CaffeineCacheManager}), как до issue #281. Redis не задействован.</li>
 * </ul>
 *
 * <p>Имена кешей не меняются ({@code species-list}, {@code species-detail}, {@code care-types}),
 * поэтому существующие {@code @Cacheable}/{@code @CacheEvict} в сервисах работают через любой
 * из менеджеров без правок.
 */
@Slf4j
@Configuration
@EnableCaching
@EnableConfigurationProperties(TwoLevelCacheProperties.class)
public class CacheConfig {

    /** Справочники, кешируемые приложением. Совпадает с прежним списком в {@code CaffeineCacheManager}. */
    static final List<String> CACHE_NAMES = List.of("species-list", "species-detail", "care-types");

    // ────────────────────────── two-level (Caffeine L1 + Redis L2) ──────────────────────────

    /**
     * Основной менеджер: двухуровневый кеш. Активен, когда {@code app.cache.two-level.enabled}
     * не задан (дефолт) или {@code true}.
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.cache.two-level", name = "enabled", havingValue = "true", matchIfMissing = true)
    public CacheManager twoLevelCacheManager(
            TwoLevelCacheProperties properties,
            RedisProperties redisProperties,
            RedisTemplate<String, Object> redisTemplate) {

        log.info("Cache: two-level mode (Caffeine L1 + Redis L2), l1Ttl={}, l2Ttl={}, channel={}",
                properties.l1Ttl(), properties.l2Ttl(), properties.invalidationChannel());

        return new TwoLevelCacheManager(properties, redisProperties, redisTemplate, CACHE_NAMES);
    }

    /**
     * Контейнер-слушатель Redis Pub/Sub-канала инвалидации. Поднимается только в two-level-режиме.
     * Регистрирует {@link CacheInvalidationListener}, который чистит локальный L1 по сообщениям
     * от других инстансов.
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.cache.two-level", name = "enabled", havingValue = "true", matchIfMissing = true)
    public RedisMessageListenerContainer cacheInvalidationListenerContainer(
            RedisConnectionFactory connectionFactory,
            CacheManager twoLevelCacheManager,
            TwoLevelCacheProperties properties) {

        var listener = new CacheInvalidationListener(
                (TwoLevelCacheManager) twoLevelCacheManager,
                TwoLevelCacheManager.messageSerializer());

        var container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listener, new ChannelTopic(properties.invalidationChannel()));

        log.info("Cache invalidation listener subscribed to channel {}", properties.invalidationChannel());
        return container;
    }

    // ────────────────────────── фоллбэк (чистый Caffeine) ──────────────────────────

    /**
     * Фоллбэк-менеджер: локальный Caffeine без Redis. Активен при
     * {@code app.cache.two-level.enabled=false}. Поведение совпадает с прежним
     * (до issue #281): TTL 1 час, без общего L2 и кросс-инстансной инвалидации.
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.cache.two-level", name = "enabled", havingValue = "false")
    public CacheManager caffeineCacheManager() {
        log.info("Cache: fallback mode (local Caffeine only, no Redis L2)");

        var manager = new CaffeineCacheManager(CACHE_NAMES.toArray(String[]::new));
        manager.setCaffeine(Caffeine.newBuilder().expireAfterWrite(1, TimeUnit.HOURS));
        return manager;
    }
}
