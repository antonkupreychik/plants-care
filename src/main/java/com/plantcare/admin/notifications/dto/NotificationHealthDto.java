package com.plantcare.admin.notifications.dto;

import java.time.Instant;
import java.util.List;

/**
 * Полный снапшот health-дашборда каналов уведомлений (issue #95).
 *
 * @param hours         окно наблюдения в часах
 * @param channelFilter активный фильтр по каналу ({@code all} — без фильтра)
 * @param generatedAt   момент сборки снапшота (для «обновлено в …» при HTMX-поллинге)
 * @param channels      карточки по каналам (всегда все каналы, даже с нулями)
 * @param chartLabels   подписи часов для графика
 * @param chartSeries   ряды графика по каналам
 * @param chartDataJson те же labels/series, сериализованные для Chart.js
 * @param topErrors     топ кодов ошибок за окно
 * @param problemUsers  юзеры с серией фейлов подряд
 */
public record NotificationHealthDto(
        int hours,
        String channelFilter,
        Instant generatedAt,
        List<ChannelHealthDto> channels,
        List<String> chartLabels,
        List<ChannelSeriesDto> chartSeries,
        String chartDataJson,
        List<ErrorCodeCountDto> topErrors,
        List<ProblemUserDto> problemUsers
) {

    /** Каналы, по которым сработал порог error rate — то, что выводится красной плашкой. */
    public List<ChannelHealthDto> alertingChannels() {
        return channels.stream().filter(ChannelHealthDto::alerting).toList();
    }

    /** Есть ли хоть один канал в алерте. */
    public boolean hasAlerts() {
        return !alertingChannels().isEmpty();
    }

    /** Суммарно попыток доставки за окно — чтобы шаблон мог отличить «тихо» от «всё ок». */
    public long totalAttempts() {
        return channels.stream().mapToLong(ChannelHealthDto::total).sum();
    }
}
