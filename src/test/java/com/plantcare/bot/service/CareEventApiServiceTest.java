package com.plantcare.bot.service;

import com.plantcare.bot.domain.CareHistory;
import com.plantcare.bot.domain.CareSchedule;
import com.plantcare.bot.domain.Location;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.TaskType;
import com.plantcare.bot.repository.CareHistoryRepository;
import com.plantcare.bot.repository.CareScheduleRepository;
import com.plantcare.bot.repository.LocationRepository;
import com.plantcare.bot.repository.PlantRepository;
import com.plantcare.bot.repository.UserRepository;
import com.plantcare.bot.support.IntegrationTestBase;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Интеграционные тесты {@link CareEventApiService} на реальном Postgres через Testcontainers.
 */
class CareEventApiServiceTest extends IntegrationTestBase {

    @Autowired
    private CareEventApiService careEventApiService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LocationRepository locationRepository;

    @Autowired
    private PlantRepository plantRepository;

    @Autowired
    private CareScheduleRepository careScheduleRepository;

    @Autowired
    private CareHistoryRepository careHistoryRepository;

    @AfterEach
    void cleanup() {
        careHistoryRepository.deleteAll();
        careScheduleRepository.deleteAll();
        plantRepository.deleteAll();
        locationRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ===================== registerEvent =====================

    @Test
    void should_create_care_history_and_reschedule_when_register_event_happy_path() {
        // arrange
        User user = savedUser(1001L, "UTC");
        Plant plant = savedPlant(user, "Монстера");
        LocalDateTime nextDueAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        CareSchedule schedule = savedSchedule(plant, TaskType.WATERING, nextDueAt, 7);

        Instant performedAt = nextDueAt.toInstant(ZoneOffset.UTC);

        // act
        CareHistory result = careEventApiService.registerEvent(
                user.getId(), plant.getId(), TaskType.WATERING, performedAt, "test note", null);

        // assert
        assertThat(result.getId()).isNotNull();
        assertThat(result.getPlant().getId()).isEqualTo(plant.getId());
        assertThat(result.getTaskType()).isEqualTo(TaskType.WATERING);
        assertThat(result.isOnTime()).isTrue();
        assertThat(result.getNote()).isEqualTo("test note");

        CareSchedule updatedSchedule = careScheduleRepository.findById(schedule.getId()).orElseThrow();
        LocalDateTime expectedNextDueAt = LocalDateTime.ofInstant(performedAt, ZoneOffset.UTC)
                .plusDays(schedule.getIntervalDays());
        assertThat(updatedSchedule.getNextDueAt()).isEqualTo(expectedNextDueAt);
    }

    @Test
    void should_return_existing_record_when_client_id_already_seen() {
        // arrange
        User user = savedUser(1002L, "UTC");
        Plant plant = savedPlant(user, "Фикус");
        CareSchedule schedule = savedSchedule(
                plant, TaskType.WATERING,
                LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS), 3);

        String clientId = UUID.randomUUID().toString();
        Instant performedAt = schedule.getNextDueAt().toInstant(ZoneOffset.UTC);

        CareHistory first = careEventApiService.registerEvent(
                user.getId(), plant.getId(), TaskType.WATERING, performedAt, null, clientId);

        // act
        CareHistory second = careEventApiService.registerEvent(
                user.getId(), plant.getId(), TaskType.WATERING, performedAt, null, clientId);

        // assert — идентичный объект, в БД одна запись
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(careHistoryRepository.count()).isEqualTo(1L);
    }

    @Test
    void should_create_history_without_idempotency_check_when_client_id_is_null() {
        // arrange
        User user = savedUser(1003L, "UTC");
        Plant plant = savedPlant(user, "Алоэ");
        LocalDateTime nextDueAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        savedSchedule(plant, TaskType.MISTING, nextDueAt, 5);

        Instant performedAt = nextDueAt.toInstant(ZoneOffset.UTC);

        // act
        CareHistory first = careEventApiService.registerEvent(
                user.getId(), plant.getId(), TaskType.MISTING, performedAt, null, null);
        CareHistory second = careEventApiService.registerEvent(
                user.getId(), plant.getId(), TaskType.MISTING, performedAt.plusSeconds(10), null, null);

        // assert — два разных объекта, обе записи в БД (null clientId = нет idempotency)
        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(careHistoryRepository.count()).isEqualTo(2L);
    }

    @Test
    void should_throw_entity_not_found_when_plant_belongs_to_another_user() {
        // arrange
        User owner = savedUser(1004L, "UTC");
        User intruder = savedUser(1005L, "UTC");
        Plant plant = savedPlant(owner, "Пальма");
        savedSchedule(plant, TaskType.WATERING,
                LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS), 7);

        Instant performedAt = Instant.now();

