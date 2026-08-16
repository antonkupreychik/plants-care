package com.plantcare.admin.storage.dto;

import java.time.Instant;

/**
 * Одно фото в админке (issue #101): и строка «последних загрузок» на
 * /admin/storage, и плитка грида «Фото» на странице юзера — набор полей у них
 * один и тот же, отдельные record'ы были бы копипастой.
 *
 * <p>{@code previewUrl} — пресайн-ссылка на GET объекта, {@code null}, если
 * бакет не сконфигурирован (dev/тесты) или объект уже физически вычищен.
 * Шаблон в этом случае рисует плейсхолдер, а не битую картинку.
 *
 * @param photoId     photos.id
 * @param userId      владелец
 * @param chatId      telegram chat id владельца
 * @param username    telegram username владельца, может быть {@code null}
 * @param plantId     растение из таймлайна прогресса, {@code null} если фото ни к чему не привязано
 * @param plantName   имя этого растения, {@code null} вместе с {@code plantId}
 * @param storageKey  ключ объекта в бакете — нужен, чтобы подписать превью;
 *                    в шаблон не рендерится
 * @param contentType MIME-тип
 * @param sizeBytes   размер объекта
 * @param createdAt   момент загрузки (UTC)
 * @param deletedAt   момент soft-delete, {@code null} — активно
 * @param purgedAt    момент физической чистки из бакета, {@code null} — бинарь на месте
 * @param previewUrl  пресайн-ссылка или {@code null}
 */
public record PhotoRowDto(
        long photoId,
        long userId,
        Long chatId,
        String username,
        Long plantId,
        String plantName,
        String storageKey,
        String contentType,
        long sizeBytes,
        Instant createdAt,
        Instant deletedAt,
        Instant purgedAt,
        String previewUrl
) {

    /** Копия с подставленной пресайн-ссылкой — DTO иммутабелен, URL добирается сервисом. */
    public PhotoRowDto withPreviewUrl(String url) {
        return new PhotoRowDto(photoId, userId, chatId, username, plantId, plantName,
                storageKey, contentType, sizeBytes, createdAt, deletedAt, purgedAt, url);
    }

    public boolean deleted() {
        return deletedAt != null;
    }

    public boolean purged() {
        return purgedAt != null;
    }

    /** Три состояния фото — ими же красится плитка/строка. */
    public String status() {
        if (purgedAt != null) return "purged";
        if (deletedAt != null) return "deleted";
        return "active";
    }

    public String userLabel() {
        String chat = chatId == null ? "?" : String.valueOf(chatId);
        if (username != null && !username.isBlank()) {
            return "@" + username + " (" + chat + ")";
        }
        return "(" + chat + ")";
    }

    public String sizeHuman() {
        return ByteFormat.humanize(sizeBytes);
    }
}
