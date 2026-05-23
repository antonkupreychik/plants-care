package com.plantcare.bot.domain.enums;

import java.time.Period;

/**
 * Частота фото-прогресса (issue #72).
 *
 * <p>Хранится в БД как VARCHAR(8) в колонке {@code plants.photo_progress_frequency}
 * (см. миграцию V16, CHECK по значениям этого enum).
 *
 * <ul>
 *     <li>{@link #OFF} — фото-прогресс выключен; {@code next_photo_due_at = NULL}.</li>
 *     <li>{@link #P2W} — раз в 2 недели.</li>
 *     <li>{@link #P1M} — раз в месяц.</li>
 * </ul>
 */
public enum PhotoProgressFrequency {

    OFF(null),
    P2W(Period.ofWeeks(2)),
    P1M(Period.ofMonths(1));

    private final Period period;

    PhotoProgressFrequency(Period period) {
        this.period = period;
    }

    /**
     * Период между фото. {@code null} для {@link #OFF}.
     */
    public Period getPeriod() {
        return period;
    }

    /**
     * {@code true}, если режим включён (есть период).
     */
    public boolean isEnabled() {
        return period != null;
    }
}
