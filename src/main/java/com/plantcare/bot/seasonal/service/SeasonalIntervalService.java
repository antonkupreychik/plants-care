package com.plantcare.bot.seasonal.service;

import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.Season;
import com.plantcare.bot.domain.enums.SeasonalOverride;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZonedDateTime;

/**
 * Центральная точка для расчёта «фактического» интервала ухода с учётом
 * сезонности (issue #67).
 *
 * <p>Поведение {@link #effectiveIntervalDays}:
 * <ol>
 *   <li>Если сезонность для этого растения выключена (по комбинации
 *       глобальной настройки и {@code plant.seasonalOverride}) → возвращаем
 *       базовый интервал как есть.</li>
 *   <li>Иначе определяем текущий сезон через {@link SeasonResolver}.</li>
 *   <li>В режиме MULTIPLIER: {@code round(base * multiplier(season))}.</li>
 *   <li>В режиме FIXED: возвращаем {@code interval_override_days(season)},
 *       если задан, иначе fallback на базовый интервал.</li>
 *   <li>Результат всегда clamp'ится в [{@value #MIN_INTERVAL_DAYS}..{@value #MAX_INTERVAL_DAYS}]
 *       чтобы случайные настройки не привели к 0-дневному циклу или
 *       полугодовому забвению.</li>
 * </ol>
 *
 * <p>Все методы детерминированы и без побочных эффектов: они только читают
 * настройки. Где использовать — в местах расчёта {@code next_due_at},
 * например после нажатия «Полил».
 */
@Service
@RequiredArgsConstructor
public class SeasonalIntervalService {

    public static final int MIN_INTERVAL_DAYS = 1;
    public static final int MAX_INTERVAL_DAYS = 60;

    private final SeasonResolver seasonResolver;

    /** Эффективный интервал для растения в его текущем сезоне. */
    public int effectiveIntervalDays(Plant plant, User user, int baseIntervalDays) {
        return effectiveIntervalDaysAt(plant, user, baseIntervalDays,
                ZonedDateTime.now());
    }

    /**
     * Для тестов и projection: позволяет передать конкретный момент времени.
     */
    public int effectiveIntervalDaysAt(Plant plant, User user, int baseIntervalDays,
                                       ZonedDateTime when) {
        if (!isSeasonalActive(plant, user)) {
            return clamp(baseIntervalDays);
        }
        Season season = seasonResolver.seasonAt(user, when);
        int seasonal = switch (user.getSeasonalMode()) {
            case MULTIPLIER -> applyMultiplier(baseIntervalDays, user, season);
            case FIXED      -> applyFixedOverride(baseIntervalDays, user, season);
        };
        return clamp(seasonal);
    }

    /**
     * Учитывает все три уровня решения «применять ли сезонность»:
     * глобальная настройка юзера + per-plant override.
     */
    public boolean isSeasonalActive(Plant plant, User user) {
        SeasonalOverride override = plant.getSeasonalOverride();
        if (override == null) override = SeasonalOverride.INHERIT;
        return switch (override) {
            case ON      -> true;                       // форс-вкл
            case OFF     -> false;                      // форс-выкл
            case INHERIT -> user.isSeasonalEnabled();   // как у юзера
        };
    }

    /** Хелпер для UI — какой сезон сейчас (для отображения «сейчас: лето»). */
    public Season currentSeason(User user) {
        return seasonResolver.currentSeason(user);
    }

    // ===================================================================
    // private
    // ===================================================================

    private int applyMultiplier(int base, User user, Season season) {
        BigDecimal m = season.isSummer()
                ? user.getSummerMultiplier()
                : user.getWinterMultiplier();
        if (m == null) return base;
        return BigDecimal.valueOf(base)
                .multiply(m)
                .setScale(0, RoundingMode.HALF_UP)
                .intValue();
    }

    private int applyFixedOverride(int base, User user, Season season) {
        Integer override = season.isSummer()
                ? user.getSummerIntervalOverrideDays()
                : user.getWinterIntervalOverrideDays();
        return override != null ? override : base;
    }

    private static int clamp(int v) {
        if (v < MIN_INTERVAL_DAYS) return MIN_INTERVAL_DAYS;
        if (v > MAX_INTERVAL_DAYS) return MAX_INTERVAL_DAYS;
        return v;
    }
}
