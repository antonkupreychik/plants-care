package com.plantcare.core.cache;

import com.plantcare.bot.support.IntegrationTestBase;
import com.plantcare.core.config.RedisProperties;
import com.plantcare.core.config.TwoLevelCacheProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционный тест двухуровневого кеша (issue #281, эпик #277 фаза 3).
 *
 * <p>AC:
 * <ul>
 *   <li>Инвалидация (clear/evict) в одном инстансе видна другим — через Redis Pub/Sub
 *       чистится локальный L1 у остальных.</li>
 *   <li>Падение Redis не ломает чтение справочников — fail-open на L1 + загрузчик (БД).</li>
 *   <li>Прогрев L1 из общего L2 (попадание в Redis наполняет локальный Caffeine).</li>
 * </ul>
 *
 * <p>«Инстанс A» — реальный, поднятый Spring-контекстом ({@link CacheManager} +
 * listener-контейнер). «Инстанс B» — вручную собранный второй менеджер с собственным
 * listener поверх того же Redis, моделирует второй под приложения.
 */
// two-level выключен в общем test-профиле (application-test.yml, #281) — здесь Redis
// поднят через IntegrationTestBase, поэтому включаем two-level обратно для этого теста.
@TestPropertySource(properties = "app.cache.two-level.enabled=true")
class TwoLevelCacheIT extends IntegrationTestBase {

    private static final String CACHE = "species-detail";
    private static final String CHANNEL = "pc:cache:invalidate";

    @Autowired
    private CacheManager cacheManager; // инстанс A (из контекста)

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private RedisProperties redisProperties;

    @Autowired
    private RedisConnectionFactory connectionFactory;

    private RedisMessageListenerContainer instanceBContainer;

    @AfterEach
    void cleanup() throws Exception {
        redisTemplate.delete(redisProperties.keyPrefix() + "cache:" + CACHE);
        cacheManager.getCache(CACHE).clear();
        if (instanceBContainer != null) {
            instanceBContainer.destroy();
            instanceBContainer = null;
        }
    }

    // ═══════════════ cross-instance invalidation ═══════════════

    @Test
    void should_clear_l1_on_other_instance_when_one_instance_clears() throws Exception {
        // arrange — инстанс A кладёт значение (греет свой L1 и общий L2)
        Cache cacheA = cacheManager.getCache(CACHE);
        cacheA.put(1L, "alpha");
        assertThat(cacheA.get(1L, TwoLevelCacheIT::failLoader)).isEqualTo("alpha");

        // act — другой инстанс (B) делает clear того же кеша
        TwoLevelCacheManager managerB = newInstanceB(props());
        managerB.getCache(CACHE).clear();

        // assert — clear от B по Pub/Sub почистил L1 инстанса A; L2 тоже очищен (DEL хеша),
        // поэтому при следующем чтении сработает загрузчик (БД), а не cache hit.
        AtomicInteger loaderCalls = new AtomicInteger();
        String value = awaitGet(cacheA, 1L, () -> {
            loaderCalls.incrementAndGet();
            return "reloaded";
        }, "reloaded");

        assertThat(value).isEqualTo("reloaded");
        assertThat(loaderCalls.get()).isPositive();
    }

    // ═══════════════ L1 warm-up from shared L2 ═══════════════

    @Test
    void should_warm_l1_from_shared_l2_written_by_other_instance() {
        // arrange — инстанс B пишет в общий L2
        TwoLevelCacheManager managerB = newInstanceB(props());
        managerB.getCache(CACHE).put(42L, "from-B");

        // act — инстанс A читает тот же ключ; своего L1 ещё нет, значение должно прийти из L2
        Cache cacheA = cacheManager.getCache(CACHE);
        AtomicInteger loaderCalls = new AtomicInteger();
        String value = cacheA.get(42L, () -> {
            loaderCalls.incrementAndGet();
            return "from-DB";
        });

        // assert — взяли из общего L2, загрузчик (БД) не понадобился
        assertThat(value).isEqualTo("from-B");
        assertThat(loaderCalls.get()).isZero();
    }

    // ═══════════════ fail-open: Redis недоступен ═══════════════

    @Test
    void should_serve_reads_from_l1_and_db_when_redis_is_down() {
        // arrange — кеш с заведомо «мёртвым» Redis (несуществующий порт)
        var deadFactory = new LettuceConnectionFactory("127.0.0.1", 6390);
        deadFactory.afterPropertiesSet();
        deadFactory.start();
        var deadTemplate = new RedisTemplate<String, Object>();
        deadTemplate.setConnectionFactory(deadFactory);
        deadTemplate.setKeySerializer(RedisSerializer.string());
        deadTemplate.setValueSerializer(redisTemplate.getValueSerializer());
        deadTemplate.afterPropertiesSet();

        var managerDown = new TwoLevelCacheManager(props(), redisProperties, deadTemplate, List.of(CACHE));
        Cache cache = managerDown.getCache(CACHE);

        // act — put (L2 упадёт молча, L1 запишется), затем два чтения
        cache.put(7L, "value-7");
        AtomicInteger loaderCalls = new AtomicInteger();
        String fromL1 = cache.get(7L, () -> {
            loaderCalls.incrementAndGet();
            return "from-DB";
        });
        String fromDb = cache.get(8L, () -> {  // нет в L1, L2 недоступен → загрузчик
            loaderCalls.incrementAndGet();
            return "loaded-8";
        });

        // assert — Redis down не сломал кеш: L1 hit + загрузчик отработали корректно
        assertThat(fromL1).isEqualTo("value-7");    // взято из L1, без загрузчика
        assertThat(fromDb).isEqualTo("loaded-8");   // L2 промах → БД
        assertThat(loaderCalls.get()).isEqualTo(1); // загрузчик вызван только для отсутствующего ключа

        deadFactory.destroy();
    }

    // ═══════════════ helpers ═══════════════

    private static TwoLevelCacheProperties props() {
        return new TwoLevelCacheProperties(true, Duration.ofMinutes(5), 1000L,
                Duration.ofHours(1), CHANNEL);
    }

    /** Собирает второй «инстанс» (менеджер + его listener) поверх того же Redis. */
    private TwoLevelCacheManager newInstanceB(TwoLevelCacheProperties props) {
        var managerB = new TwoLevelCacheManager(props, redisProperties, redisTemplate, List.of(CACHE));

        var listenerB = new CacheInvalidationListener(managerB, TwoLevelCacheManager.messageSerializer());
        instanceBContainer = new RedisMessageListenerContainer();
        instanceBContainer.setConnectionFactory(connectionFactory);
        instanceBContainer.addMessageListener(listenerB, new ChannelTopic(props.invalidationChannel()));
        instanceBContainer.afterPropertiesSet();
        instanceBContainer.start();
        return managerB;
    }

    /**
     * Pub/Sub-доставка асинхронна — поллим короткими интервалами до ожидаемого значения
     * (или таймаута). Загрузчик кладёт значение в кеш, поэтому ждём именно момент,
     * когда L1 уже почищен и загрузчик отработал.
     */
    private static <T> T awaitGet(Cache cache, Object key, java.util.concurrent.Callable<T> loader, T expected)
            throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        T last = null;
        while (System.currentTimeMillis() < deadline) {
            last = cache.get(key, loader);
            if (expected.equals(last)) {
                return last;
            }
            Thread.sleep(50);
        }
        return last;
    }

    private static Object failLoader() {
        throw new AssertionError("loader must not be called — value expected in cache");
    }
}
