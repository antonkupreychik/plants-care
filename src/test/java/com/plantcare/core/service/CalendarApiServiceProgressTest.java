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
import com.plantcare.core.service.CalendarApiService.DayProgress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционные тесты {@link CalendarApiService#getProgress} на реальном Postgres
 * через Testcontainers (issue #179, gap G11).
 *
 * <p>{@code planned} проецируется из активных расписаний (интервал/nextDueAt),
 * {@code done} агрегируется из {@code care_history} с бакетированием по локальному
 * дню в TZ юзера. Проверяем happy, пустые дни и ключевой TZ-кейс на границе суток.
 */
@DisplayName("CalendarApiService#getProgress — прогресс по дням (#179)")
class CalendarApiServiceProgressTest extends IntegrationTestBase {

    @Autowired private CalendarApiService service;

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

    @Test
    @DisplayName("happy: за диапазон считаются planned (из расписаний) и done (из истории)")
    void should_count_planned_and_done_per_day() {
        // arrange — UTC-юзер; полив каждые 7 дней, nextDueAt = 2026-05-04
        User user = savedUser(5000L, "UTC");
        Plant plant = savedPlant(user, "Монстера");
        savedSchedule(plant, TaskType.WATERING, 7, LocalDateTime.of(2026, 5, 4, 9, 0));

        // Выполнено: 04 мая (день с планом) и 06 мая (день без плана).
        saveCare(plant, TaskType.WATERING, utc(2026, 5, 4, 10, 0));
        saveCare(plant, TaskType.MISTING, utc(2026, 5, 6, 10, 0));

        // act — окно 2026-05-01..2026-05-10
        Map<LocalDate, DayProgress> progress = service.getProgress(
                user.getId(), LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 10), "UTC");

        // assert — 04: planned=1 (occurrence) + done=1; 06: planned=0 + done=1.
        assertThat(progress).containsKeys(LocalDate.of(2026, 5, 4), LocalDate.of(2026, 5, 6));
        assertThat(progress.get(LocalDate.of(2026, 5, 4)))
                .isEqualTo(new DayProgress(1, 1));
        assertThat(progress.get(LocalDate.of(2026, 5, 6)))
                .isEqualTo(new DayProgress(0, 1));
    }

    @Test
    @DisplayName("edge: день без планов и без выполнений отсутствует в карте")
    void should_omit_days_without_any_activity() {
        // arrange — единственное выполнение 5 мая, нет расписаний
        User user = savedUser(5001L, "UTC");
        Plant plant = savedPlant(user, "Монстера");
        saveCare(plant, TaskType.WATERING, utc(2026, 5, 5, 12, 0));

        // act
        Map<LocalDate, DayProgress> progress = service.getProgress(
                user.getId(), LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 10), "UTC");

        // assert — только 5 мая, остальные дни отсутствуют (не {0,0})
        assertThat(progress).containsOnlyKeys(LocalDate.of(2026, 5, 5));
        assertThat(progress.get(LocalDate.of(2026, 5, 5)))
                .isEqualTo(new DayProgress(0, 1));
    }

    @Test
    @DisplayName("edge: cancelled-запись не учитывается в done")
    void should_exclude_cancelled_entry_from_done() {
        // arrange
        User user = savedUser(5002L, "UTC");
        Plant plant = savedPlant(user, "Монстера");

        CareHistory compensation = saveCare(plant, TaskType.WATERING, utc(2026, 5, 5, 18, 0));
        CareHistory original = CareHistory.builder()
                .plant(plant)
                .taskType(TaskType.WATERING)
                .doneAt(LocalDateTime.of(2026, 5, 5, 8, 0))
                .onTime(true)
                .cancelledBy(compensation)
                .build();
        careHistoryRepository.save(original);

        // act
        Map<LocalDate, DayProgress> progress = service.getProgress(
                user.getId(), LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 10), "UTC");

        // assert — done=1 (только активная компенсация), не 2
        assertThat(progress.get(LocalDate.of(2026, 5, 5)))
                .isEqualTo(new DayProgress(0, 1));
    }

    @Test
    @DisplayName("TZ: done бакетируется в локальный день Almaty, а не UTC-день")
    void should_bucket_done_into_local_day_for_almaty() {
        // arrange — Almaty (UTC+5). doneAt 2026-05-05 20:00 UTC = 2026-05-06 01:00 Almaty.
        // По UTC попал бы в 5 мая, по локальному календарю — 6 мая.
        User user = savedUser(5003L, "Asia/Almaty");
        Plant plant = savedPlant(user, "Монстера");
        saveCare(plant, TaskType.WATERING, utc(2026, 5, 5, 20, 0));

        // act
        Map<LocalDate, DayProgress> progress = service.getProgress(
                user.getId(), LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 10), "Asia/Almaty");

        // assert — запись в локальном 6 мая, не в 5 мая
        assertThat(progress).containsOnlyKeys(LocalDate.of(2026, 5, 6));
        assertThat(progress.get(LocalDate.of(2026, 5, 6)))
                .isEqualTo(new DayProgress(0, 1));
    }

    @Test
    @DisplayName("TZ: окно done на границе диапазона учитывает локальные сутки (от/до)")
    void should_respect_local_window_boundaries_for_almaty() {
        // arrange — Almaty (UTC+5). Запрос за один локальный день 2026-05-05.
        // Локальный 2026-05-05 = [2026-05-04 19:00 UTC; 2026-05-05 19:00 UTC).
        User user = savedUser(5004L, "Asia/Almaty");
        Plant plant = savedPlant(user, "Монстера");

        // 2026-05-04 19:00 UTC = 2026-05-05 00:00 Almaty → входит.
        saveCare(plant, TaskType.WATERING, utc(2026, 5, 4, 19, 0));
        // 2026-05-04 18:00 UTC = 2026-05-04 23:00 Almaty → НЕ входит (предыдущий локальный день).
        saveCare(plant, TaskType.MISTING, utc(2026, 5, 4, 18, 0));

        // act — диапазон только 5 мая (один день)
        Map<LocalDate, DayProgress> progress = service.getProgress(
                user.getId(), LocalDate.of(2026, 5, 5), LocalDate.of(2026, 5, 5), "Asia/Almaty");

        // assert
        assertThat(progress).containsOnlyKeys(LocalDate.of(2026, 5, 5));
        assertThat(progress.get(LocalDate.of(2026, 5, 5)))
                .isEqualTo(new DayProgress(0, 1));
    }

    // ===================== helpers =====================

    private User savedUser(Long chatId, String timezone) {
        return userRepository.save(User.builder()
                .telegramChatId(chatId)
                .timezone(timezone)
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
                .build());
    }

    private CareSchedule savedSchedule(Plant plant, TaskType type, int intervalDays,
                                       LocalDateTime nextDueAt) {
        return careScheduleRepository.save(CareSchedule.builder()
                .plant(plant)
                .taskType(type)
                .intervalDays(intervalDays)
                .nextDueAt(nextDueAt)
                .active(true)
                .updatedAt(LocalDateTime.of(2026, 5, 1, 0, 0))
                .build());
    }

    private CareHistory saveCare(Plant plant, TaskType type, LocalDateTime doneAtUtc) {
        return careHistoryRepository.save(CareHistory.builder()
                .plant(plant)
                .taskType(type)
                .doneAt(doneAtUtc.truncatedTo(ChronoUnit.MICROS))
                .onTime(true)
                .build());
    }

    private static LocalDateTime utc(int y, int mo, int d, int h, int mi) {
        return LocalDateTime.of(y, mo, d, h, mi).atZone(ZoneId.of("UTC")).toLocalDateTime();
    }
}
