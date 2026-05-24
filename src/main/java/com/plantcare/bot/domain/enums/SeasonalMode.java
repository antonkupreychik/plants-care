package com.plantcare.bot.domain.enums;

/**
 * Способ применения сезонности (issue #67).
 *
 * <ul>
 *   <li>{@link #MULTIPLIER} — умножаем базовый интервал растения на коэффициент
 *       сезона ({@code summer_multiplier} или {@code winter_multiplier}). Дефолтный
 *       режим: проще и предсказуемее, без необходимости настраивать каждое растение
 *       отдельно.</li>
 *   <li>{@link #FIXED} — для сезона используется фиксированный интервал
 *       ({@code summer_interval_override_days} / {@code winter_interval_override_days}).
 *       Если значение для сезона не задано (null) — fallback на базовый интервал
 *       растения. Удобно когда юзеру комфортнее думать «летом 5 дней, зимой 14»,
 *       а не в коэффициентах.</li>
 * </ul>
 */
public enum SeasonalMode {
    MULTIPLIER,
    FIXED
}
