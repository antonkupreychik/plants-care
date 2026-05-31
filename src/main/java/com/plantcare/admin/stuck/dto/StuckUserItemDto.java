package com.plantcare.admin.stuck.dto;

import java.time.LocalDateTime;

/**
 * Одна строка таблицы /admin/stuck (issue #59).
 *
 * @param userId            internal user id (для action-URL)
 * @param chatId            telegram chat ID
 * @param username          telegram username (может быть null)
 * @param conversationState текущее состояние, например {@code AWAITING_PLANT_RENAME}
 * @param stateData         JSON state_data из БД, для отображения в {@code <details>}
 * @param updatedAt         когда state последний раз менялся (UTC)
 * @param stuckHours        сколько часов прошло с {@code updatedAt} — отображается в столбце
 */
public record StuckUserItemDto(
        long userId,
        long chatId,
        String username,
        String conversationState,
        String stateData,
        LocalDateTime updatedAt,
        long stuckHours
) {

    /** Telegram-сборная подпись юзера: «@username (chat_id)» или просто «(chat_id)». */
    public String userLabel() {
        if (username != null && !username.isBlank()) {
            return "@" + username + " (" + chatId + ")";
        }
        return "(" + chatId + ")";
    }

    /** Грубый разрядник для подсветки строк по «давности застревания». */
    public String severity() {
        if (stuckHours < 48) return "warning";   // 1-2 дня — лёгкая подсветка
        if (stuckHours < 168) return "error";    // 2 дня — неделя — серьёзно
        return "error";                          // > недели — то же красным
    }
}
