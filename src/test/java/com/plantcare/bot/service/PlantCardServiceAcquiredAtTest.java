package com.plantcare.bot.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-тесты вычисления возраста растения и подстановки строки «С тобой с …»
 * в карточке (issue #117, Part A).
 *
 * <p>Проверяем приватную математику возраста через рефлексию — без поднятия
 * Spring и Telegram-моков, потому что метод {@code formatPlantAge}
 * package-private static и зависит только от двух {@link LocalDate}.
 * Это ловит баги в склонении и в крайних случаях («ровно месяц», «1 год»),
 * которых на тестах через рендер карточки не словить.
 */
@DisplayName("PlantCardService — возраст растения и блок «С тобой с …» (issue #117)")
class PlantCardServiceAcquiredAtTest {

    private static String formatAge(LocalDate acquired, LocalDate reference) {
        try {
            Method m = PlantCardService.class
                    .getDeclaredMethod("formatPlantAge", LocalDate.class, LocalDate.class);
            m.setAccessible(true);
            return (String) m.invoke(null, acquired, reference);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    // --- happy / типичные случаи ---

    @Test
    @DisplayName("ровно 1 год — «1 год» (склонение в единственном числе)")
    void should_format_one_year_in_singular() {
        var acquired = LocalDate.of(2025, 5, 1);
        var ref = LocalDate.of(2026, 5, 1);

        assertThat(formatAge(acquired, ref)).isEqualTo("1 год");
    }

    @Test
    @DisplayName("2 года 3 месяца — «2 года 3 месяца»")
    void should_format_two_years_three_months() {
        var acquired = LocalDate.of(2024, 2, 15);
        var ref = LocalDate.of(2026, 5, 15);

        assertThat(formatAge(acquired, ref)).isEqualTo("2 года 3 месяца");
    }

    @Test
    @DisplayName("5 лет — «5 лет» (множественное)")
    void should_format_five_years() {
        var acquired = LocalDate.of(2020, 1, 10);
        var ref = LocalDate.of(2025, 1, 10);

        assertThat(formatAge(acquired, ref)).isEqualTo("5 лет");
    }

    @Test
    @DisplayName("11 лет — «11 лет» (исключение: 11..14 всегда «лет»)")
    void should_format_eleven_years() {
        var acquired = LocalDate.of(2014, 6, 1);
        var ref = LocalDate.of(2025, 6, 1);

        assertThat(formatAge(acquired, ref)).isEqualTo("11 лет");
    }

    @Test
    @DisplayName("21 год — «21 год» (склонение по последней цифре, не 11)")
    void should_format_twenty_one_year() {
        var acquired = LocalDate.of(2000, 1, 1);
        var ref = LocalDate.of(2021, 1, 1);

        assertThat(formatAge(acquired, ref)).isEqualTo("21 год");
    }

    @Test
    @DisplayName("только месяцы — годы скрыты")
    void should_skip_years_when_zero() {
        var acquired = LocalDate.of(2026, 2, 10);
        var ref = LocalDate.of(2026, 5, 10);

        assertThat(formatAge(acquired, ref)).isEqualTo("3 месяца");
    }

    @Test
    @DisplayName("1 месяц — «1 месяц» (единственное число)")
    void should_format_one_month_singular() {
        var acquired = LocalDate.of(2026, 4, 1);
        var ref = LocalDate.of(2026, 5, 1);

        assertThat(formatAge(acquired, ref)).isEqualTo("1 месяц");
    }

    // --- edge ---

    @Test
    @DisplayName("ровно reference == acquired — «меньше месяца»")
    void should_return_less_than_month_when_same_day() {
        var d = LocalDate.of(2026, 5, 22);

        assertThat(formatAge(d, d)).isEqualTo("меньше месяца");
    }

    @Test
    @DisplayName("разница меньше месяца — «меньше месяца»")
    void should_return_less_than_month_when_within_month() {
        var acquired = LocalDate.of(2026, 5, 1);
        var ref = LocalDate.of(2026, 5, 20);

        assertThat(formatAge(acquired, ref)).isEqualTo("меньше месяца");
    }

    @Test
    @DisplayName("reference раньше acquired — «меньше месяца» (защита от будущего)")
    void should_return_less_than_month_when_reference_before_acquired() {
        var acquired = LocalDate.of(2026, 5, 22);
        var ref = LocalDate.of(2026, 5, 21);

        assertThat(formatAge(acquired, ref)).isEqualTo("меньше месяца");
    }

    @Test
    @DisplayName("acquired == null — «меньше месяца» (защита, не NPE)")
    void should_not_throw_when_acquired_null() {
        var ref = LocalDate.of(2026, 5, 22);

        assertThat(formatAge(null, ref)).isEqualTo("меньше месяца");
    }
}
