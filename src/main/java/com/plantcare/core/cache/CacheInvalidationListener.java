package com.plantcare.core.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.serializer.RedisSerializer;

/**
 * Issue #281, эпик #277 фаза 3: слушатель Redis Pub/Sub-канала инвалидации кеша.
 *
 * <p>Когда любой инстанс выполняет {@code evict}/{@code clear} двухуровневого кеша, он
 * публикует {@link CacheInvalidationMessage}. Этот листенер на остальных инстансах ловит
 * сообщение и чистит соответствующий <b>локальный L1</b> (L2 общий — его уже обновил
 * инстанс-источник). Так инвалидация в одном инстансе становится видна другим.
 *
 * <p>Собственное эхо (сообщения с тем же {@code originId}) отбрасывается: свой L1 уже
 * почищен синхронно в {@link TwoLevelCache#evict}/{@link TwoLevelCache#clear}.
 *
 * <p>Любая ошибка разбора/обработки логируется и проглатывается — сбой инвалидации не должен
 * ронять листенер-контейнер (худший случай — L1 живёт до истечения собственного TTL).
 */
@Slf4j
public class CacheInvalidationListener implements MessageListener {

    private final TwoLevelCacheManager cacheManager;
    private final RedisSerializer<CacheInvalidationMessage> messageSerializer;

    public CacheInvalidationListener(
            TwoLevelCacheManager cacheManager,
            RedisSerializer<CacheInvalidationMessage> messageSerializer) {
        this.cacheManager = cacheManager;
        this.messageSerializer = messageSerializer;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            CacheInvalidationMessage msg = messageSerializer.deserialize(message.getBody());
            if (msg == null) {
                log.debug("Ignoring empty cache invalidation message");
                return;
            }

            // отсекаем собственное эхо
            if (cacheManager.getOriginId().equals(msg.originId())) {
                return;
            }

            TwoLevelCache cache = cacheManager.findExisting(msg.cacheName());
            if (cache == null) {
                // на этом инстансе такой кеш ещё не создан — чистить нечего
                return;
            }

            if (msg.isClear()) {
                cache.clearL1Local();
                log.debug("L1 cleared (cache={}) by remote invalidation from {}",
                        msg.cacheName(), msg.originId());
            } else {
                cache.evictL1Local(msg.key());
                log.debug("L1 evicted (cache={}, key={}) by remote invalidation from {}",
                        msg.cacheName(), msg.key(), msg.originId());
            }
        } catch (Exception e) {
            log.warn("Failed to process cache invalidation message: {}", e.getMessage());
        }
    }
}
