package com.plantcare.core.service;

import com.plantcare.bot.support.IntegrationTestBase;
import com.plantcare.core.domain.CareSchedule;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.Species;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.repository.CareHistoryRepository;
import com.plantcare.core.repository.CareScheduleRepository;
import com.plantcare.core.repository.PlantRepository;
import com.plantcare.core.repository.SpeciesRepository;
import com.plantcare.core.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Интеграционные тесты per-plant расписаний ухода для REST API (issue #185, G14/G19).
 *
 * <p>Покрывает {@link PlantService#getSchedules}, {@link PlantService#updateSchedule}
 * и {@link PlantService#createPlantWithDefaultSchedules} на реальном Postgres
 * (Testcontainers), без моков репозиториев. Внешних API здесь нет.
 *
 * <p>Замечание про время: {@code PlantService} использует {@code LocalDateTime.now()}
 * (Clock-бина нет), поэтому {@code nextDueAt} проверяется с допуском (день/диапазон),
 * а не пин к конкретному инстанту.
 *
 * <p>telegramChatId уникальны (8500+) во избежание коллизий с другими тест-классами
 * при включённом reuse контейнера.
 */
@DisplayName("Интеграционные тесты PlantService — расписания ухода (issue #185)")
class PlantScheduleServiceTest extends IntegrationTestBase {

    @Autowired private PlantService plantService;
    @Autowired private PlantRepository plantRepository;
    @Autowired private CareScheduleRepository scheduleRepository;
    @Autowired private CareHistoryRepository careHistoryRepository;
    @Autowired private SpeciesRepository speciesRepository;
    @Autowired private UserRepository userRepository;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = userRepository.save(User.builder()
                .telegramChatId(8500L)
                .username("schedule_test_user")
                .build());
    }

    @AfterEach
    void cleanup() {
        careHistoryRepository.deleteAll();
        scheduleRepository.deleteAll();
        plantRepository.deleteAll();
        // удаляем только наши кастомные виды, seed-данные не трогаем
        speciesRepository.findByName("ТестВид185")
                .ifPresent(s -> speciesRepository.delete(s));
        userRepository.deleteAll();
    }

    // ---------------------------------------------------------------- helpers

    private User newUser(long chatId, String name) {
        return userRepository.save(User.builder()
                .telegramChatId(chatId)
                .username(name)
                .build());
    }

    /** Вид с заведомо нестандартными интервалами — чтобы доказать, что дефолты текут из species. */
    private Species customSpecies() {
        return speciesRepository.save(Species.builder()
                .name("ТестВид185")
                .wateringDays(9)
                .mistingDays(5)
                .fertilizingDays(21)
                .soilCheckDays(4)
                .build());
    }

    // ============================= getSchedules =============================

    @Test
    @DisplayName("getSchedules: ровно 4 расписания в порядке WATERING, MISTING, FERTILIZING, SOIL_CHECK")
    void should_return_four_schedules_in_fixed_order_when_no_rows() {
        Plant plant = plantService.createPlant(testUser, "Без расписаний", null, null, null);

        List<PlantService.ScheduleView> views = plantService.getSchedules(testUser.getId(), plant.getId());

        assertThat(views).extracting(PlantService.ScheduleView::type)
                .containsExactly(TaskType.WATERING, TaskType.MISTING,
                        TaskType.FERTILIZING, TaskType.SOIL_CHECK);
    }

    @Test
    @DisplayName("getSchedules: растение без строк → все disabled, интервалы из species-дефолтов")
    void should_use_species_defaults_when_no_rows() {
        Species species = customSpecies();
        Plant plant = plantService.createPlant(testUser, "Из вида", null, null, species.getId());

        List<PlantService.ScheduleView> views = plantService.getSchedules(testUser.getId(), plant.getId());

        assertThat(views).allMatch(v -> !v.enabled());
        assertThat(views).allMatch(v -> v.nextDueAt() == null);
        assertThat(views).allMatch(v -> v.amountMl() == null);
        assertThat(views).extracting(PlantService.ScheduleView::every)
                .containsExactly(9, 5, 21, 4); // wateringDays, mistingDays, fertilizingDays, soilCheckDays
    }

    @Test
    @DisplayName("getSchedules: species == null → hardcode-дефолты W7/M3/F14/S3")
    void should_use_hardcoded_defaults_when_species_null() {
        Plant plant = plantService.createPlant(testUser, "Без вида", null, null, null);

        List<PlantService.ScheduleView> views = plantService.getSchedules(testUser.getId(), plant.getId());

        assertThat(views).extracting(PlantService.ScheduleView::every)
                .containsExactly(7, 3, 14, 3);
    }

    @Test
    @DisplayName("getSchedules: активная строка → отражает interval/amountMl/active и nextDueAt не null")
    void should_reflect_active_row_with_next_due_at() {
        Plant plant = plantService.createPlant(testUser, "С поливом", null, null, null);
        plantService.updateSchedule(testUser.getId(), plant.getId(),
                TaskType.WATERING, 4, 250, true);

        PlantService.ScheduleView watering = plantService
                .getSchedules(testUser.getId(), plant.getId()).getFirst();

        assertThat(watering.enabled()).isTrue();
        assertThat(watering.every()).isEqualTo(4);
        assertThat(watering.amountMl()).isEqualTo(250);
        assertThat(watering.nextDueAt()).isNotNull();
        assertThat(watering.nextDueAt()).isAfter(LocalDateTime.now().minusMinutes(1));
    }

    @Test
    @DisplayName("getSchedules: неактивная строка → enabled=false и nextDueAt=null")
    void should_hide_next_due_at_for_inactive_row() {
        Plant plant = plantService.createPlant(testUser, "Выключенное", null, null, null);
        // включаем, затем выключаем — строка существует, но active=false
        plantService.updateSchedule(testUser.getId(), plant.getId(), TaskType.MISTING, 3, null, true);
        plantService.updateSchedule(testUser.getId(), plant.getId(), TaskType.MISTING, 3, null, false);

        PlantService.ScheduleView misting = plantService
                .getSchedules(testUser.getId(), plant.getId()).get(1);

        assertThat(misting.type()).isEqualTo(TaskType.MISTING);
        assertThat(misting.enabled()).isFalse();
        assertThat(misting.nextDueAt()).isNull();
    }

    // ============================ updateSchedule ============================

    @Test
    @DisplayName("updateSchedule: включение WATERING создаёт активную строку с amountMl и nextDueAt ≈ now+interval")
    void should_create_active_watering_row_when_enabled() {
        Plant plant = plantService.createPlant(testUser, "Полив", null, null, null);

        PlantService.ScheduleView view = plantService.updateSchedule(
                testUser.getId(), plant.getId(), TaskType.WATERING, 5, 300, true);

        assertThat(view.enabled()).isTrue();
        assertThat(view.amountMl()).isEqualTo(300);
        assertThat(view.nextDueAt())
                .isAfter(LocalDateTime.now().plusDays(4))
                .isBefore(LocalDateTime.now().plusDays(6));

        CareSchedule row = scheduleRepository
                .findByPlantIdAndTaskType(plant.getId(), TaskType.WATERING).orElseThrow();
        assertThat(row.isActive()).isTrue();
        assertThat(row.getIntervalDays()).isEqualTo(5);
        assertThat(row.getAmountMl()).isEqualTo(300);
    }

    @Test
    @DisplayName("updateSchedule: повторный вызов меняет interval/amount и пересчитывает nextDueAt")
    void should_update_existing_row_and_recompute_next_due_at() {
        Plant plant = plantService.createPlant(testUser, "Полив", null, null, null);
        plantService.updateSchedule(testUser.getId(), plant.getId(), TaskType.WATERING, 5, 300, true);

        PlantService.ScheduleView updated = plantService.updateSchedule(
                testUser.getId(), plant.getId(), TaskType.WATERING, 10, 500, true);

        assertThat(updated.every()).isEqualTo(10);
        assertThat(updated.amountMl()).isEqualTo(500);
        assertThat(updated.nextDueAt())
                .isAfter(LocalDateTime.now().plusDays(9))
                .isBefore(LocalDateTime.now().plusDays(11));

        // одна строка, не дубль
        assertThat(scheduleRepository.findAllByPlantId(plant.getId()).stream()
                .filter(s -> s.getTaskType() == TaskType.WATERING).toList()).hasSize(1);
    }

    @Test
    @DisplayName("updateSchedule: enabled=false → строка inactive, getSchedules показывает nextDueAt=null")
    void should_disable_row_and_null_next_due_at_in_view() {
        Plant plant = plantService.createPlant(testUser, "Удобрение", null, null, null);
        plantService.updateSchedule(testUser.getId(), plant.getId(), TaskType.FERTILIZING, 14, null, true);

        PlantService.ScheduleView disabled = plantService.updateSchedule(
                testUser.getId(), plant.getId(), TaskType.FERTILIZING, 14, null, false);

        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.nextDueAt()).isNull();

        CareSchedule row = scheduleRepository
                .findByPlantIdAndTaskType(plant.getId(), TaskType.FERTILIZING).orElseThrow();
        assertThat(row.isActive()).isFalse();

        // и в проекции наружу тоже null
        PlantService.ScheduleView fromList = plantService
                .getSchedules(testUser.getId(), plant.getId()).get(2);
        assertThat(fromList.nextDueAt()).isNull();
    }

    @Test
    @DisplayName("updateSchedule: amountMl игнорируется (null) для MISTING/FERTILIZING/SOIL_CHECK")
    void should_ignore_amount_ml_for_non_watering_types() {
        Plant plant = plantService.createPlant(testUser, "Не полив", null, null, null);

        for (TaskType type : List.of(TaskType.MISTING, TaskType.FERTILIZING, TaskType.SOIL_CHECK)) {
            PlantService.ScheduleView view = plantService.updateSchedule(
                    testUser.getId(), plant.getId(), type, 3, 999, true);

            assertThat(view.amountMl())
                    .as("amountMl должен игнорироваться для типа %s", type)
                    .isNull();
            assertThat(scheduleRepository.findByPlantIdAndTaskType(plant.getId(), type)
                    .orElseThrow().getAmountMl()).isNull();
        }
    }

    @Test
    @DisplayName("updateSchedule: amountMl <= 0 для WATERING → IllegalArgumentException")
    void should_reject_non_positive_amount_ml_for_watering() {
        Plant plant = plantService.createPlant(testUser, "Полив", null, null, null);

        assertThatThrownBy(() -> plantService.updateSchedule(
                testUser.getId(), plant.getId(), TaskType.WATERING, 5, 0, true))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> plantService.updateSchedule(
                testUser.getId(), plant.getId(), TaskType.WATERING, 5, -50, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("updateSchedule: every вне [1,365] (0 и 366) → IllegalArgumentException")
    void should_reject_interval_out_of_range() {
        Plant plant = plantService.createPlant(testUser, "Полив", null, null, null);

        assertThatThrownBy(() -> plantService.updateSchedule(
                testUser.getId(), plant.getId(), TaskType.WATERING, 0, null, true))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> plantService.updateSchedule(
                testUser.getId(), plant.getId(), TaskType.WATERING, 366, null, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ============================ cross-user scope ============================

    @Test
    @DisplayName("getSchedules: чужое растение → AccessDeniedException")
    void should_deny_get_schedules_for_foreign_plant() {
        User owner = newUser(8501L, "owner");
        Plant plant = plantService.createPlant(owner, "Чужое", null, null, null);

        User intruder = newUser(8502L, "intruder");

        assertThatThrownBy(() -> plantService.getSchedules(intruder.getId(), plant.getId()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("updateSchedule: чужое растение → AccessDeniedException, строка не создаётся")
    void should_deny_update_schedule_for_foreign_plant() {
        User owner = newUser(8503L, "owner2");
        Plant plant = plantService.createPlant(owner, "Чужое2", null, null, null);

        User intruder = newUser(8504L, "intruder2");

        assertThatThrownBy(() -> plantService.updateSchedule(
                intruder.getId(), plant.getId(), TaskType.WATERING, 5, 100, true))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(scheduleRepository.findAllByPlantId(plant.getId())).isEmpty();
    }

    // ================================ G14 ====================================

    @Test
    @DisplayName("createPlantWithDefaultSchedules: speciesId → 4 строки, активен только WATERING с nextDueAt")
    void should_seed_four_schedules_only_watering_active_with_species() {
        Species species = customSpecies();

        Plant plant = plantService.createPlantWithDefaultSchedules(
                testUser, "G14 с видом", null, null, species.getId());

        List<CareSchedule> rows = scheduleRepository.findAllByPlantId(plant.getId());
        assertThat(rows).hasSize(4);

        CareSchedule watering = byType(rows, TaskType.WATERING);
        assertThat(watering.isActive()).isTrue();
        assertThat(watering.getIntervalDays()).isEqualTo(9);   // species watering default
        assertThat(watering.getNextDueAt()).isAfter(LocalDateTime.now().minusMinutes(1));

        assertThat(byType(rows, TaskType.MISTING).isActive()).isFalse();
        assertThat(byType(rows, TaskType.FERTILIZING).isActive()).isFalse();
        assertThat(byType(rows, TaskType.SOIL_CHECK).isActive()).isFalse();

        // интервалы у неактивных тоже из species-дефолтов
        assertThat(byType(rows, TaskType.MISTING).getIntervalDays()).isEqualTo(5);
        assertThat(byType(rows, TaskType.FERTILIZING).getIntervalDays()).isEqualTo(21);
        assertThat(byType(rows, TaskType.SOIL_CHECK).getIntervalDays()).isEqualTo(4);
    }

    @Test
    @DisplayName("createPlantWithDefaultSchedules: speciesId=null → hardcode-дефолты, активен только WATERING")
    void should_seed_four_schedules_with_hardcoded_defaults_when_species_null() {
        Plant plant = plantService.createPlantWithDefaultSchedules(
                testUser, "G14 без вида", null, null, null);

        List<CareSchedule> rows = scheduleRepository.findAllByPlantId(plant.getId());
        assertThat(rows).hasSize(4);

        assertThat(byType(rows, TaskType.WATERING).isActive()).isTrue();
        assertThat(byType(rows, TaskType.WATERING).getIntervalDays()).isEqualTo(7);
        assertThat(byType(rows, TaskType.MISTING).isActive()).isFalse();
        assertThat(byType(rows, TaskType.MISTING).getIntervalDays()).isEqualTo(3);
        assertThat(byType(rows, TaskType.FERTILIZING).getIntervalDays()).isEqualTo(14);
        assertThat(byType(rows, TaskType.SOIL_CHECK).getIntervalDays()).isEqualTo(3);

        long active = rows.stream().filter(CareSchedule::isActive).count();
        assertThat(active).isEqualTo(1);
    }

    // ============================ non-UTC timezone ============================

    @Test
    @DisplayName("updateSchedule: nextDueAt корректен (now+interval) для пользователя в Asia/Almaty")
    void should_compute_next_due_at_correctly_for_non_utc_timezone() {
        User almatyUser = userRepository.save(User.builder()
                .telegramChatId(8505L)
                .username("almaty_user")
                .timezone("Asia/Almaty")
                .build());

        Plant plant = plantService.createPlant(almatyUser, "Алматинское", null, null, null);

        LocalDateTime before = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);

        PlantService.ScheduleView view = plantService.updateSchedule(
                almatyUser.getId(), plant.getId(), TaskType.WATERING, 7, 200, true);

        LocalDateTime after = LocalDateTime.now();

        // nextDueAt хранится в UTC wall-clock = now + 7 дней (сезонность у юзера выключена),
        // и не зависит от его таймзоны.
        assertThat(view.nextDueAt())
                .isAfterOrEqualTo(before.plusDays(7))
                .isBeforeOrEqualTo(after.plusDays(7).plusSeconds(1));
    }

    // ---------------------------------------------------------------- util

    private static CareSchedule byType(List<CareSchedule> rows, TaskType type) {
        return rows.stream().filter(r -> r.getTaskType() == type).findFirst().orElseThrow();
    }
}
