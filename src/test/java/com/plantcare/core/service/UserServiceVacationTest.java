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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Тесты на vacation-методы UserService (issue #53).
 *
 * <p>Фиксируем Clock на 2026-05-23T10:00:00Z (UTC), чтобы расчёты
 * «paused_until = сейчас-в-TZ + N дней → UTC» были детерминированы.
 */
@Import(UserServiceVacationTest.FixedClockConfig.class)
@DisplayName("UserService vacation flow (issue #53)")
class UserServiceVacationTest extends IntegrationTestBase {

    /** Фиксированный момент: 2026-05-23 10:00 UTC. */
    static final Instant FIXED_NOW = Instant.parse("2026-05-23T10:00:00Z");

    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;

    @AfterEach
    void cleanup() {
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("startVacation на 3 дня для Asia/Almaty (UTC+5) сохраняет paused_until в UTC")
    void should_set_paused_until_in_user_tz_when_starting_vacation_for_3_days() {
        User user = newUser(900L, "Asia/Almaty");

        User updated = userService.startVacation(user, 3);

        // Ожидание: сейчас-в-Almaty + 3 дня, переведённое обратно в UTC.
        LocalDateTime expected = ZonedDateTime.ofInstant(FIXED_NOW, ZoneId.of("Asia/Almaty"))
                .plusDays(3)
                .withZoneSameInstant(ZoneOffset.UTC)
                .toLocalDateTime();

        assertThat(updated.getPausedUntil())
                .isNotNull()
                .isEqualTo(expected);

        // Перепроверяем, что значение реально лежит в БД, а не только в кеше Hibernate.
        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getPausedUntil())
                .isEqualToIgnoringNanos(expected);
    }

    @Test
    @DisplayName("startVacation на 14 дней для Europe/Moscow (UTC+3) сохраняет paused_until в UTC")
    void should_set_paused_until_in_user_tz_when_starting_vacation_for_14_days() {
        User user = newUser(901L, "Europe/Moscow");

        User updated = userService.startVacation(user, 14);

        LocalDateTime expected = ZonedDateTime.ofInstant(FIXED_NOW, ZoneId.of("Europe/Moscow"))
                .plusDays(14)
                .withZoneSameInstant(ZoneOffset.UTC)
                .toLocalDateTime();

        assertThat(updated.getPausedUntil())
                .isNotNull()
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("startVacation бросает IllegalArgumentException, если дней < 1")
    void should_throw_when_days_below_1() {
        User user = newUser(902L, "UTC");

        assertThatThrownBy(() -> userService.startVacation(user, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1")
                .hasMessageContaining("60");
    }

    @Test
    @DisplayName("startVacation бросает IllegalArgumentException, если дней > 60")
    void should_throw_when_days_above_60() {
        User user = newUser(903L, "UTC");

        assertThatThrownBy(() -> userService.startVacation(user, 61))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1")
                .hasMessageContaining("60");
    }

    @Test
    @DisplayName("startVacation принимает граничные значения 1 и 60")
    void should_accept_boundary_values_1_and_60() {
        User user1 = newUser(904L, "UTC");
        User user60 = newUser(905L, "UTC");

        User result1 = userService.startVacation(user1, 1);
        User result60 = userService.startVacation(user60, 60);

        LocalDateTime expected1 = ZonedDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC)
                .plusDays(1).toLocalDateTime();
        LocalDateTime expected60 = ZonedDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC)
                .plusDays(60).toLocalDateTime();

        assertThat(result1.getPausedUntil()).isEqualTo(expected1);
        assertThat(result60.getPausedUntil()).isEqualTo(expected60);
    }

    @Test
    @DisplayName("endVacation сбрасывает paused_until в null")
    void should_clear_paused_until_when_ending_vacation() {
        User user = newUser(906L, "UTC");
        user.setPausedUntil(LocalDateTime.now(Clock.fixed(FIXED_NOW, ZoneOffset.UTC)).plusDays(5));
        userRepository.save(user);

        User updated = userService.endVacation(user);

        assertThat(updated.getPausedUntil()).isNull();
        assertThat(userRepository.findById(user.getId()).orElseThrow().getPausedUntil()).isNull();
    }

    @Test
    @DisplayName("extendVacation добавляет дни к существующему paused_until")
    void should_add_days_when_extending_vacation() {
        User user = newUser(907L, "UTC");
        LocalDateTime initial = LocalDateTime.of(2026, 5, 30, 10, 0)
                .truncatedTo(ChronoUnit.MICROS);
        user.setPausedUntil(initial);
        userRepository.save(user);

        User updated = userService.extendVacation(user, 3);

        assertThat(updated.getPausedUntil())
                .isEqualTo(initial.plusDays(3));
    }

    @Test
    @DisplayName("extendVacation бросает IllegalStateException, если юзер не в отпуске")
    void should_throw_when_extending_user_not_in_vacation() {
        User user = newUser(908L, "UTC");
        // paused_until не выставляли — отпуск не активен.

        assertThatThrownBy(() -> userService.extendVacation(user, 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("не в отпуске");
    }

    @Test
    @DisplayName("extendVacation бросает IllegalArgumentException на нулевые / отрицательные / слишком большие дни")
    void should_throw_when_extend_days_out_of_range() {
        User user = newUser(909L, "UTC");
        user.setPausedUntil(LocalDateTime.of(2026, 6, 1, 10, 0));
        userRepository.save(user);

        assertThatThrownBy(() -> userService.extendVacation(user, 0))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> userService.extendVacation(user, 61))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private User newUser(Long chatId, String timezone) {
        User u = User.builder()
                .telegramChatId(chatId)
                .timezone(timezone)
                .blocked(false)
                .build();
        return userRepository.save(u);
    }

    /**
     * Подменяет дефолтный Clock на фиксированный — чтобы startVacation давал
     * детерминированный результат и расчёты «сейчас + N дней» можно было
     * проверить точным равенством.
     */
    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        public Clock fixedClock() {
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        }
    }
}
