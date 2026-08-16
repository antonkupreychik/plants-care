package com.plantcare.core.cache;

import com.plantcare.core.config.RedisProperties;
import com.plantcare.core.config.TwoLevelCacheProperties;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CacheInvalidationListener — обработка сообщений инвалидации L1")
class CacheInvalidationListenerTest {

    private static final String ORIGIN_A = "instance-A";
    private static final String ORIGIN_B = "instance-B";
    private static final String CACHE_NAME = "plants";
    private static final byte[] DUMMY_BODY = new byte[]{1, 2, 3};

    @Mock
    @SuppressWarnings("unchecked")
    private RedisSerializer<CacheInvalidationMessage> messageSerializer;

    @Mock
    private Message redisMessage;

    /**
     * Настоящий TwoLevelCacheManager с отключённым L2 (redisTemplate=null).
     * Это даёт нам реальный findExisting / getOriginId без лишних моков,
     * и позволяет проверить, что listener реально чистит L1 Caffeine-кеша.
     */
    private TwoLevelCacheManager cacheManager;
    private TwoLevelCache plantsCache;

    @BeforeEach
    void setUp() {
        cacheManager = new TwoLevelCacheManager(
                new TwoLevelCacheProperties(null, null, null, null, null, null, null),
                new RedisProperties(null, null, null),
                null, // L2 отключён — нет Redis
                List.of(CACHE_NAME));

        plantsCache = (TwoLevelCache) cacheManager.getCache(CACHE_NAME);
        when(redisMessage.getBody()).thenReturn(DUMMY_BODY);
    }

    private CacheInvalidationListener listener() {
        return new CacheInvalidationListener(cacheManager, messageSerializer);
    }

    private CacheInvalidationMessage evictMsg(String originId) {
        return CacheInvalidationMessage.evict(originId, CACHE_NAME, "key-1");
    }

    private CacheInvalidationMessage clearMsg(String originId) {
        return CacheInvalidationMessage.clear(originId, CACHE_NAME);
    }

    // ──────────────────────────── evict от другого инстанса ────────────────────────────

    @Test
    @DisplayName("Чистит L1 по ключу при evict-сообщении от другого инстанса")
    void should_evictL1ByKey_when_evictMessageFromOtherOrigin() {
        plantsCache.put("key-1", "value-1");
        CacheInvalidationMessage msg = evictMsg(ORIGIN_B);
        when(messageSerializer.deserialize(DUMMY_BODY)).thenReturn(msg);

        listener().onMessage(redisMessage, null);

        // После evict значение должно исчезнуть из L1
        // NB: evictL1Local — только L1, поэтому проверяем через getNativeCache
        @SuppressWarnings("unchecked")
        com.github.benmanes.caffeine.cache.Cache<Object, Object> l1 =
                (com.github.benmanes.caffeine.cache.Cache<Object, Object>) plantsCache.getNativeCache();
        assertThat(l1.getIfPresent("key-1")).isNull();
    }

    // ──────────────────────────── clear от другого инстанса ────────────────────────────

    @Test
    @DisplayName("Чистит весь L1 при clear-сообщении от другого инстанса")
    void should_clearL1_when_clearMessageFromOtherOrigin() {
        plantsCache.put("key-1", "value-1");
        plantsCache.put("key-2", "value-2");
        CacheInvalidationMessage msg = clearMsg(ORIGIN_B);
        when(messageSerializer.deserialize(DUMMY_BODY)).thenReturn(msg);

        listener().onMessage(redisMessage, null);

        @SuppressWarnings("unchecked")
        com.github.benmanes.caffeine.cache.Cache<Object, Object> l1 =
                (com.github.benmanes.caffeine.cache.Cache<Object, Object>) plantsCache.getNativeCache();
        assertThat(l1.estimatedSize()).isZero();
    }

    // ──────────────────────────── собственное эхо ────────────────────────────

    @Test
    @DisplayName("Игнорирует сообщение от себя (собственное эхо не чистит L1 повторно)")
    void should_ignoreOwnEcho_when_messageHasSameOriginId() {
        plantsCache.put("key-1", "value-1");
        // Сообщение с тем же originId, что у этого менеджера
        CacheInvalidationMessage ownMsg = CacheInvalidationMessage.evict(
                cacheManager.getOriginId(), CACHE_NAME, "key-1");
        when(messageSerializer.deserialize(DUMMY_BODY)).thenReturn(ownMsg);

        listener().onMessage(redisMessage, null);

        // L1 НЕ должен быть очищен — это собственное эхо
        @SuppressWarnings("unchecked")
        com.github.benmanes.caffeine.cache.Cache<Object, Object> l1 =
                (com.github.benmanes.caffeine.cache.Cache<Object, Object>) plantsCache.getNativeCache();
        assertThat(l1.getIfPresent("key-1")).isNotNull();
    }

    // ──────────────────────────── кеш не создан ────────────────────────────

    @Test
    @DisplayName("Игнорирует сообщение для кеша, которого нет на этом инстансе")
    void should_ignoreMessage_when_cacheNotYetCreated() {
        CacheInvalidationMessage msg = CacheInvalidationMessage.evict(ORIGIN_B, "nonexistent-cache", "key-1");
        when(messageSerializer.deserialize(DUMMY_BODY)).thenReturn(msg);

        // Должно быть без исключений
        assertThatNoException().isThrownBy(() -> listener().onMessage(redisMessage, null));
    }

    // ──────────────────────────── null сообщение ────────────────────────────

    @Test
    @DisplayName("Игнорирует null-сообщение (десериализация вернула null)")
    void should_ignoreNullMessage_when_deserializationReturnsNull() {
        when(messageSerializer.deserialize(DUMMY_BODY)).thenReturn(null);

        assertThatNoException().isThrownBy(() -> listener().onMessage(redisMessage, null));
    }

    // ──────────────────────────── fail-open: исключение при десериализации ────────────────────────────

    @Test
    @DisplayName("Fail-open: исключение при десериализации не роняет листенер")
    void should_notThrow_when_deserializationThrows() {
        when(messageSerializer.deserialize(DUMMY_BODY)).thenThrow(new RuntimeException("bad bytes"));

        assertThatNoException().isThrownBy(() -> listener().onMessage(redisMessage, null));
    }

    // ──────────────────────────── fail-open: исключение при обработке ────────────────────────────

    @Test
    @DisplayName("Fail-open: любое исключение при обработке сообщения не роняет листенер")
    void should_notThrow_when_processingThrows() {
        // Мокаем менеджер чтобы бросил при getOriginId
        TwoLevelCacheManager brokenManager = mock(TwoLevelCacheManager.class);
        when(brokenManager.getOriginId()).thenThrow(new RuntimeException("unexpected"));

        CacheInvalidationMessage msg = evictMsg(ORIGIN_B);
        when(messageSerializer.deserialize(DUMMY_BODY)).thenReturn(msg);

        CacheInvalidationListener listenerWithBrokenManager =
                new CacheInvalidationListener(brokenManager, messageSerializer);

        assertThatNoException().isThrownBy(
                () -> listenerWithBrokenManager.onMessage(redisMessage, null));
    }
}
