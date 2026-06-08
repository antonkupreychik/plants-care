package com.plantcare.core.cache;

import java.io.Serializable;

/**
 * Issue #281, эпик #277 фаза 3: сообщение инвалидации, рассылаемое по Redis Pub/Sub.
 *
 * <p>Когда инстанс выполняет {@code evict}/{@code clear} двухуровневого кеша, он публикует
 * это сообщение в общий канал. Остальные инстансы получают его и чистят свой локальный L1,
 * чтобы не отдавать устаревшие данные.
 *
 * @param originId уникальный id инстанса-отправителя; получатель отбрасывает собственное
 *                 эхо (свои же сообщения), чтобы не чистить L1 повторно зря
 * @param cacheName имя справочника-кеша, чей L1 нужно почистить
 * @param key       конкретный ключ для точечного evict; {@code null} означает clear всего кеша
 */
public record CacheInvalidationMessage(
        String originId,
        String cacheName,
        String key
) implements Serializable {

    /** Точечная инвалидация одной записи. */
    public static CacheInvalidationMessage evict(String originId, String cacheName, String key) {
        return new CacheInvalidationMessage(originId, cacheName, key);
    }

    /** Полная очистка кеша (key == null). */
    public static CacheInvalidationMessage clear(String originId, String cacheName) {
        return new CacheInvalidationMessage(originId, cacheName, null);
    }

    /** {@code true}, если сообщение про полную очистку кеша (без конкретного ключа). */
    public boolean isClear() {
        return key == null;
    }
}
