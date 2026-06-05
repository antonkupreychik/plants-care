package com.plantcare.core.service;

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
import com.plantcare.core.service.TodayApiService.TodayTask;
import com.plantcare.bot.support.FixedClockTestConfig;
import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * Интеграционные тесты done-фида {@code /today} (issue #184, ADR-014) на реальном
 * Postgres через Testcontainers — выборка UNION pending+done и матчинг
 * {@code care_history} ↔ {@code care_schedules} проверяются на настоящих записях,
 * а не на моках репозитория.
 *
 * <p>{@link com.plantcare.bot.support.FixedClockTestConfig} подменяет {@code Clock}-бин
 * фиксированным моментом {@link FixedClockTestConfig#FIXED_NOW}, чтобы границы окна
 * «сегодня» в TZ юзера были детерминированными. Общий конфиг обеспечивает совместное
 * использование {@code ApplicationContext} между тестами (issue #239).
 *
 * <p>Покрывает AC #184: pending; done-сегодня (с уехавшим вперёд nextDueAt);
 * дедуп pending+done; ретро-отметка (вчера) не считается сегодня; cancelled не
 * считается; пустой день; не-UTC TZ на границе суток.
 */
@DisplayName("TodayApiService — done-фид /today (issue #184)")
@Import(FixedClockTestConfig.class)
class TodayApiServiceTest extends IntegrationTestBase {

    /** Фиксированный «сейчас» — делегируем к общей конфигурации. */
    static final Instant FIXED_NOW = FixedClockTestConfig.FIXED_NOW;

    @Autowired private TodayApiService service;

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
    @DisplayName("should_return_pending_task_without_doneAt_when_due_today_and_no_history")
    void should_return_pending_task_without_doneAt_when_due_today_and_no_history() {
        // arrange — расписание с дедлайном сегодня, без записей в care_history
        User user = savedUser(9001L, "UTC");
        Plant plant = savedPlant(user, "Монстера");
        savedSchedule(plant, TaskType.WATERING, utc("2026-05-25T10:00:00"));

        // act
        List<TodayTask> tasks = service.getTodayTasks(user.getId(), "UTC");

        // assert
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).doneAt()).isNull();
        assertThat(tasks.get(0).schedule().getTaskType()).isEqualTo(TaskType.WATERING);
    }

    @Test
    @DisplayName("should_include_done_today_even_when_nextDueAt_moved_to_future")
    void should_include_done_today_even_when_nextDueAt_moved_to_future() {
        // arrange — отметили сегодня, markCareDone сдвинул nextDueAt вперёд (выпал из pending),
        // но done-запись за сегодня обязана вернуть задачу с doneAt (ключевой кейс G11)
        User user = savedUser(9002L, "UTC");
        Plant plant = savedPlant(user, "Фикус");
        savedSchedule(plant, TaskType.WATERING, utc("2026-05-30T10:00:00")); // в будущем
        savedCare(plant, TaskType.WATERING, utc("2026-05-25T07:42:00"), null);

        // act
        List<TodayTask> tasks = service.getTodayTasks(user.getId(), "UTC");

        // assert
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).doneAt()).isEqualTo(utc("2026-05-25T07:42:00"));
    }

    @Test
    @DisplayName("should_dedup_pending_and_done_into_single_done_task")
    void should_dedup_pending_and_done_into_single_done_task() {
        // arrange — задача и в pending (дедлайн сегодня), и с done-записью за сегодня
        User user = savedUser(9003L, "UTC");
        Plant plant = savedPlant(user, "Орхидея");
        savedSchedule(plant, TaskType.MISTING, utc("2026-05-25T08:00:00"));
        savedCare(plant, TaskType.MISTING, utc("2026-05-25T09:00:00"), null);

        // act
        List<TodayTask> tasks = service.getTodayTasks(user.getId(), "UTC");

        // assert — одна запись, в пользу done
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).doneAt()).isEqualTo(utc("2026-05-25T09:00:00"));
    }

    @Test
    @DisplayName("should_not_count_retro_done_from_yesterday_as_done_today")
    void should_not_count_retro_done_from_yesterday_as_done_today() {
        // arrange — отметка задним числом за вчера; nextDueAt в будущем (не pending)
        User user = savedUser(9004L, "UTC");
        Plant plant = savedPlant(user, "Кактус");
        savedSchedule(plant, TaskType.WATERING, utc("2026-05-30T10:00:00"));
        savedCare(plant, TaskType.WATERING, utc("2026-05-24T12:00:00"), null); // вчера

        // act
        List<TodayTask> tasks = service.getTodayTasks(user.getId(), "UTC");

        // assert — вчерашняя отметка не делает задачу done-сегодня, и pending нет → пусто
        assertThat(tasks).isEmpty();
    }

    @Test
    @DisplayName("should_not_count_cancelled_history_as_done")
    void should_not_count_cancelled_history_as_done() {
        // arrange — done-запись за сегодня, но отменённая (cancelledBy != null)
        User user = savedUser(9005L, "UTC");
        Plant plant = savedPlant(user, "Спатифиллум");
        savedSchedule(plant, TaskType.FERTILIZING, utc("2026-05-30T10:00:00")); // не pending
        CareHistory compensating = savedCare(plant, TaskType.FERTILIZING, utc("2026-05-20T10:00:00"), null);
        savedCare(plant, TaskType.FERTILIZING, utc("2026-05-25T11:00:00"), compensating); // cancelled

        // act
        List<TodayTask> tasks = service.getTodayTasks(user.getId(), "UTC");

        // assert — отменённая запись не считается done, pending нет → пусто
        assertThat(tasks).isEmpty();
    }

    @Test
    @DisplayName("should_return_empty_when_user_has_no_schedules")
    void should_return_empty_when_user_has_no_schedules() {
        // arrange
        User user = savedUser(9006L, "UTC");

        // act
        List<TodayTask> tasks = service.getTodayTasks(user.getId(), "UTC");

        // assert
        assertThat(tasks).isEmpty();
    }

    @Test
    @DisplayName("should_match_done_on_day_boundary_in_user_timezone_not_utc")
    void should_match_done_on_day_boundary_in_user_timezone_not_utc() {
        // arrange — юзер в Asia/Almaty (UTC+5). FIXED_NOW = 2026-05-25 00:30 UTC = 05:30 Алматы.
        // Окно «сегодня» по Алматы: [2026-05-24 19:00 UTC; 2026-05-25 18:59:59 UTC].
        // done_at = 2026-05-24 20:00 UTC = 2026-05-25 01:00 Алматы → «сегодня» локально, в окне;
        // при наивном UTC-расчёте (граница 2026-05-25 00:00 UTC) выпало бы. nextDueAt в будущем.
        User user = savedUser(9007L, "Asia/Almaty");
        Plant plant = savedPlant(user, "Алматинская монстера");
        savedSchedule(plant, TaskType.WATERING, utc("2026-05-30T10:00:00"));
        savedCare(plant, TaskType.WATERING, utc("2026-05-24T20:00:00"), null);

        // act
        List<TodayTask> tasks = service.getTodayTasks(user.getId(), "Asia/Almaty");

        // assert — отметка засчитана как done сегодня по TZ юзера
        assertSoftly(softly -> {
            softly.assertThat(tasks).hasSize(1);
            softly.assertThat(tasks.get(0).doneAt()).isEqualTo(utc("2026-05-24T20:00:00"));
        });
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

    private CareHistory savedCare(Plant plant, TaskType type, LocalDateTime doneAt, CareHistory cancelledBy) {
        return careHistoryRepository.save(CareHistory.builder()
                .plant(plant)
                .taskType(type)
                .doneAt(doneAt)
                .onTime(true)
                .cancelledBy(cancelledBy)
                .build());
    }

    private static LocalDateTime utc(String isoLocal) {
        return LocalDateTime.parse(isoLocal).truncatedTo(ChronoUnit.MICROS);
    }
}
