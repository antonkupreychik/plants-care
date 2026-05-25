package com.plantcare.bot.service;

import com.plantcare.bot.domain.CareSchedule;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.Species;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.TaskType;
import com.plantcare.bot.repository.CareHistoryRepository;
import com.plantcare.bot.repository.CareScheduleRepository;
import com.plantcare.bot.repository.PlantRepository;
import com.plantcare.bot.repository.SpeciesRepository;
import com.plantcare.bot.repository.UserRepository;
import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Интеграционные тесты родословной растений (issue #139, ADR-012):
 * {@link PlantCardService#createCutting} + lineage-методы репозитория
 * ({@code countByParentId} / {@code findByParentId}).
 *
 * Фокус — модель данных родословной, а не Telegram-рендер: проверяем
 * копирование настроек как шаблона (ADR-005), денормализованный user_id
 * (ADR-010), счётчик потомков и сохранение ссылки при архивации родителя
 * (ADR-009 — каскада нет). Реальная Postgres через Testcontainers (не H2:
 * self-FK + индекс idx_plants_parent_id из V28 должны работать на боевом диалекте).
 */
@DisplayName("Интеграционные тесты родословной (createCutting / parent-child)")
class PlantCardServiceLineageTest extends IntegrationTestBase {

    @Autowired private PlantCardService plantCardService;
    @Autowired private PlantService plantService;
    @Autowired private PlantRepository plantRepository;
    @Autowired private CareScheduleRepository scheduleRepository;
    @Autowired private CareHistoryRepository careHistoryRepository;
    @Autowired private SpeciesRepository speciesRepository;
    @Autowired private UserRepository userRepository;

    private User testUser;
    private Species monstera;

    @BeforeEach
    void setUp() {
        testUser = userRepository.save(User.builder()
                .telegramChatId(810L)
                .username("lineage_test_user")
                .build());

        monstera = speciesRepository.findByName("Монстера").orElseThrow();
    }

    @AfterEach
    void cleanup() {
        careHistoryRepository.deleteAll();
        scheduleRepository.deleteAll();
        // Потомки ссылаются на родителей через parent_id (self-FK), поэтому
        // удаляем сначала всё, что parent_id != null, иначе FK не даст дропнуть родителей.
        plantRepository.findAll().stream()
                .filter(p -> p.getParent() != null)
                .forEach(plantRepository::delete);
        plantRepository.deleteAll();
        userRepository.deleteAll();
    }

    private Plant createParentPlant(String name, int wateringInterval) {
        LocalDateTime nextDue = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS)
                .plusDays(wateringInterval);
        return plantService.createPlantWithWateringSchedule(
                testUser, monstera.getId(), name, wateringInterval, nextDue);
    }

    // ==================== happy path: копирование шаблона + parent_id ====================

    @Test
    @DisplayName("createCutting: потомок наследует species, location и parent_id родителя")
    void should_copy_species_location_and_set_parent_when_cutting_created() {
        Plant parent = createParentPlant("Монстера-мать", 7);

        Plant cutting = plantCardService.createCutting(testUser, parent.getId(), "Черенок №1");

        Plant reloaded = plantRepository.findById(cutting.getId()).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("Черенок №1");
        assertThat(reloaded.getSpecies().getId()).isEqualTo(monstera.getId());
        assertThat(reloaded.getLocation().getId()).isEqualTo(parent.getLocation().getId());
        assertThat(reloaded.getParent()).isNotNull();
        assertThat(reloaded.getParent().getId()).isEqualTo(parent.getId());
    }

    @Test
    @DisplayName("createCutting: user_id потомка равен user_id родителя (денормализация, ADR-010)")
    void should_set_child_user_to_parent_user_when_cutting_created() {
        Plant parent = createParentPlant("Монстера-мать", 7);

        Plant cutting = plantCardService.createCutting(testUser, parent.getId(), "Черенок");

        Plant reloaded = plantRepository.findById(cutting.getId()).orElseThrow();
        assertThat(reloaded.getUser().getId()).isEqualTo(testUser.getId());
        assertThat(reloaded.getUser().getId()).isEqualTo(parent.getUser().getId());
    }

    @Test
    @DisplayName("createCutting: интервалы скопированы как НОВЫЕ CareSchedule, не ссылки на родительские")
    void should_copy_intervals_as_new_schedules_when_cutting_created() {
        Plant parent = createParentPlant("Монстера-мать", 9);
        CareSchedule parentSchedule = scheduleRepository
                .findByPlantIdAndTaskType(parent.getId(), TaskType.WATERING).orElseThrow();

        Plant cutting = plantCardService.createCutting(testUser, parent.getId(), "Черенок");

        List<CareSchedule> cuttingSchedules = scheduleRepository.findAllByPlantId(cutting.getId());
        assertThat(cuttingSchedules).hasSize(1);

        CareSchedule childSchedule = cuttingSchedules.getFirst();
        // Значение интервала совпадает с родителем...
        assertThat(childSchedule.getIntervalDays()).isEqualTo(parentSchedule.getIntervalDays());
        assertThat(childSchedule.getTaskType()).isEqualTo(TaskType.WATERING);
        assertThat(childSchedule.isActive()).isTrue();
        // ...но это ОТДЕЛЬНАЯ запись с собственным id, привязанная к потомку.
        assertThat(childSchedule.getId()).isNotEqualTo(parentSchedule.getId());
        assertThat(childSchedule.getPlant().getId()).isEqualTo(cutting.getId());
    }

    @Test
    @DisplayName("createCutting: nextDueAt потомка отсчитан от now (свой график), не скопирован у родителя")
    void should_reschedule_child_from_now_when_cutting_created() {
        Plant parent = createParentPlant("Монстера-мать", 10);

        Plant cutting = plantCardService.createCutting(testUser, parent.getId(), "Черенок");

        CareSchedule childSchedule = scheduleRepository
                .findByPlantIdAndTaskType(cutting.getId(), TaskType.WATERING).orElseThrow();
        // now + интервал (с сезонной коррекцией) — не в прошлом и не нулевое.
        assertThat(childSchedule.getNextDueAt())
                .isAfter(LocalDateTime.now())
                .isBefore(LocalDateTime.now().plusDays(366));
    }

    // ==================== edge: копирование нескольких активных расписаний ====================

    @Test
    @DisplayName("createCutting: у родителя 3 активных расписания → у потомка ровно 3 новых с теми же интервалами")
    void should_copy_all_active_schedules_when_parent_has_several() {
        Plant parent = createParentPlant("Монстера-мать", 7);
        plantService.addCareSchedule(parent, TaskType.MISTING, 3,
                LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).plusDays(3));
        plantService.addCareSchedule(parent, TaskType.FERTILIZING, 14,
                LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).plusDays(14));

        Plant cutting = plantCardService.createCutting(testUser, parent.getId(), "Черенок");

        List<CareSchedule> cuttingSchedules = scheduleRepository.findAllByPlantId(cutting.getId());
        assertThat(cuttingSchedules).hasSize(3);
        assertThat(cuttingSchedules)
                .extracting(CareSchedule::getTaskType)
                .containsExactlyInAnyOrder(
                        TaskType.WATERING, TaskType.MISTING, TaskType.FERTILIZING);
        assertThat(cuttingSchedules)
                .extracting(CareSchedule::getIntervalDays)
                .containsExactlyInAnyOrder(7, 3, 14);
        assertThat(cuttingSchedules).allMatch(CareSchedule::isActive);
    }

    @Test
    @DisplayName("createCutting: выключенное расписание родителя НЕ переносится потомку")
    void should_skip_inactive_parent_schedule_when_cutting_created() {
        Plant parent = createParentPlant("Монстера-мать", 7);
        plantService.addCareSchedule(parent, TaskType.MISTING, 3,
                LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).plusDays(3));
        // Выключаем misting у родителя — он не должен попасть в шаблон потомка.
        plantService.deactivateCareSchedule(parent, TaskType.MISTING);

        Plant cutting = plantCardService.createCutting(testUser, parent.getId(), "Черенок");

        List<CareSchedule> cuttingSchedules = scheduleRepository.findAllByPlantId(cutting.getId());
        assertThat(cuttingSchedules)
                .extracting(CareSchedule::getTaskType)
                .containsExactly(TaskType.WATERING);
    }

    // ==================== счётчик потомков ====================

    @Test
    @DisplayName("countByParentId: растение без потомков → 0")
    void should_return_zero_when_plant_has_no_children() {
        Plant lonely = createParentPlant("Одиночка", 7);

        assertThat(plantRepository.countByParentId(lonely.getId())).isZero();
    }

    @Test
    @DisplayName("countByParentId: N черенков → N; findByParentId возвращает именно их")
    void should_count_children_when_several_cuttings_created() {
        Plant parent = createParentPlant("Плодовитая монстера", 7);

        Plant c1 = plantCardService.createCutting(testUser, parent.getId(), "Черенок 1");
        Plant c2 = plantCardService.createCutting(testUser, parent.getId(), "Черенок 2");
        Plant c3 = plantCardService.createCutting(testUser, parent.getId(), "Черенок 3");

        assertThat(plantRepository.countByParentId(parent.getId())).isEqualTo(3);
        assertThat(plantRepository.findByParentId(parent.getId()))
                .extracting(Plant::getId)
                .containsExactlyInAnyOrder(c1.getId(), c2.getId(), c3.getId());
    }

    @Test
    @DisplayName("countByParentId: считает только прямых потомков, не внуков")
    void should_count_only_direct_children_not_grandchildren() {
        Plant grandparent = createParentPlant("Прародитель", 7);
        Plant parent = plantCardService.createCutting(testUser, grandparent.getId(), "Родитель");
        plantCardService.createCutting(testUser, parent.getId(), "Внук");

        // У прародителя ровно один прямой потомок (parent), внук висит на parent.
        assertThat(plantRepository.countByParentId(grandparent.getId())).isEqualTo(1);
        assertThat(plantRepository.countByParentId(parent.getId())).isEqualTo(1);
    }

    // ==================== edge: родитель в архиве (ADR-009) ====================

    @Test
    @DisplayName("Архивация родителя: ссылка потомка СОХРАНЯЕТСЯ, каскадной архивации нет")
    void should_keep_parent_link_when_parent_archived() {
        Plant parent = createParentPlant("Монстера-мать", 7);
        Plant cutting = plantCardService.createCutting(testUser, parent.getId(), "Черенок");

        plantService.archivePlant(testUser.getId(), parent.getId());

        // Родитель архивирован...
        Plant archivedParent = plantRepository.findById(parent.getId()).orElseThrow();
        assertThat(archivedParent.getArchivedAt()).isNotNull();

        // ...а потомок жив, не архивирован и всё ещё ссылается на родителя.
        // getParent().getId() читаем через proxy id (без инициализации сессии),
        // сам объект родителя проверяем выше через прямой findById.
        Plant reloadedCutting = plantRepository.findById(cutting.getId()).orElseThrow();
        assertThat(reloadedCutting.getArchivedAt()).isNull();
        assertThat(reloadedCutting.getParent()).isNotNull();
        assertThat(reloadedCutting.getParent().getId()).isEqualTo(parent.getId());

        // Связь findByParentId/parent_id переживает архивацию родителя.
        assertThat(plantRepository.countByParentId(parent.getId())).isEqualTo(1);
        assertThat(plantRepository.findByParentId(parent.getId()))
                .extracting(Plant::getId)
                .containsExactly(cutting.getId());
    }

    // ==================== failure path ====================

    @Test
    @DisplayName("createCutting: чужой/несуществующий родитель → IllegalArgumentException")
    void should_reject_when_parent_not_owned_by_user() {
        Plant parent = createParentPlant("Монстера-мать", 7);
        User intruder = userRepository.save(User.builder()
                .telegramChatId(999L)
                .username("intruder")
                .build());

        assertThatThrownBy(() ->
                plantCardService.createCutting(intruder, parent.getId(), "Краденый черенок"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не найдено");
    }

    @Test
    @DisplayName("createCutting: родитель в архиве → IllegalArgumentException (черенок от мёртвого не берём)")
    void should_reject_when_parent_is_archived() {
        Plant parent = createParentPlant("Монстера-мать", 7);
        plantService.archivePlant(testUser.getId(), parent.getId());

        assertThatThrownBy(() ->
                plantCardService.createCutting(testUser, parent.getId(), "Черенок"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не найдено");
    }

    @Test
    @DisplayName("createCutting: пустое имя → IllegalArgumentException, потомок не создаётся")
    void should_reject_when_cutting_name_blank() {
        Plant parent = createParentPlant("Монстера-мать", 7);

        assertThatThrownBy(() ->
                plantCardService.createCutting(testUser, parent.getId(), "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("пуст");

        assertThat(plantRepository.countByParentId(parent.getId())).isZero();
    }
}
