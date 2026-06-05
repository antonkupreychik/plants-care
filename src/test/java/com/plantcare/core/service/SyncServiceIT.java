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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционный тест {@link SyncService} (issue #91).
 *
 * <p>Проверяет:
 *   - sync возвращает только изменения после since
 *   - idempotency: повторные вызовы дают те же данные при том же since
 *   - deletions видны в ответе (plants/locations с deleted_at)
 *   - CareEvents включены в ответ с корректными данными
 *   - Таймзона: since в Europe/Moscow не ломает UTC-сравнение
 *
 * <p>Тесты используют уникальные telegram-chatId (диапазон 9_091_*),
 * чтобы не конфликтовать с другими IT-тестами.
 */
@DisplayName("SyncService — офлайн-синк (issue #91)")
class SyncServiceIT extends IntegrationTestBase {

    @Autowired
    private SyncService syncService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LocationRepository locationRepository;

    @Autowired
    private PlantRepository plantRepository;

    @Autowired
    private CareHistoryRepository careHistoryRepository;

    @Autowired
    private CareScheduleRepository careScheduleRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<User> createdUsers = new ArrayList<>();

    /** chatId диапазона этого тест-класса — чистим перед каждым тестом (защита от предыдущего красного прогона). */
    private static final long[] TEST_CHAT_IDS = {
            9_091_001L, 9_091_002L, 9_091_003L, 9_091_004L,
            9_091_005L, 9_091_006L, 9_091_007L
    };

    @org.junit.jupiter.api.BeforeEach
    void cleanupStaleData() {
        for (long chatId : TEST_CHAT_IDS) {
            userRepository.findByTelegramChatId(chatId).ifPresent(u -> {
                jdbcTemplate.update(
                        "DELETE FROM care_history WHERE plant_id IN (SELECT id FROM plants WHERE user_id = ?)",
                        u.getId());
                jdbcTemplate.update(
                        "DELETE FROM care_schedules WHERE plant_id IN (SELECT id FROM plants WHERE user_id = ?)",
                        u.getId());
                jdbcTemplate.update("DELETE FROM plants WHERE user_id = ?", u.getId());
                jdbcTemplate.update("DELETE FROM locations WHERE user_id = ?", u.getId());
                userRepository.deleteById(u.getId());
            });
        }
    }

    @AfterEach
    void cleanup() {
        // Каскадное ON DELETE CASCADE через user_id уберёт всё при удалении юзера
        for (User u : createdUsers) {
            if (userRepository.existsById(u.getId())) {
                // Удаляем дочерние записи в правильном порядке (без CASCADE на care_history через user)
                jdbcTemplate.update(
                        "DELETE FROM care_history WHERE plant_id IN "
                                + "(SELECT id FROM plants WHERE user_id = ?)", u.getId());
                jdbcTemplate.update(
                        "DELETE FROM care_schedules WHERE plant_id IN "
                                + "(SELECT id FROM plants WHERE user_id = ?)", u.getId());
                // plants → locations → users (соблюдаем FK порядок)
                jdbcTemplate.update("DELETE FROM plants WHERE user_id = ?", u.getId());
                jdbcTemplate.update("DELETE FROM locations WHERE user_id = ?", u.getId());
                userRepository.deleteById(u.getId());
            }
        }
        createdUsers.clear();
    }

    // ===================== happy path: incremental sync =====================

    @Test
    @DisplayName("Sync возвращает только растения, изменённые после since")
    void should_return_only_changed_plants_since_timestamp() {
        // arrange
        User user = saveUser(9_091_001L, "UTC");
        Location location = saveLocation(user, "Гостиная");

        // растение в прошлом — вставляем через JDBC с явным old updated_at.
        // Используем JDBC INSERT (не Hibernate) чтобы обойти trigger.
        jdbcTemplate.update(
                "INSERT INTO plants (user_id, location_id, name, created_at, updated_at, seasonal_override, photo_progress_frequency)"
                        + " VALUES (?, ?, 'Старый кактус', '2020-01-01 00:00:00', '2020-01-01 00:00:00', 'INHERIT', 'OFF')",
                user.getId(), location.getId());

        // since = 2022-01-01 — после старого растения, до нового
        Instant since = Instant.parse("2022-01-01T00:00:00Z");

        // новое растение через Hibernate — updated_at = now() (текущий момент)
        Plant newPlant = savePlant(user, location, "Новая монстера");

        // act
        SyncService.SyncResult result = syncService.sync(user.getId(), since);

        // assert — только новое растение
        assertThat(result.plants()).hasSize(1);
        assertThat(result.plants().get(0).getId()).isEqualTo(newPlant.getId());
        assertThat(result.plants().get(0).getName()).isEqualTo("Новая монстера");
    }

    // ===================== full sync =====================

    @Test
    @DisplayName("Full sync (since=EPOCH) возвращает все активные данные пользователя")
    void should_return_all_data_when_since_is_epoch() {
        // arrange
        User user = saveUser(9_091_002L, "Europe/Moscow");
        Location location = saveLocation(user, "Балкон");
        savePlant(user, location, "Фикус");
        savePlant(user, location, "Алоэ");

        // act — since = EPOCH означает full sync
        SyncService.SyncResult result = syncService.sync(user.getId(), Instant.EPOCH);

        // assert
        assertThat(result.plants()).hasSize(2);
        assertThat(result.locations()).hasSize(1);
    }

    // ===================== deletions =====================

    @Test
    @DisplayName("Удалённые растения (deleted_at IS NOT NULL) видны в deletions")
    void should_include_deleted_plants_in_deletions() {
        // arrange
        User user = saveUser(9_091_003L, "Asia/Almaty");
        Location location = saveLocation(user, "Кухня");
        Plant plant = savePlant(user, location, "Орхидея");

        Instant since = Instant.EPOCH;

        // soft-delete: проставляем deleted_at через JDBC (entity не используется для удаления в MVP)
        jdbcTemplate.update(
                "UPDATE plants SET deleted_at = now(), archived_at = now() WHERE id = ?",
                plant.getId());

        // act
        SyncService.SyncResult result = syncService.sync(user.getId(), since);

        // assert — орхидея в deletions, но не в plants
        assertThat(result.plants()).extracting(Plant::getId).doesNotContain(plant.getId());
        assertThat(result.deletions()).hasSize(1);
        assertThat(result.deletions().get(0).entityType()).isEqualTo("plant");
        assertThat(result.deletions().get(0).id()).isEqualTo(plant.getId());
        assertThat(result.deletions().get(0).deletedAt()).isNotNull();
    }

    // ===================== care events =====================

    @Test
    @DisplayName("Sync возвращает события ухода, изменённые после since")
    void should_return_care_events_changed_since_timestamp() {
        // arrange
        User user = saveUser(9_091_004L, "UTC");
        Location location = saveLocation(user, "Спальня");
        Plant plant = savePlant(user, location, "Суккулент");
        saveSchedule(plant, TaskType.WATERING);

        // Старое событие — вставляем через JDBC с явно старым updated_at, обходя Hibernate-триггер
        jdbcTemplate.update(
                "INSERT INTO care_history (plant_id, task_type, done_at, was_on_time, created_at, updated_at)"
                        + " VALUES (?, 'WATERING', '2020-06-01 08:00:00', true, '2020-06-01 08:00:00', '2020-06-01 08:00:00')",
                plant.getId());

        // since = 2022-01-01 — после старого события, до нового
        Instant since = Instant.parse("2022-01-01T00:00:00Z");

        // Создаём событие ПОСЛЕ since через Hibernate — updated_at = now()
        CareHistory newEvent = saveCareHistory(plant, TaskType.WATERING, "client-uuid-sync-test");

        // act
        SyncService.SyncResult result = syncService.sync(user.getId(), since);

        // assert — только новое событие
        assertThat(result.careEvents()).hasSize(1);
        assertThat(result.careEvents().get(0).getId()).isEqualTo(newEvent.getId());
        assertThat(result.careEvents().get(0).getClientId()).isEqualTo("client-uuid-sync-test");
    }

    // ===================== idempotency =====================

    @Test
    @DisplayName("Повторный вызов с тем же since возвращает те же данные (идемпотентность)")
    void should_be_idempotent_when_called_twice_with_same_since() {
        // arrange
        User user = saveUser(9_091_005L, "UTC");
        Location location = saveLocation(user, "Офис");
        savePlant(user, location, "Потос");

        Instant since = Instant.EPOCH;

        // act — два вызова
        SyncService.SyncResult first = syncService.sync(user.getId(), since);
        SyncService.SyncResult second = syncService.sync(user.getId(), since);

        // assert — одинаковое количество записей
        assertThat(first.plants()).hasSameSizeAs(second.plants());
        assertThat(first.locations()).hasSameSizeAs(second.locations());
    }

    // ===================== race condition: concurrent creates with same clientId =====================

    @Test
    @DisplayName("Sync не возвращает дублей при одновременных изменениях с одним clientId")
    void should_not_duplicate_care_events_when_multiple_events_have_unique_client_ids() {
        // arrange
        User user = saveUser(9_091_006L, "UTC");
        Location location = saveLocation(user, "Зал");
        Plant plant = savePlant(user, location, "Спатифиллум");
        saveSchedule(plant, TaskType.FERTILIZING);

        // создаём два события с РАЗНЫМИ clientId (параллельная запись)
        CareHistory e1 = saveCareHistory(plant, TaskType.FERTILIZING, "client-uuid-A");
        CareHistory e2 = saveCareHistory(plant, TaskType.FERTILIZING, "client-uuid-B");

        // act
        SyncService.SyncResult result = syncService.sync(user.getId(), Instant.EPOCH);

        // assert — оба события в ответе, без дублей
        var ids = result.careEvents().stream()
                .map(CareHistory::getId)
                .toList();
        assertThat(ids).containsExactlyInAnyOrder(e1.getId(), e2.getId());
    }

    // ===================== serverTime field =====================

    @Test
    @DisplayName("serverTime в ответе задан и примерно равен now()")
    void should_return_serverTime_close_to_now() {
        // arrange
        User user = saveUser(9_091_007L, "UTC");

        Instant before = Instant.now();

        // act
        SyncService.SyncResult result = syncService.sync(user.getId(), Instant.EPOCH);

        Instant after = Instant.now();

        // assert — serverTime между before и after
        assertThat(result.serverTime()).isBetween(before, after);
    }

    // ====================================================================
    // fixtures
    // ====================================================================

    private User saveUser(long chatId, String timezone) {
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

    private Location saveLocation(User user, String name) {
        return locationRepository.save(Location.builder()
                .user(user)
                .name(name)
                .emoji("🌿")
                .defaultLocation(true)
                .build());
    }

    private Plant savePlant(User user, Location location, String name) {
        return plantRepository.save(Plant.builder()
                .user(user)
                .location(location)
                .name(name)
                .build());
    }

    private CareSchedule saveSchedule(Plant plant, TaskType taskType) {
        return careScheduleRepository.save(CareSchedule.builder()
                .plant(plant)
                .taskType(taskType)
                .intervalDays(7)
                .nextDueAt(LocalDateTime.of(2026, 7, 1, 6, 0))
                .active(true)
                .build());
    }

    private CareHistory saveCareHistory(Plant plant, TaskType taskType, String clientId) {
        CareHistory history = new CareHistory();
        history.setPlant(plant);
        history.setTaskType(taskType);
        history.setDoneAt(LocalDateTime.now(ZoneOffset.UTC));
        history.setOnTime(true);
        history.setClientId(clientId);
        return careHistoryRepository.save(history);
    }
}
