package com.plantcare.core.seasonal.service;

import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.Season;
import com.plantcare.core.domain.enums.SeasonalMode;
import com.plantcare.core.domain.enums.SeasonalOverride;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SeasonalIntervalService — расчёт эффективного интервала (issue #67)")
class SeasonalIntervalServiceTest {

    @Mock private SeasonResolver seasonResolver;

    private SeasonalIntervalService service;
    private User user;
    private Plant plant;

    @BeforeEach
    void setUp() {
        service = new SeasonalIntervalService(seasonResolver, java.time.Clock.systemUTC());

        user = User.builder()
                .telegramChatId(100L)
                .timezone("Europe/Minsk")
                .seasonalEnabled(true)
                .seasonalMode(SeasonalMode.MULTIPLIER)
                .summerMultiplier(new BigDecimal("0.80"))
                .winterMultiplier(new BigDecimal("1.20"))
                .build();

        plant = Plant.builder()
                .name("Monstera")
                .seasonalOverride(SeasonalOverride.INHERIT)
                .build();
    }

    @Nested
    @DisplayName("isSeasonalActive — combined logic")
    class ActiveLogic {

        @Test
        @DisplayName("global=ON, plant=INHERIT → активна")
        void globalOnInheritOn() {
            user.setSeasonalEnabled(true);
            plant.setSeasonalOverride(SeasonalOverride.INHERIT);
            assertThat(service.isSeasonalActive(plant, user)).isTrue();
        }

        @Test
        @DisplayName("global=OFF, plant=INHERIT → не активна")
        void globalOffInheritOff() {
            user.setSeasonalEnabled(false);
            plant.setSeasonalOverride(SeasonalOverride.INHERIT);
            assertThat(service.isSeasonalActive(plant, user)).isFalse();
        }

        @Test
        @DisplayName("global=OFF, plant=ON → активна (forced)")
        void plantOnOverridesGlobalOff() {
            user.setSeasonalEnabled(false);
            plant.setSeasonalOverride(SeasonalOverride.ON);
            assertThat(service.isSeasonalActive(plant, user)).isTrue();
        }

        @Test
        @DisplayName("global=ON, plant=OFF → НЕ активна (forced)")
        void plantOffOverridesGlobalOn() {
            user.setSeasonalEnabled(true);
            plant.setSeasonalOverride(SeasonalOverride.OFF);
            assertThat(service.isSeasonalActive(plant, user)).isFalse();
        }

        @Test
        @DisplayName("plant.seasonalOverride == null → как INHERIT (paranoia)")
        void nullOverrideTreatedAsInherit() {
            plant.setSeasonalOverride(null);
            user.setSeasonalEnabled(true);
            assertThat(service.isSeasonalActive(plant, user)).isTrue();
        }
    }

    @Nested
    @DisplayName("effectiveIntervalDays — MULTIPLIER")
    class Multiplier {

        @BeforeEach
        void enableSummer() {
            when(seasonResolver.seasonAt(any(), any())).thenReturn(Season.SUMMER);
        }

        @ParameterizedTest(name = "base={0}, summer × 0.80 → {1}")
        @CsvSource({
                "10, 8",   // round(10 * 0.8) = 8
                "5,  4",   // round(5 * 0.8) = 4
                "7,  6",   // round(7 * 0.8) = 5.6 → 6 (HALF_UP)
                "1,  1",   // clamp lower bound
        })
        @DisplayName("Лето: round(base * 0.8) с clamp ≥ 1")
        void summerMultiplier(int base, int expected) {
            assertThat(service.effectiveIntervalDaysAt(plant, user, base, now()))
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("Лето: base=1, multiplier=0.5 → 1 (clamp)")
        void clampToMinDays() {
            user.setSummerMultiplier(new BigDecimal("0.50"));
            assertThat(service.effectiveIntervalDaysAt(plant, user, 1, now()))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("Зима: base=10 × 1.2 = 12")
        void winterMultiplier() {
            when(seasonResolver.seasonAt(any(), any())).thenReturn(Season.WINTER);
            assertThat(service.effectiveIntervalDaysAt(plant, user, 10, now()))
                    .isEqualTo(12);
        }

        @Test
        @DisplayName("Clamp верхней границы: base=100, × 1.2 → 60")
        void clampToMaxDays() {
            when(seasonResolver.seasonAt(any(), any())).thenReturn(Season.WINTER);
            assertThat(service.effectiveIntervalDaysAt(plant, user, 100, now()))
                    .isEqualTo(60);
        }
    }

    @Nested
    @DisplayName("effectiveIntervalDays — FIXED")
    class Fixed {

        @BeforeEach
        void setupFixed() {
            user.setSeasonalMode(SeasonalMode.FIXED);
        }

        @Test
        @DisplayName("Лето: override задан → используется override")
        void summerWithOverride() {
            user.setSummerIntervalOverrideDays(5);
            when(seasonResolver.seasonAt(any(), any())).thenReturn(Season.SUMMER);

            assertThat(service.effectiveIntervalDaysAt(plant, user, 10, now()))
                    .isEqualTo(5);
        }

        @Test
        @DisplayName("Зима: override не задан → fallback на base")
        void winterFallbackToBase() {
            user.setWinterIntervalOverrideDays(null);
            when(seasonResolver.seasonAt(any(), any())).thenReturn(Season.WINTER);

            assertThat(service.effectiveIntervalDaysAt(plant, user, 14, now()))
                    .isEqualTo(14);
        }

        @Test
        @DisplayName("Override > 60 → clamp до 60")
        void overrideClampedToMax() {
            user.setSummerIntervalOverrideDays(120);
            when(seasonResolver.seasonAt(any(), any())).thenReturn(Season.SUMMER);

            assertThat(service.effectiveIntervalDaysAt(plant, user, 10, now()))
                    .isEqualTo(60);
        }
    }

    @Nested
    @DisplayName("Сезонность выключена → возвращает base (clamped)")
    class Disabled {

        @Test
        @DisplayName("global=OFF → base без изменений")
        void disabledReturnsBase() {
            user.setSeasonalEnabled(false);

            assertThat(service.effectiveIntervalDaysAt(plant, user, 10, now()))
                    .isEqualTo(10);
        }

        @Test
        @DisplayName("Даже когда сезонность выключена — clamp применяется")
        void clampAppliesEvenWhenDisabled() {
            user.setSeasonalEnabled(false);

            assertThat(service.effectiveIntervalDaysAt(plant, user, 0, now()))
                    .isEqualTo(1);  // clamp ≥ 1
            assertThat(service.effectiveIntervalDaysAt(plant, user, 999, now()))
                    .isEqualTo(60);  // clamp ≤ 60
        }
    }

    // =====================================================================
    // effectiveIntervalForSeason — новый метод (issue #188)
    // =====================================================================

    @Nested
    @DisplayName("effectiveIntervalForSeason — preview без текущего времени")
    class ForSeason {

        @Test
        @DisplayName("should_return_base_interval_when_seasonal_disabled")
        void should_return_base_interval_when_seasonal_disabled() {
            // arrange
            user.setSeasonalEnabled(false);
            plant.setSeasonalOverride(SeasonalOverride.INHERIT);

            // act + assert
            assertThat(service.effectiveIntervalForSeason(plant, user, 10, Season.SUMMER))
                    .isEqualTo(10);
            assertThat(service.effectiveIntervalForSeason(plant, user, 10, Season.WINTER))
                    .isEqualTo(10);
        }

        @Test
        @DisplayName("should_apply_summer_multiplier")
        void should_apply_summer_multiplier() {
            // arrange
            user.setSeasonalEnabled(true);
            user.setSeasonalMode(SeasonalMode.MULTIPLIER);
            user.setSummerMultiplier(new BigDecimal("0.5"));

            // act
            int result = service.effectiveIntervalForSeason(plant, user, 10, Season.SUMMER);

            // assert
            assertThat(result).isEqualTo(5);
        }

        @Test
        @DisplayName("should_apply_winter_multiplier")
        void should_apply_winter_multiplier() {
            // arrange
            user.setSeasonalEnabled(true);
            user.setSeasonalMode(SeasonalMode.MULTIPLIER);
            user.setWinterMultiplier(new BigDecimal("2.0"));

            // act
            int result = service.effectiveIntervalForSeason(plant, user, 10, Season.WINTER);

            // assert
            assertThat(result).isEqualTo(20);
        }

        @Test
        @DisplayName("should_apply_fixed_summer_override")
        void should_apply_fixed_summer_override() {
            // arrange
            user.setSeasonalEnabled(true);
            user.setSeasonalMode(SeasonalMode.FIXED);
            user.setSummerIntervalOverrideDays(5);

            // act
            int result = service.effectiveIntervalForSeason(plant, user, 14, Season.SUMMER);

            // assert
            assertThat(result).isEqualTo(5);
        }

        @Test
        @DisplayName("should_fallback_to_base_when_no_fixed_override")
        void should_fallback_to_base_when_no_fixed_override() {
            // arrange
            user.setSeasonalEnabled(true);
            user.setSeasonalMode(SeasonalMode.FIXED);
            user.setSummerIntervalOverrideDays(null);

            // act
            int result = service.effectiveIntervalForSeason(plant, user, 14, Season.SUMMER);

            // assert
            assertThat(result).isEqualTo(14);
        }

        @Test
        @DisplayName("should_clamp_result_to_max")
        void should_clamp_result_to_max() {
            // arrange
            user.setSeasonalEnabled(true);
            user.setSeasonalMode(SeasonalMode.MULTIPLIER);
            user.setWinterMultiplier(new BigDecimal("3.0"));

            // act
            int result = service.effectiveIntervalForSeason(plant, user, 30, Season.WINTER);

            // assert — 30 * 3.0 = 90, clamped to MAX_INTERVAL_DAYS = 60
            assertThat(result).isEqualTo(SeasonalIntervalService.MAX_INTERVAL_DAYS);
        }

        @Test
        @DisplayName("should_clamp_result_to_min")
        void should_clamp_result_to_min() {
            // arrange
            user.setSeasonalEnabled(true);
            user.setSeasonalMode(SeasonalMode.MULTIPLIER);
            user.setSummerMultiplier(new BigDecimal("0.01"));

            // act
            int result = service.effectiveIntervalForSeason(plant, user, 5, Season.SUMMER);

            // assert — 5 * 0.01 = 0.05 → rounds to 0, clamped to MIN_INTERVAL_DAYS = 1
            assertThat(result).isEqualTo(SeasonalIntervalService.MIN_INTERVAL_DAYS);
        }

        @Test
        @DisplayName("should_respect_plant_seasonal_override_on")
        void should_respect_plant_seasonal_override_on() {
            // arrange — глобально выключена, но у растения принудительно ON
            user.setSeasonalEnabled(false);
            user.setSeasonalMode(SeasonalMode.MULTIPLIER);
            user.setSummerMultiplier(new BigDecimal("0.5"));
            plant.setSeasonalOverride(SeasonalOverride.ON);

            // act — сезонность включена через per-plant override, умножитель применяется
            int result = service.effectiveIntervalForSeason(plant, user, 10, Season.SUMMER);

            // assert
            assertThat(result).isEqualTo(5);
        }
    }

    private static ZonedDateTime now() {
        return ZonedDateTime.of(2026, 7, 15, 12, 0, 0, 0, ZoneId.of("Europe/Minsk"));
    }
}
