package com.plantcare.core.cache;

import com.plantcare.core.config.RedisProperties;
import com.plantcare.core.config.TwoLevelCacheProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TwoLevelCacheManager — менеджер двухуровневых кешей")
class TwoLevelCacheManagerTest {

    // Свойства с дефолтами через compact constructor (все null → defaults)
    private final TwoLevelCacheProperties properties =
            new TwoLevelCacheProperties(null, null, null, null, null, null, null);
    private final RedisProperties redisProperties =
            new RedisProperties(null, null, null);

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    @SuppressWarnings("unchecked")
    private org.springframework.data.redis.core.HashOperations<String, Object, Object> hashOps;

    private TwoLevelCacheManager buildManager(List<String> cacheNames) {
        // lenient: not every test that builds a manager with Redis actually does cache I/O
        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOps);
        return new TwoLevelCacheManager(properties, redisProperties, redisTemplate, cacheNames);
    }

    private TwoLevelCacheManager buildManagerNoL2(List<String> cacheNames) {
        return new TwoLevelCacheManager(properties, redisProperties, null, cacheNames);
    }

    // ──────────────────────────── construction / lazy creation ────────────────────────────

    @Test
    @DisplayName("Кеши из конструктора создаются сразу и доступны через getCache")
    void should_createCachesUpFront_when_constructedWithCacheNames() {
        TwoLevelCacheManager manager = buildManager(List.of("plants", "species"));

        Cache plants = manager.getCache("plants");
        Cache species = manager.getCache("species");

        assertThat(plants).isInstanceOf(TwoLevelCache.class);
        assertThat(species).isInstanceOf(TwoLevelCache.class);
        assertThat(plants.getName()).isEqualTo("plants");
        assertThat(species.getName()).isEqualTo("species");
    }

    @Test
    @DisplayName("getCache создаёт кеш лениво при первом обращении к неизвестному имени")
    void should_createCacheLazily_when_getCalledForUnknownName() {
        TwoLevelCacheManager manager = buildManager(List.of());

        Cache newCache = manager.getCache("on-demand");

        assertThat(newCache).isInstanceOf(TwoLevelCache.class);
        assertThat(newCache.getName()).isEqualTo("on-demand");
    }

    @Test
    @DisplayName("getCache возвращает тот же экземпляр при повторном вызове")
    void should_returnSameInstance_when_getCalledTwiceForSameName() {
        TwoLevelCacheManager manager = buildManager(List.of("plants"));

        Cache first = manager.getCache("plants");
        Cache second = manager.getCache("plants");

        assertThat(first).isSameAs(second);
    }

    @Test
    @DisplayName("getCacheNames возвращает имена всех зарегистрированных кешей")
    void should_returnAllCacheNames_when_getCacheNamesCalled() {
        TwoLevelCacheManager manager = buildManager(List.of("plants", "species", "rooms"));

        assertThat(manager.getCacheNames()).containsExactlyInAnyOrder("plants", "species", "rooms");
    }

    // ──────────────────────────── findExisting ────────────────────────────

    @Test
    @DisplayName("findExisting возвращает существующий кеш как TwoLevelCache")
    void should_returnTwoLevelCache_when_findExistingCalledForKnownName() {
        TwoLevelCacheManager manager = buildManager(List.of("plants"));

        TwoLevelCache found = manager.findExisting("plants");

        assertThat(found).isNotNull();
        assertThat(found.getName()).isEqualTo("plants");
    }

    @Test
    @DisplayName("findExisting возвращает null для имени, которого нет в менеджере")
    void should_returnNull_when_findExistingCalledForUnknownName() {
        TwoLevelCacheManager manager = buildManager(List.of("plants"));

        TwoLevelCache found = manager.findExisting("nonexistent");

        assertThat(found).isNull();
    }

    // ──────────────────────────── originId ────────────────────────────

    @Test
    @DisplayName("getOriginId возвращает стабильный непустой id инстанса")
    void should_haveStableNonNullOriginId() {
        TwoLevelCacheManager manager = buildManager(List.of());

        String id1 = manager.getOriginId();
        String id2 = manager.getOriginId();

        assertThat(id1).isNotBlank();
        assertThat(id1).isEqualTo(id2);
    }

    @Test
    @DisplayName("У двух разных менеджеров разные originId")
    void should_haveDifferentOriginIds_when_twoManagersCreated() {
        TwoLevelCacheManager manager1 = buildManager(List.of());
        TwoLevelCacheManager manager2 = buildManager(List.of());

        assertThat(manager1.getOriginId()).isNotEqualTo(manager2.getOriginId());
    }

    // ──────────────────────────── L2 disabled (null redisTemplate) ────────────────────────────

    @Test
    @DisplayName("L2 отключён (null redisTemplate) — менеджер работает как чистый L1")
    void should_workAsL1Only_when_redisTemplateIsNull() {
        TwoLevelCacheManager manager = buildManagerNoL2(List.of("plants"));

        Cache cache = manager.getCache("plants");

        assertThat(cache).isInstanceOf(TwoLevelCache.class);
        // Кешируем что-то — не должно падать
        assertThatNoException().isThrownBy(() -> cache.put("key", "value"));
        assertThat(cache.get("key")).isNotNull();
        assertThat(cache.get("key").get()).isEqualTo("value");
    }

    @Test
    @DisplayName("L2 отключён — findExisting работает корректно")
    void should_findExistingWork_when_redisTemplateIsNull() {
        TwoLevelCacheManager manager = buildManagerNoL2(List.of("plants"));

        TwoLevelCache found = manager.findExisting("plants");

        assertThat(found).isNotNull();
        assertThat(found.getName()).isEqualTo("plants");
    }

    // ──────────────────────────── publishInvalidation fail-open ────────────────────────────

    @Test
    @DisplayName("Fail-open: Redis падает при публикации инвалидации — исключение не пробрасывается")
    void should_notThrow_when_redisThrowsOnPublishInvalidation() {
        TwoLevelCacheManager manager = buildManager(List.of("plants"));

        // Инициируем evict — он вызывает publishInvalidation внутри TwoLevelCache
        Cache cache = manager.getCache("plants");
        // Организуем падение при publish через execute
        doThrow(new RuntimeException("Redis pub/sub unavailable"))
                .when(redisTemplate).execute(any(org.springframework.data.redis.core.RedisCallback.class));

        assertThatNoException().isThrownBy(() -> cache.evict("some-key"));
    }

    // ──────────────────────────── messageSerializer ────────────────────────────

    @Test
    @DisplayName("messageSerializer возвращает непустой сериализатор")
    void should_returnNonNullSerializer_when_messageSerializerCalled() {
        assertThat(TwoLevelCacheManager.messageSerializer()).isNotNull();
    }

    @Test
    @DisplayName("messageSerializer сериализует и десериализует CacheInvalidationMessage симметрично")
    void should_serializeAndDeserializeSymmetrically_when_messageSerializerUsed() {
        var serializer = TwoLevelCacheManager.messageSerializer();
        CacheInvalidationMessage original = CacheInvalidationMessage.evict("origin-1", "plants", "key-42");

        byte[] bytes = serializer.serialize(original);
        CacheInvalidationMessage deserialized = serializer.deserialize(bytes);

        assertThat(deserialized).isNotNull();
        assertThat(deserialized.originId()).isEqualTo("origin-1");
        assertThat(deserialized.cacheName()).isEqualTo("plants");
        assertThat(deserialized.key()).isEqualTo("key-42");
        assertThat(deserialized.isClear()).isFalse();
    }

    @Test
    @DisplayName("messageSerializer сериализует clear-сообщение (key=null) корректно")
    void should_serializeClearMessage_when_keyIsNull() {
        var serializer = TwoLevelCacheManager.messageSerializer();
        CacheInvalidationMessage clearMsg = CacheInvalidationMessage.clear("origin-2", "species");

        byte[] bytes = serializer.serialize(clearMsg);
        CacheInvalidationMessage deserialized = serializer.deserialize(bytes);

        assertThat(deserialized).isNotNull();
        assertThat(deserialized.isClear()).isTrue();
        assertThat(deserialized.key()).isNull();
    }
}
