package com.plantcare.core.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Резильентный стартер listener-контейнера инвалидации кеша (issue #281, эпик #277 фаза 3).
 *
 * <p><b>Проблема (инцидент на dev).</b> {@link RedisMessageListenerContainer} с
 * {@code autoStartup=true} стартует синхронно во время refresh контекста. Когда Redis
 * недоступен, {@code lazyListen} бросает {@code RedisConnectionFailureException} из
 * {@code SmartLifecycle.start()}, и весь {@code ApplicationContext} не поднимается —
 * приложение не стартует. Это нарушает заявленный для #281 принцип <b>fail-open</b>:
 * Redis-блип не должен класть прод, кэш должен деградировать на L1 + БД.
 *
 * <p><b>Решение.</b> Контейнер сконфигурирован с {@code autoStartup=false}
 * ({@link com.plantcare.core.config.CacheConfig}), Spring его не стартует. Этот стартер:
 * <ul>
 *   <li>на {@link ApplicationReadyEvent} пытается {@code start()} в try/catch — при ошибке
 *       пишет WARN и НЕ роняет контекст;</li>
 *   <li>{@link Scheduled} периодически проверяет {@code !isRunning()} и пробует стартовать
 *       снова — так листенер поднимется, как только Redis вернётся.</li>
 * </ul>
 * Бин активен в two-level-режиме ({@code app.cache.two-level.enabled=true}, дефолт), то есть
 * ровно тогда же, когда создаётся сам контейнер. В фоллбэк-режиме контейнера нет — стартер
 * тоже не создаётся.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.cache.two-level", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CacheInvalidationListenerStarter {

    private final RedisMessageListenerContainer cacheInvalidationListenerContainer;

    /**
     * Первая попытка запуска после готовности приложения. Ошибка (Redis недоступен) не
     * пробрасывается — контекст уже поднят, ретраи продолжит {@link #retryStartIfDown()}.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void startOnReady() {
        tryStart();
    }

    /**
     * Периодический ретрай: если контейнер ещё не запущен (Redis был недоступен на старте или
     * упал), пробуем поднять его снова. Когда Redis вернётся — листенер подпишется и
     * кросс-инстансная инвалидация заработает. Интервал — из {@code app.cache.two-level
     * .listener-retry-interval} (дефолт 30s).
     */
    @Scheduled(
            fixedDelayString = "${app.cache.two-level.listener-retry-interval:PT30S}",
            initialDelayString = "${app.cache.two-level.listener-retry-interval:PT30S}")
    public void retryStartIfDown() {
        if (!cacheInvalidationListenerContainer.isRunning()) {
            tryStart();
        }
    }

    /**
     * Идемпотентный запуск: не дублирует старт, если контейнер уже running; ошибку запуска
     * (Redis недоступен) глотает с WARN, чтобы не ронять контекст и не валить шедулер.
     */
    private void tryStart() {
        if (cacheInvalidationListenerContainer.isRunning()) {
            return;
        }
        try {
            cacheInvalidationListenerContainer.start();
            log.info("Cache invalidation listener started (Redis Pub/Sub subscription active)");
        } catch (RuntimeException e) {
            log.warn("Redis недоступен, листенер инвалидации кеша не запущен, ретрай позже: {}",
                    e.getMessage());
        }
    }
}