        // act + assert
        assertThatThrownBy(() -> careEventApiService.registerEvent(
                intruder.getId(), plant.getId(), TaskType.WATERING, performedAt, null, null))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining(String.valueOf(plant.getId()));
    }

    @Test
    void should_throw_entity_not_found_when_no_active_schedule_for_task_type() {
        // arrange
        User user = savedUser(1006L, "UTC");
        Plant plant = savedPlant(user, "Кактус");
        // Нет расписания для FERTILIZING
        savedSchedule(plant, TaskType.WATERING,
                LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS), 7);

        Instant performedAt = Instant.now();

        // act + assert
        assertThatThrownBy(() -> careEventApiService.registerEvent(
                user.getId(), plant.getId(), TaskType.FERTILIZING, performedAt, null, null))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("FERTILIZING");
    }

    @Test
    void should_mark_on_time_true_when_performed_within_grace_period() {
        // arrange
        User user = savedUser(1007L, "UTC");
        Plant plant = savedPlant(user, "Орхидея");
        LocalDateTime nextDueAt = LocalDateTime.parse("2026-05-22T10:00:00");
        savedSchedule(plant, TaskType.WATERING, nextDueAt, 7);

        // performedAt = nextDueAt + 23 часа (в пределах льготного окна 24ч)
        Instant performedAt = nextDueAt.plusHours(23).toInstant(ZoneOffset.UTC);

        // act
        CareHistory result = careEventApiService.registerEvent(
                user.getId(), plant.getId(), TaskType.WATERING, performedAt, null, null);

        // assert
        assertThat(result.isOnTime()).isTrue();
    }

    @Test
    void should_mark_on_time_false_when_performed_after_grace_period() {
        // arrange
        User user = savedUser(1008L, "UTC");
        Plant plant = savedPlant(user, "Хлорофитум");
        LocalDateTime nextDueAt = LocalDateTime.parse("2026-05-22T10:00:00");
        savedSchedule(plant, TaskType.WATERING, nextDueAt, 7);

        // performedAt = nextDueAt + 25 часов (за пределами льготного окна 24ч)
        Instant performedAt = nextDueAt.plusHours(25).toInstant(ZoneOffset.UTC);

        // act
        CareHistory result = careEventApiService.registerEvent(
                user.getId(), plant.getId(), TaskType.WATERING, performedAt, null, null);

        // assert
        assertThat(result.isOnTime()).isFalse();
    }

    // ===================== cancelEvent =====================

    @Test
    void should_create_compensation_record_and_mark_original_cancelled_when_cancel_event() {
        // arrange
        User user = savedUser(1009L, "UTC");
        Plant plant = savedPlant(user, "Диффенбахия");
        savedSchedule(plant, TaskType.WATERING,
                LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS), 7);

        CareHistory original = careEventApiService.registerEvent(
                user.getId(), plant.getId(), TaskType.WATERING, Instant.now(), null, null);

        // act
        careEventApiService.cancelEvent(user.getId(), original.getId());

        // assert
        CareHistory updated = careHistoryRepository.findById(original.getId()).orElseThrow();
        assertThat(updated.isCancelled()).isTrue();
        assertThat(updated.getCancelledBy()).isNotNull();
        assertThat(careHistoryRepository.count()).isEqualTo(2L); // оригинал + компенсирующая
    }

    @Test
    void should_throw_illegal_state_when_cancel_already_cancelled_event() {
        // arrange
        User user = savedUser(1010L, "UTC");
        Plant plant = savedPlant(user, "Замиокулькас");
        savedSchedule(plant, TaskType.WATERING,
                LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS), 7);

        CareHistory original = careEventApiService.registerEvent(
                user.getId(), plant.getId(), TaskType.WATERING, Instant.now(), null, null);

        careEventApiService.cancelEvent(user.getId(), original.getId());

        // act + assert
        assertThatThrownBy(() -> careEventApiService.cancelEvent(user.getId(), original.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already cancelled");
    }

    @Test
    void should_throw_access_denied_when_cancel_event_of_another_user() {
        // arrange
        User owner = savedUser(1011L, "UTC");
        User intruder = savedUser(1012L, "UTC");
        Plant plant = savedPlant(owner, "Сансевьера");
        savedSchedule(plant, TaskType.WATERING,
                LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS), 7);

        CareHistory original = careEventApiService.registerEvent(
                owner.getId(), plant.getId(), TaskType.WATERING, Instant.now(), null, null);

        // act + assert
        assertThatThrownBy(() -> careEventApiService.cancelEvent(intruder.getId(), original.getId()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void should_throw_entity_not_found_when_cancel_nonexistent_history_id() {
        // arrange
        User user = savedUser(1013L, "UTC");

        // act + assert
        assertThatThrownBy(() -> careEventApiService.cancelEvent(user.getId(), 999_999L))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("999999");
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
        Location location = savedDefaultLocation(user);
        return plantRepository.save(Plant.builder()
                .user(user)
                .location(location)
                .name(name)
                .build());
    }

    private CareSchedule savedSchedule(Plant plant, TaskType taskType, LocalDateTime nextDueAt, int intervalDays) {
        return careScheduleRepository.save(CareSchedule.builder()
                .plant(plant)
                .taskType(taskType)
                .intervalDays(intervalDays)
                .nextDueAt(nextDueAt)
                .active(true)
                .build());
    }
}
