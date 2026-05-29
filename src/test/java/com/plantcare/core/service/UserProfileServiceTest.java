package com.plantcare.core.service;

import com.plantcare.bot.support.IntegrationTestBase;
import com.plantcare.core.domain.CareHistory;
import com.plantcare.core.domain.CareSchedule;
import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.repository.CareHistoryRepository;
import com.plantcare.core.repository.CareScheduleRepository;
import com.plantcare.core.repository.LocationRepository;
import com.plantcare.core.repository.PlantRepository;
import com.plantcare.core.repository.UserRepository;
import com.plantcare.core.service.UserProfileService.Profile;
import com.plantcare.core.service.UserProfileService.ProfileUpdate;
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
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * Интеграционные тесты {@link UserProfileService} (issue #182) на реальном Postgres
 * через Testcontainers — счётчики профиля и частичный апдейт настроек проверяются на
 * настоящих записях (растения, расписания, users), а не на моках репозиториев.
 *
 * <p>{@link Clock}-бин подменён фиксированным {@link #FIXED_NOW}, чтобы граница окна
 * «сегодня» (для {@code tasksToday}, считающегося в TZ юзера через
 * {@link TodayApiService}) была детерминированной.
 *
 * <p>Покрывает: happy GET (счётчики + настройки); частичный апдейт (locale-only,
 * quietHours-only, tz-only — omitted-поля не зануляются); смена IANA-tz отражается
 * в профиле; TZ-кейс на границе суток (tasksToday в зоне юзера, не UTC); невалидный
 * IANA-tz → IllegalArgumentException.
 */
@DisplayName("UserProfileService — профиль и настройки /me (issue #182)")
@Import(UserProfileServiceTest.FixedClockConfig.class)
class UserProfileServiceTest extends IntegrationTestBase {

    /**
     * Фиксированный «сейчас» 00:30 UTC. Для восточных TZ (Almaty UTC+5) локальная дата
     * уже «сегодня», а UTC-полночь и локальная-полночь расходятся на день — это и
     * проверяет граничный TZ-кейс счётчика tasksToday.
     */
    static final Instant FIXED_NOW = Instant.parse("2026-05-25T00:30:00Z");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        }
    }

    @Autowired private UserProfileService service;

    @Autowired private UserRepository userRepository;
    @Autowired private LocationRepository locationRepository;
    @Autowired private PlantRepository plantRepository;
    @Autowired private CareScheduleRepository careScheduleRepository;
    @Autowired private CareHistoryRepository careHistoryRepository;

    @AfterEach
    void cleanup() {
        careHistoryRepository.deleteAll();
        careScheduleRepository.deleteAll();
        plantRepository.deleteAll();
        locationRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ------------------------------------------------------------------ GET happy

    @Test
    @DisplayName("should_return_counts_and_settings_when_building_profile")
    void should_return_counts_and_settings_when_building_profile() {
        // arrange — AC #1: 2 неархивированных растения, одно расписание с дедлайном сегодня
        User user = savedUser(8001L, "UTC", LocalTime.of(22, 0), LocalTime.of(8, 0), "ru");
        Plant monstera = savedPlant(user, "Монстера");
        savedPlant(user, "Фикус");
        savedSchedule(monstera, TaskType.WATERING, utc("2026-05-25T10:00:00"));

        // act
        Profile profile = service.getProfile(user);

        // assert
        assertSoftly(softly -> {
            softly.assertThat(profile.name()).isEqualTo(user.getUsername());
            softly.assertThat(profile.avatar()).isNull();
            softly.assertThat(profile.plantsTotal()).isEqualTo(2);
            softly.assertThat(profile.tasksToday()).isEqualTo(1);
            softly.assertThat(profile.notificationsUnread()).isZero();
            softly.assertThat(profile.quietHoursStart()).isEqualTo(LocalTime.of(22, 0));
            softly.assertThat(profile.quietHoursEnd()).isEqualTo(LocalTime.of(8, 0));
            softly.assertThat(profile.timezone()).isEqualTo("UTC");
            softly.assertThat(profile.locale()).isEqualTo("ru");
        });
    }

    @Test
    @DisplayName("should_not_count_archived_plants_in_plantsTotal")
    void should_not_count_archived_plants_in_plantsTotal() {
        // arrange — edge: одно растение архивировано, считаем только активные
        User user = savedUser(8002L, "UTC", LocalTime.of(22, 0), LocalTime.of(9, 0), "ru");
        savedPlant(user, "Активная");
        Plant archived = savedPlant(user, "Архивная");
        archived.setArchivedAt(LocalDateTime.now());
        plantRepository.save(archived);

        // act
        Profile profile = service.getProfile(user);

        // assert
        assertThat(profile.plantsTotal()).isEqualTo(1);
    }

    @Test
    @DisplayName("should_return_zero_counts_when_user_has_no_plants_or_schedules")
    void should_return_zero_counts_when_user_has_no_plants_or_schedules() {
        // arrange — edge: пустой пользователь
        User user = savedUser(8003L, "UTC", LocalTime.of(22, 0), LocalTime.of(9, 0), "ru");

        // act
        Profile profile = service.getProfile(user);

        // assert
        assertSoftly(softly -> {
            softly.assertThat(profile.plantsTotal()).isZero();
            softly.assertThat(profile.tasksToday()).isZero();
            softly.assertThat(profile.notificationsUnread()).isZero();
        });
    }

    @Test
    @DisplayName("should_count_only_pending_tasks_today_excluding_done")
    void should_count_only_pending_tasks_today_excluding_done() {
        // arrange — два расписания с дедлайном сегодня; одно отмечаем выполненным сегодня.
        // getTodayTasks вернёт UNION pending+done = 2 задачи (одна с doneAt!=null), а
        // tasksToday фильтрует doneAt==null → бейдж не должен учитывать выполненную.
        User user = savedUser(8011L, "UTC", LocalTime.of(22, 0), LocalTime.of(8, 0), "ru");
        Plant monstera = savedPlant(user, "Монстера");
        Plant fikus = savedPlant(user, "Фикус");
        savedSchedule(monstera, TaskType.WATERING, utc("2026-05-25T10:00:00"));
        savedSchedule(fikus, TaskType.MISTING, utc("2026-05-25T11:00:00"));
        // отмечаем полив монстеры выполненным сегодня → выпадает из pending
        savedCare(monstera, TaskType.WATERING, utc("2026-05-25T09:00:00"));

        // act
        Profile profile = service.getProfile(user);

        // assert — только pending (опрыскивание фикуса), не union из двух
        assertThat(profile.tasksToday()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ non-UTC TZ (MANDATORY)

    @Test
    @DisplayName("should_count_tasksToday_in_user_timezone_not_utc_on_day_boundary")
    void should_count_tasksToday_in_user_timezone_not_utc_on_day_boundary() {
        // arrange — один и тот же дедлайн, два юзера в разных TZ. FIXED_NOW = 2026-05-25 00:30 UTC.
        // tasksToday = pending: nextDueAt <= конец сегодняшнего дня В TZ ЮЗЕРА.
        //   * UTC:         конец сегодня = 2026-05-25 23:59:59 UTC.
        //   * Asia/Almaty (UTC+5): сейчас 05:30 локально, конец сегодня 2026-05-25 23:59:59 Алматы
        //                  = 2026-05-25 18:59:59 UTC (раньше по UTC).
        // Дедлайн nextDueAt = 2026-05-25 21:00 UTC попадает В окно UTC-юзера, но ПОЗЖЕ конца
        // дня у Алматы → для Алматы это ещё не сегодня. Это и отличает TZ-расчёт от наивного UTC.
        LocalDateTime deadline = utc("2026-05-25T21:00:00");

        User utcUser = savedUser(8004L, "UTC", LocalTime.of(22, 0), LocalTime.of(9, 0), "ru");
        savedSchedule(savedPlant(utcUser, "UTC монстера"), TaskType.WATERING, deadline);

        User almatyUser = savedUser(8005L, "Asia/Almaty", LocalTime.of(22, 0), LocalTime.of(9, 0), "ru");
        savedSchedule(savedPlant(almatyUser, "Алматинская монстера"), TaskType.WATERING, deadline);

        // act
        Profile utcProfile = service.getProfile(utcUser);
        Profile almatyProfile = service.getProfile(almatyUser);

        // assert — один и тот же момент: «сегодня» для UTC, ещё не «сегодня» для Алматы.
        // Доказывает, что счётчик считается в TZ пользователя, а не в UTC.
        assertSoftly(softly -> {
            softly.assertThat(utcProfile.tasksToday())
                    .as("UTC: дедлайн 21:00 UTC внутри сегодняшнего дня")
                    .isEqualTo(1);
            softly.assertThat(almatyProfile.tasksToday())
                    .as("Almaty: 21:00 UTC = 02:00 завтра по Алматы → ещё не сегодня")
                    .isZero();
        });
    }

    // ------------------------------------------------------------------ PATCH partial

    @Test
    @DisplayName("should_change_only_locale_and_leave_tz_and_quiet_hours_untouched")
    void should_change_only_locale_and_leave_tz_and_quiet_hours_untouched() {
        // arrange — AC #2: меняем только locale
        User user = savedUser(8006L, "Europe/Moscow", LocalTime.of(22, 0), LocalTime.of(8, 0), "ru");

        // act
        Profile updated = service.updateProfile(user,
                new ProfileUpdate(null, null, null, "en"));

        // assert — locale изменился, остальное на месте
        assertSoftly(softly -> {
            softly.assertThat(updated.locale()).isEqualTo("en");
            softly.assertThat(updated.timezone()).isEqualTo("Europe/Moscow");
            softly.assertThat(updated.quietHoursStart()).isEqualTo(LocalTime.of(22, 0));
            softly.assertThat(updated.quietHoursEnd()).isEqualTo(LocalTime.of(8, 0));
        });

        // assert — изменение реально записано в БД
        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getLocale()).isEqualTo("en");
        assertThat(reloaded.getTimezone()).isEqualTo("Europe/Moscow");
    }

    @Test
    @DisplayName("should_change_only_quiet_hours_and_leave_tz_and_locale_untouched")
    void should_change_only_quiet_hours_and_leave_tz_and_locale_untouched() {
        // arrange — AC #2: меняем только тихие часы
        User user = savedUser(8007L, "Europe/Moscow", LocalTime.of(22, 0), LocalTime.of(8, 0), "ru");

        // act
        Profile updated = service.updateProfile(user,
                new ProfileUpdate(LocalTime.of(23, 0), LocalTime.of(7, 30), null, null));

        // assert
        assertSoftly(softly -> {
            softly.assertThat(updated.quietHoursStart()).isEqualTo(LocalTime.of(23, 0));
            softly.assertThat(updated.quietHoursEnd()).isEqualTo(LocalTime.of(7, 30));
            softly.assertThat(updated.timezone()).isEqualTo("Europe/Moscow");
            softly.assertThat(updated.locale()).isEqualTo("ru");
        });

        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertSoftly(softly -> {
            softly.assertThat(reloaded.getQuietHoursStart()).isEqualTo(LocalTime.of(23, 0));
            softly.assertThat(reloaded.getQuietHoursEnd()).isEqualTo(LocalTime.of(7, 30));
            softly.assertThat(reloaded.getLocale()).isEqualTo("ru");
        });
    }

    @Test
    @DisplayName("should_not_null_any_field_when_update_is_empty")
    void should_not_null_any_field_when_update_is_empty() {
        // arrange — AC #2 граница: пустой апдейт = всё без изменений
        User user = savedUser(8008L, "Europe/Moscow", LocalTime.of(21, 0), LocalTime.of(6, 0), "en");

        // act
        Profile updated = service.updateProfile(user,
                new ProfileUpdate(null, null, null, null));

        // assert
        assertSoftly(softly -> {
            softly.assertThat(updated.timezone()).isEqualTo("Europe/Moscow");
            softly.assertThat(updated.locale()).isEqualTo("en");
            softly.assertThat(updated.quietHoursStart()).isEqualTo(LocalTime.of(21, 0));
            softly.assertThat(updated.quietHoursEnd()).isEqualTo(LocalTime.of(6, 0));
        });
    }

    // ------------------------------------------------------------------ PATCH timezone

    @Test
    @DisplayName("should_update_timezone_to_valid_iana_and_reflect_in_profile")
    void should_update_timezone_to_valid_iana_and_reflect_in_profile() {
        // arrange — AC #3: Europe/Moscow → Asia/Almaty
        User user = savedUser(8009L, "Europe/Moscow", LocalTime.of(22, 0), LocalTime.of(9, 0), "ru");

        // act
        Profile updated = service.updateProfile(user,
                new ProfileUpdate(null, null, "Asia/Almaty", null));

        // assert
        assertThat(updated.timezone()).isEqualTo("Asia/Almaty");
        assertThat(userRepository.findById(user.getId()).orElseThrow().getTimezone())
                .isEqualTo("Asia/Almaty");
    }

    // ------------------------------------------------------------------ PATCH failure

    @Test
    @DisplayName("should_throw_illegal_argument_when_timezone_is_not_valid_iana")
    void should_throw_illegal_argument_when_timezone_is_not_valid_iana() {
        // arrange — AC #5: невалидный IANA-id → IllegalArgumentException (контроллер → 400)
        User user = savedUser(8010L, "Europe/Moscow", LocalTime.of(22, 0), LocalTime.of(9, 0), "ru");

        // act + assert
        assertThatThrownBy(() -> service.updateProfile(user,
                new ProfileUpdate(null, null, "Mars/Phobos", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Mars/Phobos");
    }

    // ===================== helpers =====================

    private User savedUser(Long chatId, String timezone, LocalTime quietStart, LocalTime quietEnd, String locale) {
        return userRepository.save(User.builder()
                .telegramChatId(chatId)
                .username("user-" + chatId)
                .timezone(timezone)
                .quietHoursStart(quietStart)
                .quietHoursEnd(quietEnd)
                .locale(locale)
                .blocked(false)
                .build());
    }

    private Location savedDefaultLocation(User user) {
        return locationRepository.findByUserIdAndDefaultLocationTrue(user.getId())
                .orElseGet(() -> locationRepository.save(Location.builder()
                        .user(user)
                        .name(Location.DEFAULT_NAME)
                        .emoji(Location.DEFAULT_EMOJI)
                        .defaultLocation(true)
                        .build()));
    }

    private Plant savedPlant(User user, String name) {
        return plantRepository.save(Plant.builder()
                .user(user)
                .location(savedDefaultLocation(user))
                .name(name)
                .acquiredAt(LocalDate.of(2024, 1, 1))
                .build());
    }

    private CareSchedule savedSchedule(Plant plant, TaskType type, LocalDateTime nextDueAt) {
        return careScheduleRepository.save(CareSchedule.builder()
                .plant(plant)
                .taskType(type)
                .intervalDays(7)
                .nextDueAt(nextDueAt)
                .active(true)
                .build());
    }

    private CareHistory savedCare(Plant plant, TaskType type, LocalDateTime doneAt) {
        return careHistoryRepository.save(CareHistory.builder()
                .plant(plant)
                .taskType(type)
                .doneAt(doneAt)
                .onTime(true)
                .cancelledBy(null)
                .build());
    }

    private static LocalDateTime utc(String isoLocal) {
        return LocalDateTime.parse(isoLocal).truncatedTo(ChronoUnit.MICROS);
    }
}
