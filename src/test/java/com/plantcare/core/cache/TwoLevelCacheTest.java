package com.plantcare.core.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.support.NullValue;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TwoLevelCache — двухуровневый кеш Caffeine L1 + Redis L2")
class TwoLevelCacheTest {

    private static final String CACHE_NAME = "test-cache";
    private static final String KEY_PREFIX = "pc:";
    private static final String REDIS_HASH_KEY = KEY_PREFIX + "cache:" + CACHE_NAME;
    private static final String ORIGIN_ID = "instance-1";
    private static final Duration L2_TTL = Duration.ofHours(1);

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    @SuppressWarnings("unchecked")
    private HashOperations<String, Object, Object> hashOps;

    @Mock
    @SuppressWarnings("unchecked")
    private Consumer<CacheInvalidationMessage> invalidationPublisher;

    private Cache<Object, Object> l1;
    private TwoLevelCache cache;

    @BeforeEach
    void setUp() {
        l1 = Caffeine.newBuilder().build();
        // lenient: not all tests touch Redis — tests that use only L1 or null-redisTemplate don't need this stub
        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOps);
        cache = new TwoLevelCache(CACHE_NAME, l1, redisTemplate, KEY_PREFIX, L2_TTL,
                invalidationPublisher, ORIGIN_ID);
    }

    @Test
    @DisplayName("getName возвращает имя кеша")
    void should_returnCacheName_when_getNameCalled() {
        assertThat(cache.getName()).isEqualTo(CACHE_NAME);
    }

    @Test
    @DisplayName("getNativeCache возвращает Caffeine L1")
    void should_returnL1Cache_when_getNativeCacheCalled() {
        assertThat(cache.getNativeCache()).isSameAs(l1);
    }

    // ──────────────────────────── lookup / get ────────────────────────────

    @Nested
    @DisplayName("lookup / get")
    class LookupTests {

        @Test
        @DisplayName("Возвращает значение из L1 без обращения к Redis")
        void should_returnL1Value_when_l1Hit() {
            l1.put("key1", "value1");

            org.springframework.cache.Cache.ValueWrapper result = cache.get("key1");

            assertThat(result).isNotNull();
            assertThat(result.get()).isEqualTo("value1");
            verifyNoInteractions(hashOps);
        }

        @Test
        @DisplayName("Прогревает L1 из L2 при попадании в Redis")
        void should_warmL1FromL2_when_l1MissAndL2Hit() {
            when(hashOps.get(REDIS_HASH_KEY, "key2")).thenReturn("from-redis");

            org.springframework.cache.Cache.ValueWrapper result = cache.get("key2");

            assertThat(result).isNotNull();
            assertThat(result.get()).isEqualTo("from-redis");
            // L1 прогрет
            assertThat(l1.getIfPresent("key2")).isEqualTo("from-redis");
        }

        @Test
        @DisplayName("Возвращает null при промахе в обоих уровнях")
        void should_returnNull_when_bothL1AndL2Miss() {
            when(hashOps.get(REDIS_HASH_KEY, "missing")).thenReturn(null);

            org.springframework.cache.Cache.ValueWrapper result = cache.get("missing");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Fail-open: Redis недоступен при чтении — возвращает null без исключения")
        void should_returnNullAndNotThrow_when_redisThrowsOnRead() {
            when(hashOps.get(anyString(), any())).thenThrow(new RuntimeException("Redis timeout"));

            assertThatNoException().isThrownBy(() -> {
                org.springframework.cache.Cache.ValueWrapper result = cache.get("key3");
                assertThat(result).isNull();
            });
        }

        @Test
        @DisplayName("L2 отключён (redisTemplate=null) — работает только через L1")
        void should_workWithL1Only_when_redisTemplateIsNull() {
            TwoLevelCache noL2 = new TwoLevelCache(CACHE_NAME, l1, null, KEY_PREFIX, L2_TTL,
                    invalidationPublisher, ORIGIN_ID);
            l1.put("key4", "local-value");

            org.springframework.cache.Cache.ValueWrapper result = noL2.get("key4");

            assertThat(result).isNotNull();
            assertThat(result.get()).isEqualTo("local-value");
            verifyNoInteractions(redisTemplate);
        }

        @Test
        @DisplayName("L2 отключён — промах L1 не обращается к Redis, возвращает null")
        void should_returnNullWithoutRedis_when_l2DisabledAndL1Miss() {
            TwoLevelCache noL2 = new TwoLevelCache(CACHE_NAME, l1, null, KEY_PREFIX, L2_TTL,
                    invalidationPublisher, ORIGIN_ID);

            org.springframework.cache.Cache.ValueWrapper result = noL2.get("absent");

            assertThat(result).isNull();
            verifyNoInteractions(redisTemplate);
        }

        @Test
        @DisplayName("get(key, valueLoader): промах — вызывает загрузчик и кладёт значение")
        void should_callValueLoaderAndCache_when_cacheMiss() {
            when(hashOps.get(REDIS_HASH_KEY, "db-key")).thenReturn(null);

            String loaded = cache.get("db-key", () -> "from-db");

            assertThat(loaded).isEqualTo("from-db");
            assertThat(l1.getIfPresent("db-key")).isEqualTo("from-db");
            verify(hashOps).put(eq(REDIS_HASH_KEY), eq("db-key"), eq("from-db"));
        }

        @Test
        @DisplayName("get(key, valueLoader): попадание — не вызывает загрузчик")
        void should_returnCachedValueWithoutCallingLoader_when_cacheHit() {
            l1.put("cached-key", "cached-value");
            @SuppressWarnings("unchecked")
            Callable<String> loader = mock(Callable.class);

            String result = cache.get("cached-key", loader);

            assertThat(result).isEqualTo("cached-value");
            verifyNoInteractions(loader);
        }

        @Test
        @DisplayName("get(key, valueLoader): исключение загрузчика — пробрасывается как ValueRetrievalException")
        void should_throwValueRetrievalException_when_loaderThrows() {
            when(hashOps.get(anyString(), any())).thenReturn(null);

            assertThatThrownBy(() -> cache.get("bad-key", () -> {
                throw new RuntimeException("DB error");
            })).isInstanceOf(org.springframework.cache.Cache.ValueRetrievalException.class);
        }
    }

    // ──────────────────────────── put ────────────────────────────

    @Nested
    @DisplayName("put")
    class PutTests {

        @Test
        @DisplayName("Сохраняет значение в L1 и L2")
        void should_putInBothLevels_when_valuePut() {
            cache.put("k", "v");

            assertThat(l1.getIfPresent("k")).isEqualTo("v");
            verify(hashOps).put(REDIS_HASH_KEY, "k", "v");
            verify(redisTemplate).expire(REDIS_HASH_KEY, L2_TTL);
        }

        @Test
        @DisplayName("null значение кодируется как NullValue в обоих уровнях")
        void should_storeNullValue_when_nullPut() {
            cache.put("null-key", null);

            assertThat(l1.getIfPresent("null-key")).isInstanceOf(NullValue.class);
            verify(hashOps).put(eq(REDIS_HASH_KEY), eq("null-key"), any(NullValue.class));
        }

        @Test
        @DisplayName("Fail-open: Redis недоступен при записи — значение остаётся в L1")
        void should_keepInL1Only_when_redisThrowsOnWrite() {
            doThrow(new RuntimeException("Redis down")).when(hashOps).put(anyString(), any(), any());

            assertThatNoException().isThrownBy(() -> cache.put("k2", "v2"));
            assertThat(l1.getIfPresent("k2")).isEqualTo("v2");
        }

        @Test
        @DisplayName("L2 отключён — put только в L1, нет обращений к Redis")
        void should_putInL1Only_when_redisTemplateIsNull() {
            TwoLevelCache noL2 = new TwoLevelCache(CACHE_NAME, l1, null, KEY_PREFIX, L2_TTL,
                    invalidationPublisher, ORIGIN_ID);

            noL2.put("k3", "v3");

            assertThat(l1.getIfPresent("k3")).isEqualTo("v3");
            verifyNoInteractions(redisTemplate);
        }
    }

    // ──────────────────────────── evict ────────────────────────────

    @Nested
    @DisplayName("evict")
    class EvictTests {

        @Test
        @DisplayName("Удаляет из L1 и L2, публикует сообщение инвалидации")
        void should_evictFromBothLevelsAndPublish_when_evictCalled() {
            l1.put("ek", "ev");
            ArgumentCaptor<CacheInvalidationMessage> msgCaptor =
                    ArgumentCaptor.forClass(CacheInvalidationMessage.class);

            cache.evict("ek");

            assertThat(l1.getIfPresent("ek")).isNull();
            verify(hashOps).delete(REDIS_HASH_KEY, "ek");
            verify(invalidationPublisher).accept(msgCaptor.capture());
            CacheInvalidationMessage msg = msgCaptor.getValue();
            assertThat(msg.cacheName()).isEqualTo(CACHE_NAME);
            assertThat(msg.key()).isEqualTo("ek");
            assertThat(msg.originId()).isEqualTo(ORIGIN_ID);
            assertThat(msg.isClear()).isFalse();
        }

        @Test
        @DisplayName("Fail-open: Redis недоступен при evict — L1 вычищен, исключение не пробрасывается")
        void should_evictL1AndPublish_when_redisThrowsOnEvict() {
            l1.put("ek2", "ev2");
            doThrow(new RuntimeException("Redis down")).when(hashOps).delete(anyString(), any());

            assertThatNoException().isThrownBy(() -> cache.evict("ek2"));
            assertThat(l1.getIfPresent("ek2")).isNull();
            verify(invalidationPublisher).accept(any());
        }

        @Test
        @DisplayName("L2 отключён — evict удаляет из L1 и публикует инвалидацию")
        void should_evictL1AndPublish_when_redisTemplateIsNull() {
            TwoLevelCache noL2 = new TwoLevelCache(CACHE_NAME, l1, null, KEY_PREFIX, L2_TTL,
                    invalidationPublisher, ORIGIN_ID);
            l1.put("ek3", "ev3");

            noL2.evict("ek3");

            assertThat(l1.getIfPresent("ek3")).isNull();
            verifyNoInteractions(redisTemplate);
            verify(invalidationPublisher).accept(any());
        }

        @Test
        @DisplayName("Fail-open: publishInvalidation бросает — исключение не пробрасывается")
        void should_notThrow_when_publishThrowsOnEvict() {
            doThrow(new RuntimeException("Pub/Sub down")).when(invalidationPublisher).accept(any());

            assertThatNoException().isThrownBy(() -> cache.evict("ek4"));
        }
    }

    // ──────────────────────────── clear ────────────────────────────

    @Nested
    @DisplayName("clear")
    class ClearTests {

        @Test
        @DisplayName("Очищает оба уровня и публикует clear-сообщение")
        void should_clearBothLevelsAndPublish_when_clearCalled() {
            l1.put("k1", "v1");
            l1.put("k2", "v2");
            ArgumentCaptor<CacheInvalidationMessage> msgCaptor =
                    ArgumentCaptor.forClass(CacheInvalidationMessage.class);

            cache.clear();

            assertThat(l1.estimatedSize()).isZero();
            verify(redisTemplate).delete(REDIS_HASH_KEY);
            verify(invalidationPublisher).accept(msgCaptor.capture());
            CacheInvalidationMessage msg = msgCaptor.getValue();
            assertThat(msg.cacheName()).isEqualTo(CACHE_NAME);
            assertThat(msg.isClear()).isTrue();
            assertThat(msg.key()).isNull();
        }

        @Test
        @DisplayName("Fail-open: Redis недоступен при clear — L1 вычищен, не пробрасывает")
        void should_clearL1AndPublish_when_redisThrowsOnClear() {
            l1.put("k1", "v1");
            doThrow(new RuntimeException("Redis down")).when(redisTemplate).delete(anyString());

            assertThatNoException().isThrownBy(() -> cache.clear());
            assertThat(l1.estimatedSize()).isZero();
            verify(invalidationPublisher).accept(any());
        }

        @Test
        @DisplayName("L2 отключён — clear очищает только L1, публикует инвалидацию")
        void should_clearL1AndPublish_when_redisTemplateIsNull() {
            TwoLevelCache noL2 = new TwoLevelCache(CACHE_NAME, l1, null, KEY_PREFIX, L2_TTL,
                    invalidationPublisher, ORIGIN_ID);
            l1.put("k1", "v1");

            noL2.clear();

            assertThat(l1.estimatedSize()).isZero();
            verifyNoInteractions(redisTemplate);
            verify(invalidationPublisher).accept(any());
        }

        @Test
        @DisplayName("Fail-open: publishInvalidation бросает при clear — не пробрасывает")
        void should_notThrow_when_publishThrowsOnClear() {
            doThrow(new RuntimeException("Pub/Sub down")).when(invalidationPublisher).accept(any());

            assertThatNoException().isThrownBy(() -> cache.clear());
        }
    }

    // ──────────────────────────── L1-only local invalidation ────────────────────────────

    @Nested
    @DisplayName("evictL1Local / clearL1Local — локальная инвалидация без Redis и Pub/Sub")
    class LocalInvalidationTests {

        @Test
        @DisplayName("evictL1Local удаляет только из L1, не трогает Redis и не публикует")
        void should_evictOnlyL1_when_evictL1LocalCalled() {
            l1.put("lk", "lv");

            cache.evictL1Local("lk");

            assertThat(l1.getIfPresent("lk")).isNull();
            verifyNoInteractions(hashOps);
            verifyNoInteractions(invalidationPublisher);
        }

        @Test
        @DisplayName("clearL1Local очищает только L1, не трогает Redis и не публикует")
        void should_clearOnlyL1_when_clearL1LocalCalled() {
            l1.put("a", "1");
            l1.put("b", "2");

            cache.clearL1Local();

            assertThat(l1.estimatedSize()).isZero();
            verifyNoInteractions(hashOps);
            verifyNoInteractions(invalidationPublisher);
        }

        @Test
        @DisplayName("evictL1Local молча игнорирует ключ, которого нет в L1")
        void should_notThrow_when_evictL1LocalCalledForAbsentKey() {
            assertThatNoException().isThrownBy(() -> cache.evictL1Local("absent"));
        }
    }
}
