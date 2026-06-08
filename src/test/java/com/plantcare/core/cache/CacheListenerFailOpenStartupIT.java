package com.plantcare.core.cache;

import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Регресс-тест на dev-инцидент (issue #281, эпик #277 фаза 3): при two-level-режиме
 * с НЕДОСТУПНЫМ Redis {@code ApplicationContext} обязан подняться.
 *
 * <p>Раньше listener-контейнер ({@link org.springframework.data.redis.listener.RedisMessageListenerContainer})
 * стартовал синхронно во время refresh; при недоступном Redis {@code lazyListen} бросал
 * {@code RedisConnectionFailureException} из {@code SmartLifecycle.start()} и валил весь
 * контекст — приложение не стартовало. Это нарушало принцип fail-open.
 *
 * <p>Фикс: контейнер с {@code autoStartup=false}, реальный запуск — резильентный
 * {@link CacheInvalidationListenerStarter} (try/catch + ретраи на {@code @Scheduled}).
 * Поэтому недоступность Redis на старте больше не блокирует контекст: кэш деградирует на
 * L1 + БД, а листенер поднимется сам, когда Redis вернётся.
 *
 * <p>Здесь Redis намеренно «мёртвый» — порт {@code 6390}, на котором никто не слушает
 * (перекрывает live-Redis из {@link IntegrationTestBase} через {@code @TestPropertySource},
 * у которого приоритет выше {@code @DynamicPropertySource}). two-level включён.
 */
@TestPropertySource(properties = {
        "app.cache.two-level.enabled=true",
        // Заведомо мёртвый Redis: порт, на котором никто не слушает.
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=6390",
        // Короткие таймауты, чтобы старт listener-контейнера падал быстро, а не висел.
        "spring.data.redis.connect-timeout=200ms",
        "spring.data.redis.timeout=200ms"
})
class CacheListenerFailOpenStartupIT extends IntegrationTestBase {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private CacheManager cacheManager;

    @Test
    void should_start_context_and_keep_cache_usable_when_redis_unavailable() {
        // assert — контекст поднялся (этот тест вообще запустился = refresh прошёл),
        // приложение живо несмотря на недоступный Redis.
        assertThat(applicationContext).isNotNull();

        // two-level-менеджер создан (фоллбэк-Caffeine НЕ активен при enabled=true),
        // листенер сконфигурирован, но Redis недоступен — кэш деградирует на L1 + БД (fail-open).
        assertThat(applicationContext.containsBean("twoLevelCacheManager")).isTrue();
        assertThat(applicationContext.containsBean("cacheInvalidationListenerContainer")).isTrue();

        // act + assert — кэш пригоден к использованию: загрузчик отрабатывает, значение
        // кладётся в L1, повторное чтение берёт из кэша без повторного вызова загрузчика.
        var cache = cacheManager.getCache("species-detail");
        assertThat(cache).isNotNull();

        int[] loaderCalls = {0};
        String first = cache.get(99L, () -> {
            loaderCalls[0]++;
            return "loaded-99";
        });
        String second = cache.get(99L, () -> {
            loaderCalls[0]++;
            return "loaded-99";
        });

        assertThat(first).isEqualTo("loaded-99");
        assertThat(second).isEqualTo("loaded-99");
        assertThat(loaderCalls[0]).isEqualTo(1); // второе чтение — L1 hit, без загрузчика
    }
}
