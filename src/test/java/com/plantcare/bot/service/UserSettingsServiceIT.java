package com.plantcare.bot.service;

import com.plantcare.bot.domain.CareSchedule;
import com.plantcare.bot.domain.Location;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.TaskType;
import com.plantcare.bot.repository.CareScheduleRepository;
import com.plantcare.bot.repository.LocationRepository;
import com.plantcare.bot.repository.PlantRepository;
import com.plantcare.bot.repository.UserRepository;
import com.plantcare.bot.service.UserSettingsService.TimezoneChangeResult;
import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционные тесты {@link UserSettingsService} (issue #116) на реальном Postgres
 * (Testcontainers, не H2). Проверяем пересчёт {@code nextDueAt} при смене таймзоны,
 * исключение архивных растений, флаг конфликта с тихими часами и сеттеры тихих часов.
 *
 * <p>Семантика: {@code nextDueAt} хранится как UTC wall-clock. Пересчёт сохраняет
 * локальное время дня в новой зоне (полить в 09:00 в Минске остаётся 09:00 в Алматы).
 *
 * <p>Контейнер Postgres переиспользуется (reuse=true) и шарится между классами в
 * рамках одного {@code mvn verify}. Поэтому этот тест НЕ делает глобальный
 * {@code deleteAll()} — это затирало бы строки соседних ITs (например
 * {@code MonthlyReportServiceTest}) и роняло их FK/optimistic-lock. Вместо этого
 * каждый тест работает на уникальных telegram chatId (диапазон 200–207) и удаляет в
 * {@code @AfterEach} ровно те сущности, которые сам вставил.
 */
@DisplayName("UserSettingsService — смена таймзоны и тихих часов (#116)")
class UserSettingsServiceIT extends IntegrationTestBase {

    private static final ZoneId MINSK = ZoneId.of("Europe/Minsk");
    private static final ZoneId ALMATY = ZoneId.of("Asia/Almaty");
    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");

    @Autowired
    private UserSettingsService userSettingsService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LocationRepository locationRepository;

    @Autowired
    private PlantRepository plantRepository;

    @Autowired
    private CareScheduleRepository careScheduleRepository;

    // Сущности, вставленные текущим тестом. Чистим только их, не трогая чужие строки.
    private final List<CareSchedule> createdSchedules = new ArrayList<>();
    private final List<Plant> createdPlants = new ArrayList<>();
    private final List<Location> createdLocations = new ArrayList<>();
    private final List<User> createdUsers = new ArrayList<>();

    @AfterEach
    void cleanupOwnRowsOnly() {
        // Удаляем строго в порядке FK: schedule → plant → location → user.
        careScheduleRepository.deleteAll(createdSchedules);
        plantRepository.deleteAll(createdPlants);
        locationRepository.deleteAll(createdLocations);
        userRepository.deleteAll(createdUsers);

        createdSchedules.clear();
        createdPlants.clear();
        createdLocations.clear();
        createdUsers.clear();
    }

    @Test
    @DisplayName("Минск → Алматы: локальное время дня расписания сохраняется (09:00), rescheduledCount == 1")
    void should_preserve_local_time_of_day_when_changing_timezone_minsk_to_almaty() {
        // arrange
        User user = saveUser(200L, "Europe/Minsk");
        Plant plant = savePlant(user, "Монстера", false);
        // 06:00 UTC == 09:00 в Минске (UTC+3)
        CareSchedule schedule = saveSchedule(plant, TaskType.WATERING,
                LocalDateTime.of(2026, 6, 1, 6, 0), true);

        // act
        TimezoneChangeResult result = userSettingsService.changeTimezone(user, ALMATY);

        // assert
        assertThat(result.newZoneId()).isEqualTo("Asia/Almaty");
        assertThat(result.rescheduledCount()).isEqualTo(1);

        LocalDateTime newNext = careScheduleRepository.findById(schedule.getId())
                .orElseThrow()
                .getNextDueAt();
        // в новой зоне локальное время дня == то же 09:00, что было в Минске
        LocalTime localInAlmaty = newNext.toInstant(ZoneOffset.UTC).atZone(ALMATY).toLocalTime();
        assertThat(localInAlmaty).isEqualTo(LocalTime.of(9, 0));
        // конкретно: 09:00 Алматы (UTC+5) == 04:00 UTC того же дня
        assertThat(newNext).isEqualTo(LocalDateTime.of(2026, 6, 1, 4, 0));

        assertThat(userRepository.findById(user.getId()).orElseThrow().getTimezone())
                .isEqualTo("Asia/Almaty");
    }

    @Test
    @DisplayName("Asia/Almaty → Europe/Warsaw через spring-forward gap (2026-03-29 02:30): момент сдвигается на 03:30 CEST")
    void should_keep_bridge_through_utc_correct_across_dst_spring_forward() {
        // arrange: исходная зона Almaty без DST (UTC+5), целевая Warsaw с DST.
        // 2026-03-29 02:00→03:00 — весенний перевод (CET→CEST). Локальное 02:30 в этот
        // день в Варшаве НЕ существует (gap). Подбираем nextDueAt так, чтобы локальное
        // время дня в Almaty было ровно 02:30 → при мосте через UTC оно реинтерпретируется
        // как 02:30 Warsaw и попадает в дыру.
        // 02:30 Almaty (UTC+5) == 21:30 UTC предыдущего дня (2026-03-28).
        User user = saveUser(207L, "Asia/Almaty");
        Plant plant = savePlant(user, "DST монстера", false);
        CareSchedule schedule = saveSchedule(plant, TaskType.WATERING,
                LocalDateTime.of(2026, 3, 28, 21, 30), true);

        // act
        TimezoneChangeResult result = userSettingsService.changeTimezone(user, WARSAW);

        // assert
        assertThat(result.rescheduledCount()).isEqualTo(1);

        LocalDateTime newNext = careScheduleRepository.findById(schedule.getId())
                .orElseThrow()
                .getNextDueAt();

        // ZonedDateTime.of для несуществующего локального времени сдвигает вперёд на длину
        // перехода: 02:30 → 03:30 CEST (UTC+2). Это и есть корректное поведение моста.
        java.time.ZonedDateTime newLocal = newNext.toInstant(ZoneOffset.UTC).atZone(WARSAW);
        assertThat(newLocal.toLocalTime()).isEqualTo(LocalTime.of(3, 30));
        assertThat(newLocal.getOffset()).isEqualTo(ZoneOffset.ofHours(2)); // CEST после перевода
        assertThat(newLocal.toLocalDate()).isEqualTo(java.time.LocalDate.of(2026, 3, 29));

        // и в терминах хранимого UTC wall-clock: 03:30 CEST == 01:30 UTC того же дня
        assertThat(newNext).isEqualTo(LocalDateTime.of(2026, 3, 29, 1, 30));
    }

    @Test
    @DisplayName("Расписание на архивном растении НЕ пересчитывается и не попадает в счётчик")
    void should_not_reschedule_archived_plant_schedules() {
        // arrange
        User user = saveUser(201L, "Europe/Minsk");

        Plant active = savePlant(user, "Живая монстера", false);
        CareSchedule activeSchedule = saveSchedule(active, TaskType.WATERING,
                LocalDateTime.of(2026, 6, 1, 6, 0), true);

        Plant archived = savePlant(user, "Мёртвая монстера", true);
        LocalDateTime archivedNextBefore = LocalDateTime.of(2026, 6, 1, 6, 0);
        CareSchedule archivedSchedule = saveSchedule(archived, TaskType.WATERING,
                archivedNextBefore, true);

        // act
        TimezoneChangeResult result = userSettingsService.changeTimezone(user, ALMATY);

        // assert
        assertThat(result.rescheduledCount()).isEqualTo(1);

        LocalDateTime archivedNextAfter = careScheduleRepository.findById(archivedSchedule.getId())
                .orElseThrow()
                .getNextDueAt();
        assertThat(archivedNextAfter).isEqualTo(archivedNextBefore);

        LocalDateTime activeNextAfter = careScheduleRepository.findById(activeSchedule.getId())
                .orElseThrow()
                .getNextDueAt();
        assertThat(activeNextAfter).isEqualTo(LocalDateTime.of(2026, 6, 1, 4, 0));
    }

    @Test
    @DisplayName("Новый момент попадает в тихие часы (22:00–09:00) в новой зоне → quietHoursConflict == true")
    void should_flag_quiet_hours_conflict_when_recomputed_instant_lands_in_quiet_window() {
        // arrange: 03:00 в Минске → после переезда в Алматы локальное время дня остаётся
        // 03:00, что внутри тихих часов 22:00–09:00.
        User user = saveUser(202L, "Europe/Minsk");
        Plant plant = savePlant(user, "Ночная монстера", false);
        // 00:00 UTC == 03:00 в Минске (UTC+3)
        saveSchedule(plant, TaskType.WATERING,
                LocalDateTime.of(2026, 6, 1, 0, 0), true);

        // act
        TimezoneChangeResult result = userSettingsService.changeTimezone(user, ALMATY);

        // assert
        assertThat(result.quietHoursConflict()).isTrue();
    }

    @Test
    @DisplayName("Новый момент вне тихих часов → quietHoursConflict == false")
    void should_not_flag_quiet_hours_conflict_when_recomputed_instant_is_in_daytime() {
        // arrange: 09:00 в Минске → 09:00 локально в Алматы, конец тихих часов (исключительно) — не quiet
        User user = saveUser(203L, "Europe/Minsk");
        Plant plant = savePlant(user, "Дневная монстера", false);
        // 06:00 UTC == 09:00 в Минске
        saveSchedule(plant, TaskType.WATERING,
                LocalDateTime.of(2026, 6, 1, 6, 0), true);

        // act
        TimezoneChangeResult result = userSettingsService.changeTimezone(user, ALMATY);

        // assert
        assertThat(result.quietHoursConflict()).isFalse();
    }

    @Test
    @DisplayName("Нет активных расписаний → rescheduledCount == 0, таймзона всё равно меняется")
    void should_change_timezone_with_zero_rescheduled_when_no_active_schedules() {
        // arrange
        User user = saveUser(204L, "Europe/Minsk");

        // act
        TimezoneChangeResult result = userSettingsService.changeTimezone(user, ALMATY);

        // assert
        assertThat(result.rescheduledCount()).isZero();
        assertThat(result.quietHoursConflict()).isFalse();
        assertThat(userRepository.findById(user.getId()).orElseThrow().getTimezone())
                .isEqualTo("Asia/Almaty");
    }

    @Test
    @DisplayName("setQuietHoursStart / setQuietHoursEnd персистятся в БД")
    void should_persist_quiet_hours_start_and_end() {
        // arrange
        User user = saveUser(205L, "Europe/Minsk");

        // act
        userSettingsService.setQuietHoursStart(user, LocalTime.of(23, 30));
        userSettingsService.setQuietHoursEnd(user, LocalTime.of(8, 15));

        // assert
        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getQuietHoursStart()).isEqualTo(LocalTime.of(23, 30));
        assertThat(reloaded.getQuietHoursEnd()).isEqualTo(LocalTime.of(8, 15));
    }

    @Test
    @DisplayName("resetQuietHours возвращает дефолт 22:00 / 09:00")
    void should_reset_quiet_hours_to_default() {
        // arrange
        User user = saveUser(206L, "Europe/Minsk");
        userSettingsService.setQuietHoursStart(user, LocalTime.of(1, 0));
        userSettingsService.setQuietHoursEnd(user, LocalTime.of(2, 0));

        // act
        userSettingsService.resetQuietHours(user);

        // assert
        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getQuietHoursStart()).isEqualTo(LocalTime.of(22, 0));
        assertThat(reloaded.getQuietHoursEnd()).isEqualTo(LocalTime.of(9, 0));
    }

    // ====================================================================
    // fixtures
    // ====================================================================

    private User saveUser(Long chatId, String timezone) {
        User user = userRepository.save(User.builder()
                .telegramChatId(chatId)
                .timezone(timezone)
                .blocked(false)
                .quietHoursStart(LocalTime.of(22, 0))
                .quietHoursEnd(LocalTime.of(9, 0))
                .build());
        createdUsers.add(user);
        return user;
    }

    private Location saveDefaultLocation(User user) {
        return locationRepository.findByUserIdAndDefaultLocationTrue(user.getId())
                .orElseGet(() -> {
                    Location location = locationRepository.save(Location.builder()
                            .user(user)
                            .name(Location.DEFAULT_NAME)
                            .emoji(Location.DEFAULT_EMOJI)
                            .defaultLocation(true)
                            .build());
                    createdLocations.add(location);
                    return location;
                });
    }

    private Plant savePlant(User user, String name, boolean archived) {
        Location location = saveDefaultLocation(user);
        Plant plant = plantRepository.save(Plant.builder()
                .user(user)
                .location(location)
                .name(name)
                .build());
        if (archived) {
            plant.archive();
            plant = plantRepository.save(plant);
        }
        createdPlants.add(plant);
        return plant;
    }

    private CareSchedule saveSchedule(Plant plant, TaskType taskType, LocalDateTime nextDueAt, boolean active) {
        CareSchedule schedule = careScheduleRepository.save(CareSchedule.builder()
                .plant(plant)
                .taskType(taskType)
                .intervalDays(7)
                .nextDueAt(nextDueAt.truncatedTo(ChronoUnit.MICROS))
                .active(active)
                .build());
        createdSchedules.add(schedule);
        return schedule;
    }
}
