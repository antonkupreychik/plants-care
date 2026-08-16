package com.plantcare.admin.notifications.dto;

/**
 * Карточка одного канала за окно наблюдения (issue #95).
 *
 * @param channel      канал доставки (TELEGRAM / PUSH)
 * @param total        всего попыток доставки
 * @param sent         принято провайдером
 * @param failed       отвергнуто провайдером
 * @param rateLimited  отвергнуто по лимиту (429)
 */
public record ChannelHealthDto(
        String channel,
        long total,
        long sent,
        long failed,
        long rateLimited
) {

    /**
     * Порог алерта: доля ошибок строго выше этого значения красит канал в красное
     * (AC issue #95 — «error rate &gt;5% по каналу»).
     */
    public static final double ERROR_RATE_ALERT_THRESHOLD = 5.0d;

    /** Пустая карточка — канал за окно не отправлял ничего. */
    public static ChannelHealthDto empty(String channel) {
        return new ChannelHealthDto(channel, 0, 0, 0, 0);
    }

    /** Всё, что не {@code SENT}. */
    public long errors() {
        return failed + rateLimited;
    }

    /** Доля успеха в процентах; при нулевом окне — 100 (нечему ломаться). */
    public double successRate() {
        return total == 0 ? 100.0d : (sent * 100.0d) / total;
    }

    /** Доля ошибок в процентах; при нулевом окне — 0. */
    public double errorRate() {
        return total == 0 ? 0.0d : (errors() * 100.0d) / total;
    }

    /**
     * Канал в алерте. Нулевое окно алертом не считается: «ничего не слали» — это
     * не «всё сломалось», такой сигнал ловит health шедулера на дашборде.
     */
    public boolean alerting() {
        return total > 0 && errorRate() > ERROR_RATE_ALERT_THRESHOLD;
    }

    /** Округлённый до одного знака success rate — для шаблона. */
    public String successRateLabel() {
        return String.format(java.util.Locale.ROOT, "%.1f", successRate());
    }

    /** Округлённый до одного знака error rate — для шаблона. */
    public String errorRateLabel() {
        return String.format(java.util.Locale.ROOT, "%.1f", errorRate());
    }
}
