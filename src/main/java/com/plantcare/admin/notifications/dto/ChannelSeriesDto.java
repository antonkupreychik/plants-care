package com.plantcare.admin.notifications.dto;

import java.util.List;

/**
 * Ряд почасового графика по одному каналу (issue #95): значения выровнены по
 * общему списку меток {@code NotificationHealthDto.chartLabels()}, дырки забиты
 * нулями — иначе Chart.js нарисует рваную линию.
 *
 * @param channel канал доставки
 * @param counts  количество попыток по часам
 */
public record ChannelSeriesDto(String channel, List<Long> counts) {
}
