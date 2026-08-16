package com.plantcare.admin.errors.dto;

import java.time.Instant;

/**
 * Строка топ-10 уникальных ошибок за период (issue #97): одна группа —
 * один {@code fingerprint} (см. {@code ErrorFingerprint}).
 *
 * @param fingerprint    ключ группы, он же ссылка-фильтр в полный список
 * @param exceptionClass класс исключения группы ({@code null} для событий без throwable)
 * @param sampleMessage  сообщение самой свежей ошибки группы
 * @param occurrences    сколько раз случилось за период
 * @param affectedUsers  скольких разных юзеров задело ({@code user_id IS NULL} не считается)
 * @param lastSeen       когда случилось в последний раз
 */
public record ErrorGroupDto(
        String fingerprint,
        String exceptionClass,
        String sampleMessage,
        long occurrences,
        long affectedUsers,
        Instant lastSeen
) {
}
