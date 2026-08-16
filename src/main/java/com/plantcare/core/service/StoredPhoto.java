package com.plantcare.core.service;

/**
 * Результат загрузки объекта в бакет (issue #90, Slice A): namespaced-ключ и размер.
 *
 * @param storageKey ключ объекта в бакете (например, {@code photos/<uuid>})
 * @param sizeBytes  размер записанного объекта в байтах
 */
public record StoredPhoto(String storageKey, long sizeBytes) {
}
