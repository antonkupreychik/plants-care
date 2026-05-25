package com.plantcare.core.service;

import com.plantcare.core.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit-тесты для {@link QuietHoursPolicy} (issue #118).
 *
 * <p>Никакого Spring-контекста: класс stateless, всё, что нужно — это юзер
 * с настройками quiet-hours и проверяемый {@link Instant}.
 */
@DisplayName("QuietHoursPolicy: расчёт сдвига и проверка попадания в тихие часы (#118)")
class QuietHoursPolicyTest {

    private QuietHoursPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new QuietHoursPolicy();
    }

    // ====================================================================
    // shiftOutOfQuietHours — основной метод, используемый snooze-flow
    // ====================================================================

    @Nested
    @DisplayName("shiftOutOfQuietHours")
    class Shift {

        @Test
        @DisplayName("не в quiet → возвращает входной Instant без изменений")
        void should_return_input_when_not_in_quiet_hours() {
            User user = userWithQuiet(LocalTime.of(22, 0), LocalTime.of(9, 0), "UTC");
            // 14:00 UTC — далеко за пределами окна 22:00–09:00
            Instant candidate = ZonedDateTime.of(2026, 5, 24, 14, 0, 0, 0, ZoneOffset.UTC).toInstant();

            Instant result = policy.shiftOutOfQuietHours(user, candidate);

            assertThat(result).isEqualTo(candidate);
        }

        @Test
        @DisplayName("overnight quiet 22-09, момент 02:00 → конец quiet 09:00 того же дня")
        void should_shift_to_end_of_same_day_when_overnight_and_after_midnight() {
            User user = userWithQuiet(LocalTime.of(22, 0), LocalTime.of(9, 0), "UTC");
            Instant candidate = ZonedDateTime.of(2026, 5, 24, 2, 0, 0, 0, ZoneOffset.UTC).toInstant();

            Instant result = policy.shiftOutOfQuietHours(user, candidate);

            Instant expected = ZonedDateTime.of(2026, 5, 24, 9, 0, 0, 0, ZoneOffset.UTC).toInstant();
            assertThat(result).isEqualTo(expected);
        }

        @Test
        @DisplayName("overnight quiet 22-09, момент 23:00 → конец quiet 09:00 СЛЕДУЮЩЕГО дня")
        void should_shift_to_next_day_end_when_overnight_and_before_midnight() {
            User user = userWithQuiet(LocalTime.of(22, 0), LocalTime.of(9, 0), "UTC");
            Instant candidate = ZonedDateTime.of(2026, 5, 24, 23, 0, 0, 0, ZoneOffset.UTC).toInstant();

            Instant result = policy.shiftOutOfQuietHours(user, candidate);

            Instant expected = ZonedDateTime.of(2026, 5, 25, 9, 0, 0, 0, ZoneOffset.UTC).toInstant();
            assertThat(result).isEqualTo(expected);
        }

        @Test
        @DisplayName("daytime quiet 12-14, момент 13:00 → 14:00 того же дня")
        void should_shift_to_end_when_daytime_quiet() {
            User user = userWithQuiet(LocalTime.of(12, 0), LocalTime.of(14, 0), "UTC");
            Instant candidate = ZonedDateTime.of(2026, 5, 24, 13, 0, 0, 0, ZoneOffset.UTC).toInstant();

            Instant result = policy.shiftOutOfQuietHours(user, candidate);

            Instant expected = ZonedDateTime.of(2026, 5, 24, 14, 0, 0, 0, ZoneOffset.UTC).toInstant();
            assertThat(result).isEqualTo(expected);
        }

        @Test
        @DisplayName("момент ровно в start → попадает в quiet (нижняя граница включительно)")
        void should_treat_start_boundary_as_inside_quiet() {
            User user = userWithQuiet(LocalTime.of(22, 0), LocalTime.of(9, 0), "UTC");
            Instant candidate = ZonedDateTime.of(2026, 5, 24, 22, 0, 0, 0, ZoneOffset.UTC).toInstant();

            Instant result = policy.shiftOutOfQuietHours(user, candidate);

            // start == 22:00, попали в quiet → должны быть сдвинуты на 09:00 следующего дня
            Instant expected = ZonedDateTime.of(2026, 5, 25, 9, 0, 0, 0, ZoneOffset.UTC).toInstant();
            assertThat(result).isEqualTo(expected);
        }

        @Test
        @DisplayName("момент ровно в end → НЕ в quiet (верхняя граница исключительная)")
        void should_treat_end_boundary_as_outside_quiet() {
            User user = userWithQuiet(LocalTime.of(22, 0), LocalTime.of(9, 0), "UTC");
            Instant candidate = ZonedDateTime.of(2026, 5, 24, 9, 0, 0, 0, ZoneOffset.UTC).toInstant();

            Instant result = policy.shiftOutOfQuietHours(user, candidate);

            assertThat(result).isEqualTo(candidate);
        }

        @Test
        @DisplayName("start == end → quiet-hours отключены, candidate без изменений")
        void should_return_input_when_quiet_hours_disabled_by_equal_bounds() {
            User user = userWithQuiet(LocalTime.of(9, 0), LocalTime.of(9, 0), "UTC");
            Instant candidate = ZonedDateTime.of(2026, 5, 24, 9, 0, 0, 0, ZoneOffset.UTC).toInstant();

            Instant result = policy.shiftOutOfQuietHours(user, candidate);

            assertThat(result).isEqualTo(candidate);
        }

        @Test
        @DisplayName("не-UTC TZ (Asia/Almaty, UTC+5): 02:00 локально → 09:00 локально того же дня")
        void should_use_user_timezone_when_computing_shift() {
            User user = userWithQuiet(LocalTime.of(22, 0), LocalTime.of(9, 0), "Asia/Almaty");
            // 02:00 в Almaty = 21:00 UTC предыдущего дня
            Instant candidate = ZonedDateTime.of(2026, 5, 24, 2, 0, 0, 0, ZoneId.of("Asia/Almaty"))
                    .toInstant();

            Instant result = policy.shiftOutOfQuietHours(user, candidate);

            // ожидаем 09:00 в Almaty того же дня (24.05)
            Instant expected = ZonedDateTime.of(2026, 5, 24, 9, 0, 0, 0, ZoneId.of("Asia/Almaty"))
                    .toInstant();
            assertThat(result).isEqualTo(expected);
        }

        @Test
        @DisplayName("null candidate → null (graceful)")
        void should_return_null_when_candidate_is_null() {
            User user = userWithQuiet(LocalTime.of(22, 0), LocalTime.of(9, 0), "UTC");

            Instant result = policy.shiftOutOfQuietHours(user, null);

            assertThat(result).isNull();
        }
    }

    // ====================================================================
    // isQuiet — простая проверка попадания
    // ====================================================================

    @Nested
    @DisplayName("isQuiet")
    class IsQuiet {

        @Test
        @DisplayName("overnight quiet 22-09, момент 02:00 локально → true")
        void should_return_true_when_after_midnight_in_overnight_window() {
            User user = userWithQuiet(LocalTime.of(22, 0), LocalTime.of(9, 0), "UTC");
            Instant t = ZonedDateTime.of(2026, 5, 24, 2, 0, 0, 0, ZoneOffset.UTC).toInstant();

            assertThat(policy.isQuiet(user, t)).isTrue();
        }

        @Test
        @DisplayName("overnight quiet 22-09, момент 14:00 → false")
        void should_return_false_when_outside_overnight_window() {
            User user = userWithQuiet(LocalTime.of(22, 0), LocalTime.of(9, 0), "UTC");
            Instant t = ZonedDateTime.of(2026, 5, 24, 14, 0, 0, 0, ZoneOffset.UTC).toInstant();

            assertThat(policy.isQuiet(user, t)).isFalse();
        }

        @Test
        @DisplayName("не-UTC юзер: 23:00 в Almaty (= 18:00 UTC) → true, потому что quiet считается в TZ юзера")
        void should_use_user_timezone_for_is_quiet() {
            User user = userWithQuiet(LocalTime.of(22, 0), LocalTime.of(9, 0), "Asia/Almaty");
            // 23:00 в Almaty
            Instant t = ZonedDateTime.of(2026, 5, 24, 23, 0, 0, 0, ZoneId.of("Asia/Almaty"))
                    .toInstant();

            assertThat(policy.isQuiet(user, t)).isTrue();
        }

        @Test
        @DisplayName("невалидная TZ → fallback на UTC, не кидаем исключение")
        void should_fallback_to_utc_when_timezone_invalid() {
            User user = User.builder()
                    .telegramChatId(1L)
                    .timezone("Not/A/Real/Zone")
                    .quietHoursStart(LocalTime.of(22, 0))
                    .quietHoursEnd(LocalTime.of(9, 0))
                    .build();
            // 02:00 UTC — в quiet, если посчитать по UTC
            Instant t = ZonedDateTime.of(2026, 5, 24, 2, 0, 0, 0, ZoneOffset.UTC).toInstant();

            assertThat(policy.isQuiet(user, t)).isTrue();
        }
    }

    // ====================================================================
    // helpers
    // ====================================================================

    private static User userWithQuiet(LocalTime start, LocalTime end, String tz) {
        return User.builder()
                .telegramChatId(42L)
                .timezone(tz)
                .quietHoursStart(start)
                .quietHoursEnd(end)
                .build();
    }
}
