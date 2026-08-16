package com.plantcare.core.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.plantcare.core.config.RedisProperties;
import com.plantcare.core.config.TwoLevelCacheProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.lang.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Issue #281, эпик #277 фаза 3: {@link CacheManager} двухуровневых кешей справочников.
 *
 * <p>На каждый справочник — один {@link TwoLevelCache} (Caffeine L1 + Redis L2). Менеджер
 * создаёт кеши лениво по имени (как {@code CaffeineCacheManager}), владеет общим
 * {@code originId} инстанса и публикует сообщения инвалидации в Redis Pub/Sub-канал —
 * чтобы {@code evict}/{@code clear} в одном инстансе чистили L1 на остальных.
 *
 * <p>Публикация в Redis обёрнута в try/catch (fail-open): недоступность Redis не должна
 * ронять операцию кеша, локальные L1/L2 уже обновлены к моменту публикации.
 */
@Slf4j
public class TwoLevelCacheManager implements CacheManager {

    private final ConcurrentMap<String, Cache> caches = new ConcurrentHashMap<>();

    private final TwoLevelCacheProperties properties;
    private final RedisProperties redisProperties;
    /** {@code null} → L2/Pub-Sub отключены (но менеджер всё равно работает как чистый L1). */
    @Nullable
    private final RedisTemplate<String, Object> redisTemplate;

    /** Стабильный на время жизни процесса id инстанса — отсекает собственное эхо инвалидации. */
    private final String originId = UUID.randomUUID().toString();

    /**
     * Выделенный типизированный сериализатор сообщений инвалидации. Не зависит от
     * default-typing режима основного {@code RedisTemplate} (где record как final-тип не
     * получил бы {@code @class}-маркер и десериализовался бы в Map). Тот же сериализатор
     * использует {@link CacheInvalidationListener} на принимающей стороне.
     */
    private static final RedisSerializer<CacheInvalidationMessage> MESSAGE_SERIALIZER =
            new JacksonJsonRedisSerializer<>(CacheInvalidationMessage.class);

    /** Сериализатор сообщений инвалидации — для переиспользования листенером. */
    public static RedisSerializer<CacheInvalidationMessage> messageSerializer() {
        return MESSAGE_SERIALIZER;
    }

    public TwoLevelCacheManager(
            TwoLevelCacheProperties properties,
            RedisProperties redisProperties,
            @Nullable RedisTemplate<String, Object> redisTemplate,
            Collection<String> cacheNames) {
        this.properties = properties;
        this.redisProperties = redisProperties;
        this.redisTemplate = redisTemplate;
        for (String name : cacheNames) {
            caches.put(name, createCache(name));
        }
    }

    /** Id текущего инстанса — листенер сверяет его, чтобы не обрабатывать своё эхо. */
    public String getOriginId() {
        return originId;
    }

    @Override
    @Nullable
    public Cache getCache(String name) {
        return caches.computeIfAbsent(name, this::createCache);
    }

    @Override
    public Collection<String> getCacheNames() {
        return List.copyOf(caches.keySet());
    }

    /**
     * Возвращает кеш по имени без создания нового (используется листенером инвалидации:
     * чистить L1 имеет смысл только у уже существующего кеша).
     */
    @Nullable
    TwoLevelCache findExisting(String name) {
        Cache cache = caches.get(name);
        return (cache instanceof TwoLevelCache tlc) ? tlc : null;
    }

    private Cache createCache(String name) {
        var l1 = Caffeine.newBuilder()
                .expireAfterWrite(properties.l1Ttl())
                .maximumSize(properties.l1MaxSize())
                .build();

        return new TwoLevelCache(
                name,
                l1,
                redisTemplate,
                redisProperties.keyPrefix(),
                properties.l2Ttl(),
                this::publishInvalidation,
                originId);
    }

    /**
     * Публикует сообщение инвалидации в общий Redis-канал. Fail-open: ошибка Redis
     * логируется и проглатывается — локальные уровни уже консистентны.
     */
    private void publishInvalidation(CacheInvalidationMessage message) {
        if (redisTemplate == null) {
            return;
        }
        try {
            byte[] payload = MESSAGE_SERIALIZER.serialize(message);
            redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection -> {
                connection.publish(
                        properties.invalidationChannel().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        payload);
                return null;
            });
        } catch (Exception e) {
            log.warn("Failed to publish cache invalidation to channel {} (cache={}): {}. "
                            + "Other instances may serve stale L1 until their TTL expires.",
                    properties.invalidationChannel(), message.cacheName(), e.getMessage());
        }
    }
}
