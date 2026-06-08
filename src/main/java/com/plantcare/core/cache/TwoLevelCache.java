package com.plantcare.core.cache;

import com.github.benmanes.caffeine.cache.Cache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.cache.support.AbstractValueAdaptingCache;
import org.springframework.cache.support.NullValue;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * Issue #281, эпик #277 фаза 3: двухуровневый Spring {@link org.springframework.cache.Cache}
 * поверх Caffeine L1 (локальный) и Redis L2 (общий между инстансами).
 *
 * <p>Чтение: L1 → L2 → загрузчик (БД). Попадание в L2 прогревает L1. Запись идёт в оба уровня.
 *
 * <p>L2-хранилище — Redis-хеш {@code <keyPrefix>cache:<name>} с полем = строковый ключ записи.
 * Это делает {@code clear()} одним {@code DEL}, а {@code evict(key)} — одним {@code HDEL}.
 * TTL L2 ставится/обновляется на хеше при каждой записи (expireAfterWrite-семантика).
 *
 * <p><b>Fail-open:</b> любая ошибка Redis (недоступность, таймаут, сериализация) перехватывается,
 * логируется и трактуется как промах L2 — чтение деградирует на L1 + загрузчик (БД), корректность
 * сохраняется, медленнее. Redis никогда не роняет операцию кеша.
 *
 * <p>{@code evict}/{@code clear} дополнительно публикуют сообщение инвалидации (через
 * {@code invalidationPublisher}), чтобы остальные инстансы почистили свой L1.
 */
@Slf4j
public class TwoLevelCache extends AbstractValueAdaptingCache {

    private final String name;
    private final Cache<Object, Object> l1;
    private final RedisTemplate<String, Object> redisTemplate;
    private final String redisHashKey;
    private final Duration l2Ttl;
    private final Consumer<CacheInvalidationMessage> invalidationPublisher;
    private final String originId;

    /**
     * @param name                  имя кеша (справочника)
     * @param l1                    локальный Caffeine-кеш
     * @param redisTemplate         шаблон для L2; {@code null} → L2 отключён (только L1, фоллбэк)
     * @param keyPrefix             namespacing-префикс ключей Redis (например {@code pc:})
     * @param l2Ttl                 TTL хеша L2
     * @param invalidationPublisher публикатор сообщений инвалидации в общий канал
     * @param originId              id текущего инстанса (для отсечения собственного эха)
     */
    public TwoLevelCache(
            String name,
            Cache<Object, Object> l1,
            @Nullable RedisTemplate<String, Object> redisTemplate,
            String keyPrefix,
            Duration l2Ttl,
            Consumer<CacheInvalidationMessage> invalidationPublisher,
            String originId) {
        super(true); // allowNullValues — кешируем «отсутствие» как NullValue
        this.name = name;
        this.l1 = l1;
        this.redisTemplate = redisTemplate;
        this.redisHashKey = keyPrefix + "cache:" + name;
        this.l2Ttl = l2Ttl;
        this.invalidationPublisher = invalidationPublisher;
        this.originId = originId;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Object getNativeCache() {
        return l1;
    }

    /**
     * Возвращает «сырое» (ещё не развёрнутое из {@link NullValue}) значение по ключу:
     * сначала L1, затем L2 (с прогревом L1), иначе {@code null} (промах).
     */
    @Override
    protected Object lookup(Object key) {
        Object l1Value = l1.getIfPresent(key);
        if (l1Value != null) {
            return l1Value;
        }

        Object l2Value = readFromL2(key);
        if (l2Value != null) {
            // прогреваем L1 из L2
            l1.put(key, l2Value);
            return l2Value;
        }

        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Callable<T> valueLoader) {
        Object existing = lookup(key);
        if (existing != null) {
            return (T) fromStoreValue(existing);
        }

        // загружаем из БД и кладём в оба уровня
        T loaded;
        try {
            loaded = valueLoader.call();
        } catch (Exception e) {
            throw new org.springframework.cache.Cache.ValueRetrievalException(key, valueLoader, e);
        }
        put(key, loaded);
        return loaded;
    }

    @Override
    public void put(Object key, @Nullable Object value) {
        Object storeValue = toStoreValue(value);
        l1.put(key, storeValue);
        writeToL2(key, storeValue);
    }

    @Override
    public void evict(Object key) {
        l1.invalidate(key);
        evictFromL2(key);
        publish(CacheInvalidationMessage.evict(originId, name, String.valueOf(key)));
    }

    @Override
    public void clear() {
        l1.invalidateAll();
        clearL2();
        publish(CacheInvalidationMessage.clear(originId, name));
    }

    // ──────────────────────────────────────────────────────────────────────
    // Локальная (L1-only) инвалидация — вызывается листенером Pub/Sub при получении
    // сообщения от ДРУГОГО инстанса. НЕ трогает L2 и НЕ публикует повторно (без эха).
    // ──────────────────────────────────────────────────────────────────────

    /** Чистит только L1 по ключу — реакция на внешнее сообщение инвалидации. */
    public void evictL1Local(Object key) {
        l1.invalidate(key);
    }

    /** Чистит весь L1 — реакция на внешнее сообщение clear. */
    public void clearL1Local() {
        l1.invalidateAll();
    }

    // ──────────────────────────── L2 (Redis) ────────────────────────────

    @Nullable
    private Object readFromL2(Object key) {
        if (redisTemplate == null) {
            return null;
        }
        try {
            return redisTemplate.opsForHash().get(redisHashKey, fieldOf(key));
        } catch (Exception e) {
            log.warn("Redis L2 unavailable on read (cache={}, key={}): {}. "
                    + "Degrading to L1+DB.", name, key, e.getMessage());
            return null;
        }
    }

    private void writeToL2(Object key, Object storeValue) {
        if (redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.opsForHash().put(redisHashKey, fieldOf(key), storeValue);
            // expireAfterWrite-семантика: обновляем TTL хеша на каждой записи
            redisTemplate.expire(redisHashKey, l2Ttl);
        } catch (Exception e) {
            log.warn("Redis L2 unavailable on write (cache={}, key={}): {}. "
                    + "Value kept in L1 only.", name, key, e.getMessage());
        }
    }

    private void evictFromL2(Object key) {
        if (redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.opsForHash().delete(redisHashKey, fieldOf(key));
        } catch (Exception e) {
            log.warn("Redis L2 unavailable on evict (cache={}, key={}): {}.", name, key, e.getMessage());
        }
    }

    private void clearL2() {
        if (redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.delete(redisHashKey);
        } catch (Exception e) {
            log.warn("Redis L2 unavailable on clear (cache={}): {}.", name, e.getMessage());
        }
    }

    private void publish(CacheInvalidationMessage message) {
        try {
            invalidationPublisher.accept(message);
        } catch (Exception e) {
            log.warn("Failed to publish cache invalidation (cache={}, key={}): {}.",
                    message.cacheName(), message.key(), e.getMessage());
        }
    }

    /** Поле хеша должно быть строкой — Spring-ключи могут быть произвольными объектами. */
    private static String fieldOf(Object key) {
        return String.valueOf(key);
    }
}
