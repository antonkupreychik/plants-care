package com.plantcare.admin.notifications.dto;

import java.time.Instant;

/**
 * Юзер с серией неудачных доставок подряд в одном канале (issue #95).
 *
 * <p>«Подряд» = после последней успешной доставки в этот канал. Серия считается
 * в пределах окна наблюдения, последний успех ищется по всей истории — успех
 * месячной давности всё равно обрывает серию.
 *
 * @param userId               id пользователя
 * @param username             telegram-username (может быть {@code null})
 * @param telegramChatId       chat_id (может быть {@code null} у mobile-only юзера)
 * @param channel              канал, в котором копятся фейлы
 * @param consecutiveFailures  длина серии
 * @param lastErrorCode        код последней ошибки серии
 * @param lastFailureAt        момент последней ошибки серии
 * @param deviceCount          сколько push-устройств зарегистрировано у юзера
 */
public record ProblemUserDto(
        long userId,
        String username,
        Long telegramChatId,
        String channel,
        long consecutiveFailures,
        String lastErrorCode,
        Instant lastFailureAt,
        long deviceCount
) {

    /** Человекочитаемая подпись юзера для таблицы: {@code @nick (123)} либо {@code #123}. */
    public String userLabel() {
        if (username != null && !username.isBlank()) {
            return "@" + username + " (" + userId + ")";
        }
        return "#" + userId;
    }

    /** Есть ли смысл предлагать «отписать токены» — только когда устройства реально есть. */
    public boolean hasDevices() {
        return deviceCount > 0;
    }

    /** Доступен ли Telegram как запасной канал для тестового уведомления. */
    public boolean hasTelegram() {
        return telegramChatId != null;
    }
}
