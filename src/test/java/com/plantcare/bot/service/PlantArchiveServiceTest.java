package com.plantcare.bot.service;

import com.plantcare.core.service.PlantArchiveService;

import com.plantcare.core.domain.CareHistory;
import com.plantcare.core.domain.CareSchedule;
import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.NotificationLog;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.NotificationType;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.repository.CareHistoryRepository;
import com.plantcare.core.repository.CareScheduleRepository;
import com.plantcare.core.repository.LocationRepository;
import com.plantcare.core.repository.NotificationLogRepository;
import com.plantcare.core.repository.PlantAnniversarySentRepository;
import com.plantcare.core.repository.PlantRepository;
import com.plantcare.core.repository.UserRepository;
import com.plantcare.bot.support.IntegrationTestBase;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Интеграционные тесты {@link PlantArchiveService} (issue #117, Part B) на реальном
 * Postgres через Testcontainers.
 *
 * <p>Покрывает:
 *   - count архивных растений (для кнопки «📦 Архив (N)») и счётчик 0 → кнопки нет
 *     (логика отображения в {@link PlantMenuService}, но счётчик считаем тут).
 *   - restore: archived_at становится null, plant возвращается в активные,
 *     расписания НЕ восстанавливаются автоматически — что было active=false,
 *     то и остаётся.
 *   - deleteForever: каскадно подчищает care_schedules, care_history,
 *     notifications_log, plant_anniversaries_sent (через FK ON DELETE CASCADE).
 *     Проверяем через прямой COUNT в БД, чтобы не зависеть от JPA-кеша.
 *   - isOwner: чужое растение → false.
 */
@DisplayName("PlantArchiveService — архив растений (issue #117, Part B)")
class PlantArchiveServiceTest extends IntegrationTestBase {

    @Autowired private PlantArchiveService archiveService;

    @Autowired private UserRepository userRepository;
    @Autowired private LocationRepository locationRepository;
    @Autowired private PlantRepository plantRepository;
    @Autowired private CareScheduleRepository scheduleRepository;
    @Autowired private CareHistoryRepository historyRepository;
    @Autowired private NotificationLogRepository notificationRepository;
    @Autowired private PlantAnniversarySentRepository sentRepository;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        sentRepository.deleteAll();
        notificationRepository.deleteAll();
        historyRepository.deleteAll();
        scheduleRepository.deleteAll();
        plantRepository.deleteAll();
        locationRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("should_count_only_archived_plants_when_counting_for_badge")
    void should_count_only_archived_plants_when_counting_for_badge() {
        // arrange
        User user = savedUser(3001L);
        savedPlant(user, "Активное-1", false);
        savedPlant(user, "Активное-2", false);
        savedPlant(user, "Архивное-1", true);
        savedPlant(user, "Архивное-2", true);
        savedPlant(user, "Архивное-3", true);

        // act
        long archivedCount = plantRepository.countByUserIdAndArchivedAtIsNotNull(user.getId());

        // assert
        assertThat(archivedCount).isEqualTo(3L);
    }

    @Test
    @DisplayName("should_return_zero_count_when_user_has_no_archived_plants")
    void should_return_zero_count_when_user_has_no_archived_plants() {
        // arrange
        User user = savedUser(3002L);
        savedPlant(user, "Только активное", false);

        // act
        long archivedCount = plantRepository.countByUserIdAndArchivedAtIsNotNull(user.getId());

        // assert: ноль → в UI кнопка «📦 Архив» не отрисуется
        assertThat(archivedCount).isZero();
    }

    @Test
    @DisplayName("should_clear_archived_at_and_keep_schedule_state_when_restored")
    void should_clear_archived_at_and_keep_schedule_state_when_restored() {
        // arrange: архивное растение с одним выключенным расписанием
        User user = savedUser(3003L);
        Plant plant = savedPlant(user, "Возвращаемое", true);
        CareSchedule disabled = savedSchedule(plant, TaskType.WATERING, false);

        // sanity
        assertThat(plant.getArchivedAt()).isNotNull();
        assertThat(disabled.isActive()).isFalse();

        // act
        Plant restored = archiveService.restore(user.getId(), plant.getId());

        // assert
        assertThat(restored.getArchivedAt())
                .as("archived_at должен сброситься после restore")
                .isNull();

        // Реальная проверка в БД, не через возвращённый объект
        Plant fromDb = plantRepository.findById(plant.getId()).orElseThrow();
        assertThat(fromDb.getArchivedAt()).isNull();

        // Расписание НЕ должно автоматически восстановиться — было выключено, таким и осталось
        CareSchedule scheduleAfter = scheduleRepository.findById(disabled.getId()).orElseThrow();
        assertThat(scheduleAfter.isActive())
                .as("Restore не должен трогать active-флаги расписаний")
                .isFalse();
    }

    @Test
    @DisplayName("should_throw_when_restore_called_on_non_archived_plant")
    void should_throw_when_restore_called_on_non_archived_plant() {
        // arrange
        User user = savedUser(3004L);
        Plant active = savedPlant(user, "Не в архиве", false);

        // act + assert
        assertThatThrownBy(() -> archiveService.restore(user.getId(), active.getId()))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining(String.valueOf(active.getId()));
    }

    @Test
    @DisplayName("should_throw_when_restore_called_on_other_user_plant")
    void should_throw_when_restore_called_on_other_user_plant() {
        // arrange
        User owner = savedUser(3005L);
        User other = savedUser(3006L);
        Plant ownerPlant = savedPlant(owner, "Чужое", true);

        // act + assert: «другой» юзер не может восстановить чужое архивное растение
        assertThatThrownBy(() -> archiveService.restore(other.getId(), ownerPlant.getId()))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    @DisplayName("should_cascade_delete_all_related_rows_when_delete_forever")
    void should_cascade_delete_all_related_rows_when_delete_forever() {
        // arrange: архивное растение со ВСЕМ связанным
        User user = savedUser(3007L);
        Plant plant = savedPlant(user, "Удалить навсегда", true);
        savedSchedule(plant, TaskType.WATERING, true);
        savedSchedule(plant, TaskType.MISTING, true);
        savedHistory(plant, TaskType.WATERING);
        savedHistory(plant, TaskType.MISTING);
        savedNotification(plant, TaskType.WATERING);
        savedAnniversaryRow(plant.getId(), 2026);

        Long plantId = plant.getId();

        // sanity: всё на месте
        assertThat(countSchedules(plantId)).isEqualTo(2L);
        assertThat(countHistory(plantId)).isEqualTo(2L);
        assertThat(countNotifications(plantId)).isEqualTo(1L);
        assertThat(countAnniversaries(plantId)).isEqualTo(1L);

        // act
        archiveService.deleteForever(user.getId(), plantId);

        // assert: само растение исчезло
        assertThat(plantRepository.findById(plantId)).isEmpty();

        // assert: все связанные строки тоже исчезли через ON DELETE CASCADE.
        // Проверяем JDBC-запросом, чтобы не зависеть от JPA-кеша / lazy-load.
        assertThat(countSchedules(plantId))
                .as("care_schedules должны исчезнуть каскадно")
                .isZero();
        assertThat(countHistory(plantId))
                .as("care_history должна исчезнуть каскадно")
                .isZero();
        assertThat(countNotifications(plantId))
                .as("notifications_log должны исчезнуть каскадно")
                .isZero();
        assertThat(countAnniversaries(plantId))
                .as("plant_anniversaries_sent должны исчезнуть каскадно (V21)")
                .isZero();
    }

    @Test
    @DisplayName("should_throw_when_delete_forever_called_on_non_archived_plant")
    void should_throw_when_delete_forever_called_on_non_archived_plant() {
        // arrange
        User user = savedUser(3008L);
        Plant active = savedPlant(user, "Ещё живо", false);

        // act + assert
        assertThatThrownBy(() -> archiveService.deleteForever(user.getId(), active.getId()))
                .isInstanceOf(EntityNotFoundException.class);

        // Растение НЕ должно быть удалено: операция упала до DELETE
        assertThat(plantRepository.findById(active.getId())).isPresent();
    }

    @Test
    @DisplayName("should_list_archived_in_descending_archived_at_order")
    void should_list_archived_in_descending_archived_at_order() {
        // arrange: три архивных, разные времена архивации
        User user = savedUser(3009L);
        Plant first = savedPlant(user, "Архив-1", false);
        Plant middle = savedPlant(user, "Архив-2", false);
        Plant last = savedPlant(user, "Архив-3", false);
        // Архивируем в обратном порядке времени, чтобы сортировка была видна
        first.setArchivedAt(LocalDateTime.now().minusDays(10).truncatedTo(ChronoUnit.MICROS));
        middle.setArchivedAt(LocalDateTime.now().minusDays(5).truncatedTo(ChronoUnit.MICROS));
        last.setArchivedAt(LocalDateTime.now().minusDays(1).truncatedTo(ChronoUnit.MICROS));
        plantRepository.save(first);
        plantRepository.save(middle);
        plantRepository.save(last);

        // act
        var list = archiveService.listArchived(user.getId());

        // assert: новые (last) сверху
        assertThat(list)
                .extracting(Plant::getName)
                .containsExactly("Архив-3", "Архив-2", "Архив-1");
    }

    @Test
    @DisplayName("should_return_false_when_isOwner_checks_other_user_plant")
    void should_return_false_when_isOwner_checks_other_user_plant() {
        // arrange
        User owner = savedUser(3010L);
        User other = savedUser(3011L);
        Plant plant = savedPlant(owner, "Принадлежит owner", false);

        // act + assert
        assertThat(archiveService.isOwner(owner, plant.getId())).isTrue();
        assertThat(archiveService.isOwner(other, plant.getId())).isFalse();
    }

    // ===================== helpers =====================

    private User savedUser(Long chatId) {
        return userRepository.save(User.builder()
                .telegramChatId(chatId)
                .timezone("UTC")
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

    private Plant savedPlant(User user, String name, boolean archived) {
        Plant.PlantBuilder builder = Plant.builder()
                .user(user)
                .location(savedDefaultLocation(user))
                .name(name);
        if (archived) {
            builder.archivedAt(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
        }
        return plantRepository.save(builder.build());
    }

    private CareSchedule savedSchedule(Plant plant, TaskType type, boolean active) {
        return scheduleRepository.save(CareSchedule.builder()
                .plant(plant)
                .taskType(type)
                .intervalDays(7)
                .nextDueAt(LocalDateTime.now().plusDays(7).truncatedTo(ChronoUnit.MICROS))
                .active(active)
                .build());
    }

    private void savedHistory(Plant plant, TaskType type) {
        historyRepository.save(CareHistory.builder()
                .plant(plant)
                .taskType(type)
                .doneAt(LocalDateTime.now().minusDays(1).truncatedTo(ChronoUnit.MICROS))
                .onTime(true)
                .build());
    }

    private void savedNotification(Plant plant, TaskType type) {
        notificationRepository.save(NotificationLog.builder()
                .plant(plant)
                .taskType(type)
                .notificationType(NotificationType.DUE)
                .sentAt(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS))
                .build());
    }

    private void savedAnniversaryRow(Long plantId, int year) {
        // Идём через JDBC, чтобы не зависеть от @IdClass-репозитория и
        // случайно не получить кеш-эффект.
        // PG-драйвер не умеет автоматически биндить Instant, поэтому используем
        // OffsetDateTime (тип TIMESTAMPTZ маппится на него штатно).
        jdbc.update("INSERT INTO plant_anniversaries_sent(plant_id, anniversary_year, sent_at) "
                        + "VALUES (?, ?, ?)",
                plantId, year, OffsetDateTime.now(ZoneOffset.UTC));
    }

    private long countSchedules(Long plantId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM care_schedules WHERE plant_id = ?", Long.class, plantId);
        return n == null ? 0L : n;
    }

    private long countHistory(Long plantId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM care_history WHERE plant_id = ?", Long.class, plantId);
        return n == null ? 0L : n;
    }

    private long countNotifications(Long plantId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications_log WHERE plant_id = ?", Long.class, plantId);
        return n == null ? 0L : n;
    }

    private long countAnniversaries(Long plantId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM plant_anniversaries_sent WHERE plant_id = ?",
                Long.class, plantId);
        return n == null ? 0L : n;
    }
}
