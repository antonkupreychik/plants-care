package com.plantcare.core.seasonal.service;

import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.Season;
import com.plantcare.core.domain.enums.SeasonalMode;
import com.plantcare.core.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Per-season настройки сезонности для REST API ({@code /api/v1/me/seasonal}, issue #256).
 *
 * <p>Паритет с ботом ({@link com.plantcare.bot.seasonal.service.SeasonalMenuService}):
 * на стороне бота те же поля {@code summerMultiplier}/{@code winterMultiplier} и
 * {@code summerIntervalOverrideDays}/{@code winterIntervalOverrideDays} меняются через
 * inline-кнопки. Здесь — тот же набор операций, но через явные значения от мобильного
 * клиента, а не циклический перебор шагов.
 *
 * <p>Глобальный флаг {@code seasonalEnabled} и {@code seasonalMode} здесь только читаются
 * (для контекста ответа); меняются они через {@code PATCH /api/v1/me}
 * ({@link com.plantcare.core.service.UserProfileService}).
 *
 * <p>Реально применяемое значение к расчёту {@code next_due_at} считает
 * {@link SeasonalIntervalService}; этот сервис только хранит per-season настройки.
 * Внешних вызовов (Telegram) внутри транзакции нет — только чтение/запись БД.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeasonalSettingsService {

    /** Границы множителя совпадают с UI бота (шаги 0.50..1.50). */
    static final BigDecimal MIN_MULTIPLIER = new BigDecimal("0.50");
    static final BigDecimal MAX_MULTIPLIER = new BigDecimal("1.50");

    private final UserRepository userRepository;

    /** Per-season настройки одного сезона (множитель + опциональный fixed-интервал). */
    public record SeasonSetting(Season season, BigDecimal multiplier, Integer intervalDays) {
    }

    /** Снимок настроек сезонности пользователя для {@code GET /api/v1/me/seasonal}. */
    public record SeasonalSettings(boolean enabled, SeasonalMode mode, List<SeasonSetting> seasons) {
    }

    /** Текущие per-season настройки пользователя по обоим сезонам. */
    @Transactional(readOnly = true)
    public SeasonalSettings getSettings(User user) {
        return new SeasonalSettings(
                user.isSeasonalEnabled(),
                user.getSeasonalMode(),
                List.of(
                        toSetting(user, Season.SUMMER),
                        toSetting(user, Season.WINTER)
                )
        );
    }

    /**
     * Задаёт множитель и/или fixed-интервал для одного сезона. {@code null}-поле = «не менять».
     * Множитель clamp'ится в [{@link #MIN_MULTIPLIER}..{@link #MAX_MULTIPLIER}] и округляется до
     * сотых; интервал — в [{@value SeasonalIntervalService#MIN_INTERVAL_DAYS}..{@value
     * SeasonalIntervalService#MAX_INTERVAL_DAYS}], как в боте.
     */
    @Transactional
    public SeasonalSettings updateSeason(User user, Season season,
                                         BigDecimal multiplier, Integer intervalDays) {
        if (multiplier == null && intervalDays == null) {
            throw new IllegalArgumentException(
                    "At least one of multiplier/intervalDays must be provided");
        }
        if (multiplier != null) {
            BigDecimal value = normalizeMultiplier(multiplier);
            if (season.isSummer()) {
                user.setSummerMultiplier(value);
            } else {
                user.setWinterMultiplier(value);
            }
        }
        if (intervalDays != null) {
            int value = clampInterval(intervalDays);
            if (season.isSummer()) {
                user.setSummerIntervalOverrideDays(value);
            } else {
                user.setWinterIntervalOverrideDays(value);
            }
        }
        userRepository.save(user);
        log.info("Seasonal settings updated via REST: userId={}, season={}, multiplierSet={}, intervalSet={}",
                user.getId(), season, multiplier != null, intervalDays != null);

        return getSettings(user);
    }

    /**
     * Сбрасывает fixed-интервал сезона в {@code null} — сезон возвращается к дефолту
     * (в режиме FIXED используется базовый интервал растения). Идемпотентно.
     */
    @Transactional
    public SeasonalSettings clearInterval(User user, Season season) {
        if (season.isSummer()) {
            user.setSummerIntervalOverrideDays(null);
        } else {
            user.setWinterIntervalOverrideDays(null);
        }
        userRepository.save(user);
        log.info("Seasonal interval cleared via REST: userId={}, season={}", user.getId(), season);

        return getSettings(user);
    }

    private static SeasonSetting toSetting(User user, Season season) {
        BigDecimal multiplier = season.isSummer()
                ? user.getSummerMultiplier()
                : user.getWinterMultiplier();
        Integer intervalDays = season.isSummer()
                ? user.getSummerIntervalOverrideDays()
                : user.getWinterIntervalOverrideDays();
        return new SeasonSetting(season, multiplier, intervalDays);
    }

    private static BigDecimal normalizeMultiplier(BigDecimal multiplier) {
        BigDecimal scaled = multiplier.setScale(2, RoundingMode.HALF_UP);
        if (scaled.compareTo(MIN_MULTIPLIER) < 0) return MIN_MULTIPLIER;
        if (scaled.compareTo(MAX_MULTIPLIER) > 0) return MAX_MULTIPLIER;
        return scaled;
    }

    private static int clampInterval(int days) {
        if (days < SeasonalIntervalService.MIN_INTERVAL_DAYS) {
            return SeasonalIntervalService.MIN_INTERVAL_DAYS;
        }
        if (days > SeasonalIntervalService.MAX_INTERVAL_DAYS) {
            return SeasonalIntervalService.MAX_INTERVAL_DAYS;
        }
        return days;
    }
}
