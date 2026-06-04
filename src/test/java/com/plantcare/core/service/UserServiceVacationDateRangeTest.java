package com.plantcare.core.service;

import com.plantcare.core.domain.User;
import com.plantcare.core.repository.UserRepository;
import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Интеграционные тесты {@link UserService#startVacationByDateRange} (issue #189).
 *
 * <p>Проверяем что:
 * <ul>
 *   <li>paused_until = конец дня 'to' в TZ юзера, переведённый в UTC;</li>
 *   <li>на не-UTC TZ результат правильный (Asia/Almaty, Europe/Moscow);</li>
 *   <li>to = from — граничное значение, 1 день, допустим;</li>
 *   <li>to &lt; from или длительность > 60 дней — IllegalArgumentException.</li>
 * </ul>
 */
@Import(UserServiceVacationDateRangeTest.FixedClockConfig.class)
@DisplayName("UserService.startVacationByDateRange (issue #189)")
class UserServiceVacationDateRangeTest extends IntegrationTestBase {

    /** Фиксированный момент: 2026-06-04T10:00:00Z. */
    static final Instant FIXED_NOW = Instant.parse("2026-06-04T10:00:00Z");

    @Autowired
    private UserService userService;
    @Autowired
    private UserRepository userRepository;

    @AfterEach
    void cleanup() {
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("paused_until = конец дня 'to' в UTC (UTC юзер)")
    void should_set_paused_until_to_end_of_to_day_in_utc_when_user_is_utc() {
        // arrange
        User user = newUser(800L, "UTC");
        LocalDate from = LocalDate.of(2026, 6, 10);
        LocalDate to = LocalDate.of(2026, 6, 14);

        // act
        User updated = userService.startVacationByDateRange(user, from, to);

        // assert — конец дня 14 июня UTC = 2026-06-14T23:59:59Z
        LocalDateTime expected = LocalDateTime.of(2026, 6, 14, 23, 59, 59);
        assertThat(updated.getPausedUntil()).isEqualTo(expected);

        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getPausedUntil()).isEqualTo(expected);
    }

    @Test
    @DisplayName("paused_until учитывает смещение Asia/Almaty (UTC+5)")
    void should_offset_paused_until_correctly_when_user_is_asia_almaty() {
        // arrange — UTC+5
        User user = newUser(801L, "Asia/Almaty");
        LocalDate from = LocalDate.of(2026, 6, 10);
        LocalDate to = LocalDate.of(2026, 6, 14);

        // act
        User updated = userService.startVacationByDateRange(user, from, to);

        // assert — конец дня 14 июня в Asia/Almaty = 2026-06-14T23:59:59+05:00
        //           → UTC = 2026-06-14T18:59:59Z
        LocalDateTime expected = ZonedDateTime.of(to, LocalTime.of(23, 59, 59), ZoneId.of("Asia/Almaty"))
                .withZoneSameInstant(ZoneOffset.UTC)
                .toLocalDateTime();

        assertThat(updated.getPausedUntil()).isEqualTo(expected);
    }

    @Test
    @DisplayName("paused_until учитывает смещение Europe/Moscow (UTC+3)")
    void should_offset_paused_until_correctly_when_user_is_europe_moscow() {
        // arrange — UTC+3
        User user = newUser(802L, "Europe/Moscow");
        LocalDate from = LocalDate.of(2026, 6, 1);
        LocalDate to = LocalDate.of(2026, 6, 14);

        // act
        User updated = userService.startVacationByDateRange(user, from, to);

        // assert
        LocalDateTime expected = ZonedDateTime.of(to, LocalTime.of(23, 59, 59), ZoneId.of("Europe/Moscow"))
                .withZoneSameInstant(ZoneOffset.UTC)
                .toLocalDateTime();

        assertThat(updated.getPausedUntil()).isEqualTo(expected);
    }

    @Test
    @DisplayName("Граница: from == to допустимо (1 день отпуска)")
    void should_accept_single_day_vacation_when_from_equals_to() {
        // arrange
        User user = newUser(803L, "UTC");
        LocalDate date = LocalDate.of(2026, 6, 10);

        // act
        User updated = userService.startVacationByDateRange(user, date, date);

        // assert — paused_until = конец этого же дня
        assertThat(updated.getPausedUntil()).isEqualTo(LocalDateTime.of(2026, 6, 10, 23, 59, 59));
    }

    @Test
    @DisplayName("Граница: ровно 60 дней допустимо")
    void should_accept_exactly_60_day_vacation() {
        // arrange
        User user = newUser(804L, "UTC");
        LocalDate from = LocalDate.of(2026, 6, 1);
        LocalDate to = from.plusDays(59); // 60 дней включительно: from + 59 дней (оба включительно)

        // act — не должно бросить
        User updated = userService.startVacationByDateRange(user, from, to);

        assertThat(updated.getPausedUntil()).isNotNull();
    }

    @Test
    @DisplayName("to < from → IllegalArgumentException")
    void should_throw_when_to_is_before_from() {
        User user = newUser(805L, "UTC");

        assertThatThrownBy(() -> userService.startVacationByDateRange(
                user,
                LocalDate.of(2026, 6, 14),
                LocalDate.of(2026, 6, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'to' date must not be before 'from' date");
    }

    @Test
    @DisplayName("Длительность > 60 дней → IllegalArgumentException")
    void should_throw_when_duration_exceeds_60_days() {
        User user = newUser(806L, "UTC");

        assertThatThrownBy(() -> userService.startVacationByDateRange(
                user,
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 9, 1))) // 92 дня
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("60");
    }

    @Test
    @DisplayName("Повторный POST перезаписывает paused_until (идемпотентность)")
    void should_overwrite_paused_until_on_repeated_call() {
        // arrange — первый вызов
        User user = newUser(807L, "UTC");
        userService.startVacationByDateRange(user, LocalDate.of(2026, 6, 10), LocalDate.of(2026, 6, 14));

        // act — второй вызов с другим диапазоном
        User updated = userService.startVacationByDateRange(user, LocalDate.of(2026, 6, 10), LocalDate.of(2026, 6, 20));

        // assert — paused_until = конец 20 июня
        assertThat(updated.getPausedUntil()).isEqualTo(LocalDateTime.of(2026, 6, 20, 23, 59, 59));
    }

    private User newUser(Long chatId, String timezone) {
        User u = User.builder()
                .telegramChatId(chatId)
                .timezone(timezone)
                .blocked(false)
                .build();
        return userRepository.save(u);
    }

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        public Clock fixedClock() {
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        }
    }
}
