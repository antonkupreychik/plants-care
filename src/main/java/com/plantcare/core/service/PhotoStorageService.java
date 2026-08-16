package com.plantcare.core.service;

import java.net.URL;

/**
 * Хранилище бинарного контента фото в S3-совместимом бакете (issue #90, Slice A).
 *
 * <p>Работает на уровне «байты ↔ ключ объекта», метаданные ({@code Photo}-entity)
 * и бизнес-правила (валидация типа/размера, владелец) — уровнем выше, в
 * {@code PhotoService}. Реализация — {@link S3PhotoStorage}.
 */
public interface PhotoStorageService {

    /**
     * Загружает байты в бакет под сгенерированным namespaced-ключом.
     *
     * @param bytes       содержимое объекта
     * @param contentType MIME-тип ({@code image/...})
     * @return ключ созданного объекта и его размер
     */
    StoredPhoto put(byte[] bytes, String contentType);

    /**
     * Пресайн-URL на GET объекта по ключу (TTL из конфигурации).
     * URL временный и не требует авторизации у клиента.
     */
    URL presignedGetUrl(String storageKey);

    /** Удаляет объект из бакета по ключу. В Slice A на основном флоу не вызывается (soft-delete). */
    void delete(String storageKey);
}
