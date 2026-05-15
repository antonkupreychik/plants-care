package com.plantcare.bot.domain.enums;

/**
 * Кому уйдёт рассылка (issue #60).
 * Хранится в БД как {@code admin_broadcasts.audience_filter} (VARCHAR).
 */
public enum AudienceFilter {

    /** Все юзеры, кроме заблокированных. paused получают. */
    ALL_EXCEPT_BLOCKED,

    /** Только «онлайн»: не blocked И не paused. Рекомендуемый default. */
    ONLINE,

    /** Подмножество по таймзонам (timezones, через {@code ,}). */
    BY_TIMEZONE
}
