package com.plantcare.admin.storage.dto;

/**
 * Строка таблицы «Топ юзеров по объёму» на /admin/storage (issue #101).
 *
 * @param userId     internal user id (ссылка на /admin/users/{id})
 * @param chatId     telegram chat id
 * @param username   telegram username, может быть {@code null}
 * @param totalBytes объём в бакете, занятый фото этого юзера
 * @param photoCount количество его объектов в бакете
 */
public record TopUserStorageDto(
        long userId,
        Long chatId,
        String username,
        long totalBytes,
        long photoCount
) {

    /** «@username (chat_id)» или «(chat_id)» — как в остальных админ-таблицах. */
    public String userLabel() {
        String chat = chatId == null ? "?" : String.valueOf(chatId);
        if (username != null && !username.isBlank()) {
            return "@" + username + " (" + chat + ")";
        }
        return "(" + chat + ")";
    }

    public String totalHuman() {
        return ByteFormat.humanize(totalBytes);
    }
}
