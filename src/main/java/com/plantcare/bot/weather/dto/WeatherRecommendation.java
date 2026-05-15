package com.plantcare.bot.weather.dto;

/**
 * Что бот рекомендует юзеру по поводу полива на основании текущей влажности
 * за окном (issue #69). Пороги выбраны простыми:
 * <ul>
 *   <li>{@link #DEFER_OK}      — RH ≥ 80%: «можно отложить, если земля ещё влажная»</li>
 *   <li>{@link #DO_NOT_DEFER}  — RH ≤ 35%: «лучше не откладывать»</li>
 *   <li>{@link #NEUTRAL}       — 35% &lt; RH &lt; 80%: без дополнительных подсказок</li>
 * </ul>
 *
 * <p>Этот enum намеренно не отдаёт сам текст — текст строится в местах вызова
 * (меню/push) с учётом контекста ({@link #shortText()} только для тостов/badge).
 */
public enum WeatherRecommendation {
    DEFER_OK,
    DO_NOT_DEFER,
    NEUTRAL;

    public boolean isDeferOk()     { return this == DEFER_OK; }
    public boolean isDoNotDefer()  { return this == DO_NOT_DEFER; }
    public boolean isNeutral()     { return this == NEUTRAL; }

    /** Короткий маркер для UI (без полной фразы). */
    public String shortText() {
        return switch (this) {
            case DEFER_OK     -> "можно отложить";
            case DO_NOT_DEFER -> "лучше не откладывать";
            case NEUTRAL      -> "влажность нормальная";
        };
    }
}
