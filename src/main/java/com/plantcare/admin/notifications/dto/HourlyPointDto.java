package com.plantcare.admin.notifications.dto;

import java.time.Instant;

/**
 * Сырая точка почасового графика, как её отдаёт SQL (issue #95):
 * одна строка на пару (час, канал).
 *
 * @param bucket начало часа (UTC)
 * @param channel канал доставки
 * @param total всего попыток в этом часу
 * @param failed из них неуспешных
 */
public record HourlyPointDto(Instant bucket, String channel, long total, long failed) {
}
