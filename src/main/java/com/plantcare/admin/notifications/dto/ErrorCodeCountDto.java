package com.plantcare.admin.notifications.dto;

/**
 * Строка блока «Топ ошибок» (issue #95).
 *
 * @param channel   канал доставки
 * @param errorCode код вида {@code telegram:403} / {@code fcm:UnregisteredDevice}
 * @param count     сколько раз встретился за окно
 */
public record ErrorCodeCountDto(String channel, String errorCode, long count) {
}
