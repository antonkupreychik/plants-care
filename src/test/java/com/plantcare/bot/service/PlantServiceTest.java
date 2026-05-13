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

@DisplayName("Интеграционные тесты PlantService — создание растения")
class PlantServiceTest extends IntegrationTestBase {

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
                .telegramChatId(800L)
                .username("plant_test_user")
                .build());

        // Монстера уже есть из V2__seed_species.sql
        monstera = speciesRepository.findByName("Монстера").orElseThrow();
    }

    @AfterEach
    void cleanup() {
        careHistoryRepository.deleteAll();
        scheduleRepository.deleteAll();
        plantRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ==================== createPlantWithWateringSchedule ====================

    @Test
    @DisplayName("Создание растения с шаблоном: Plant + CareSchedule(WATERING) сохраняются")
    void createWithTemplate_savesPlantAndSchedule() {
        LocalDateTime nextDue = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).plusDays(7);

        Plant plant = plantService.createPlantWithWateringSchedule(
                testUser, monstera.getId(), "Монстера в гостиной", 7, nextDue);

        // Plant
        assertThat(plant.getId()).isNotNull();
        assertThat(plant.getName()).isEqualTo("Монстера в гостиной");
        assertThat(plant.getUser().getId()).isEqualTo(testUser.getId());
        assertThat(plant.getSpecies().getId()).isEqualTo(monstera.getId());
        assertThat(plant.getRoom()).isNull();

        // CareSchedule
        CareSchedule schedule = scheduleRepository
                .findByPlantIdAndTaskType(plant.getId(), TaskType.WATERING)
                .orElseThrow();

        assertThat(schedule.getIntervalDays()).isEqualTo(7);
        assertThat(schedule.getNextDueAt()).isEqualTo(nextDue);
        assertThat(schedule.isActive()).isTrue();
    }

    @Test
    @DisplayName("Создание растения без шаблона: species_id = NULL, интервал пользовательский")
    void createWithoutTemplate_speciesIsNull() {
        LocalDateTime nextDue = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).plusDays(10);

        Plant plant = plantService.createPlantWithWateringSchedule(
                testUser, null, "Моё собственное растение", 10, nextDue);

        assertThat(plant.getSpecies()).isNull();
        assertThat(plant.getName()).isEqualTo("Моё собственное растение");

        CareSchedule schedule = scheduleRepository
                .findByPlantIdAndTaskType(plant.getId(), TaskType.WATERING)
                .orElseThrow();

        assertThat(schedule.getIntervalDays()).isEqualTo(10);
    }

    @Test
    @DisplayName("Создаётся ровно одна запись CareSchedule типа WATERING")
    void createPlant_onlyOneWateringSchedule() {
        Plant plant = plantService.createPlantWithWateringSchedule(
                testUser, monstera.getId(), "Test", 7, LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).plusDays(7));

        List<CareSchedule> schedules = scheduleRepository.findAllByPlantId(plant.getId());

        assertThat(schedules).hasSize(1);
        assertThat(schedules.getFirst().getTaskType()).isEqualTo(TaskType.WATERING);
    }

    @Test
    @DisplayName("Несколько растений у одного пользователя — каждое со своим расписанием")
    void multiplePlantsForSameUser() {
        plantService.createPlantWithWateringSchedule(
                testUser, monstera.getId(), "Монстера 1", 7, LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).plusDays(7));
        plantService.createPlantWithWateringSchedule(
                testUser, monstera.getId(), "Монстера 2", 5, LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).plusDays(5));

        List<Plant> plants = plantRepository
                .findAllByUserIdAndArchivedAtIsNullOrderByNameAsc(testUser.getId());

        assertThat(plants).hasSize(2);
        assertThat(plants).extracting(Plant::getName)
                .containsExactly("Монстера 1", "Монстера 2");
    }

    @Test
    @DisplayName("Граничные интервалы: 1 и 365 дней — сохраняются без ошибок")
    void boundaryIntervals_saveSuccessfully() {
        Plant daily = plantService.createPlantWithWateringSchedule(
                testUser, null, "Ежедневный полив", 1, LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).plusDays(1));
        Plant yearly = plantService.createPlantWithWateringSchedule(
                testUser, null, "Раз в год", 365, LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).plusDays(365));

        assertThat(scheduleRepository.findByPlantIdAndTaskType(daily.getId(), TaskType.WATERING))
                .map(CareSchedule::getIntervalDays).contains(1);
        assertThat(scheduleRepository.findByPlantIdAndTaskType(yearly.getId(), TaskType.WATERING))
                .map(CareSchedule::getIntervalDays).contains(365);
    }

    @Test
    @DisplayName("Имя с эмодзи и спецсимволами сохраняется корректно")
    void specialCharactersInName_persistCorrectly() {
        String specialName = "🌿 Монстера (№1) [гостиная]";

        Plant plant = plantService.createPlantWithWateringSchedule(
                testUser, monstera.getId(), specialName, 7, LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).plusDays(7));

        Plant reloaded = plantRepository.findById(plant.getId()).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo(specialName);
    }

    // ==================== getPopularSpecies ====================

    @Test
    @DisplayName("getPopularSpecies возвращает виды в порядке убывания популярности")
    void getPopularSpecies_orderedByPopularity() {
        List<Species> popular = plantService.getPopularSpecies(6);

        assertThat(popular).hasSizeLessThanOrEqualTo(6);
        assertThat(popular.getFirst().getName()).isEqualTo("Монстера"); // popularity=100

        for (int i = 1; i < popular.size(); i++) {
            assertThat(popular.get(i).getPopularity())
                    .isLessThanOrEqualTo(popular.get(i - 1).getPopularity());
        }
    }

    // ==================== searchSpecies ====================

    @Test
    @DisplayName("searchSpecies находит по русскому названию (подстрока)")
    void searchSpecies_findsByRussianName() {
        List<Species> results = plantService.searchSpecies("фикус", 10);

        assertThat(results).isNotEmpty();
        assertThat(results).extracting(Species::getName)
                .anyMatch(name -> name.toLowerCase().contains("фикус"));
    }

    @Test
    @DisplayName("searchSpecies находит по латинскому названию")
    void searchSpecies_findsByLatinName() {
        List<Species> results = plantService.searchSpecies("Phalaenopsis", 10);

        assertThat(results).extracting(Species::getName)
                .contains("Орхидея фаленопсис");
    }

    @Test
    @DisplayName("searchSpecies находит по тегам (english)")
    void searchSpecies_findsByTag() {
        List<Species> results = plantService.searchSpecies("orchid", 10);

        assertThat(results).extracting(Species::getName)
                .contains("Орхидея фаленопсис");
    }

    @Test
    @DisplayName("searchSpecies возвращает пустой список для несуществующего запроса")
    void searchSpecies_returnsEmptyForGarbage() {
        List<Species> results = plantService.searchSpecies("xyznonexistent", 10);

        assertThat(results).isEmpty();
    }

    // ==================== addCareSchedule ====================

    @Test
    @DisplayName("addCareSchedule: создаёт MISTING расписание для существующего растения")
    void addCareSchedule_createsMistingSchedule() {
        LocalDateTime waterDue = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).plusDays(3);
        Plant plant = plantService.createPlantWithWateringSchedule(
                testUser, monstera.getId(), "Монстера", 3, waterDue);

        LocalDateTime mistingDue = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).plusDays(3);
        CareSchedule misting = plantService.addCareSchedule(
                plant, TaskType.MISTING, 3, mistingDue);

        assertThat(misting.getId()).isNotNull();
        assertThat(misting.getTaskType()).isEqualTo(TaskType.MISTING);
        assertThat(misting.getIntervalDays()).isEqualTo(3);
        assertThat(misting.getNextDueAt()).isEqualTo(mistingDue);
        assertThat(misting.isActive()).isTrue();
    }

    @Test
    @DisplayName("addCareSchedule: создаёт FERTILIZING расписание")
    void addCareSchedule_createsFertilizingSchedule() {
        LocalDateTime waterDue = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).plusDays(7);
        Plant plant = plantService.createPlantWithWateringSchedule(
                testUser, null, "Кактус", 7, waterDue);

        LocalDateTime fertilizeDue = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).plusDays(14);
        CareSchedule fertilizing = plantService.addCareSchedule(
                plant, TaskType.FERTILIZING, 14, fertilizeDue);

        assertThat(fertilizing.getTaskType()).isEqualTo(TaskType.FERTILIZING);
        assertThat(fertilizing.getIntervalDays()).isEqualTo(14);
        assertThat(fertilizing.isActive()).isTrue();
    }

    @Test
    @DisplayName("addCareSchedule: повторный вызов обновляет существующее, не создаёт дубль")
    void addCareSchedule_updatesExisting() {
        LocalDateTime waterDue = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).plusDays(7);
        Plant plant = plantService.createPlantWithWateringSchedule(
                testUser, null, "Папоротник", 7, waterDue);

        LocalDateTime first = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).plusDays(3);
        plantService.addCareSchedule(plant, TaskType.MISTING, 3, first);

        LocalDateTime second = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).plusDays(1);
        plantService.addCareSchedule(plant, TaskType.MISTING, 1, second);

        List<CareSchedule> all = scheduleRepository.findAllByPlantId(plant.getId()).stream()
                .filter(s -> s.getTaskType() == TaskType.MISTING)
                .toList();

        // Дубля нет — только одна запись типа MISTING
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getIntervalDays()).isEqualTo(1);
        assertThat(all.get(0).getNextDueAt()).isEqualTo(second);
    }

    // ==================== deactivateCareSchedule ====================

    @Test
    @DisplayName("deactivateCareSchedule: деактивирует расписание (is_active = false)")
    void deactivateCareSchedule_setsInactive() {
        LocalDateTime waterDue = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).plusDays(7);
        Plant plant = plantService.createPlantWithWateringSchedule(
                testUser, null, "Пальма", 7, waterDue);

        LocalDateTime mistingDue = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).plusDays(3);
        plantService.addCareSchedule(plant, TaskType.MISTING, 3, mistingDue);

        plantService.deactivateCareSchedule(plant, TaskType.MISTING);

        CareSchedule misting = scheduleRepository
                .findByPlantIdAndTaskType(plant.getId(), TaskType.MISTING)
                .orElseThrow();
        assertThat(misting.isActive()).isFalse();
    }

    @Test
    @DisplayName("deactivateCareSchedule: вызов на несуществующем типе — молча ничего не делает")
    void deactivateCareSchedule_noopIfNotExists() {
        LocalDateTime waterDue = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).plusDays(7);
        Plant plant = plantService.createPlantWithWateringSchedule(
                testUser, null, "Сансевиерия", 7, waterDue);

        // FERTILIZING ещё не создавали — не должно падать
        plantService.deactivateCareSchedule(plant, TaskType.FERTILIZING);

        assertThat(scheduleRepository.findByPlantIdAndTaskType(plant.getId(), TaskType.FERTILIZING))
                .isEmpty();
    }

    // ==================== getActiveSchedules ====================

    @Test
    @DisplayName("getActiveSchedules: возвращает только активные расписания")
    void getActiveSchedules_returnsOnlyActive() {
        LocalDateTime waterDue = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).plusDays(7);
        Plant plant = plantService.createPlantWithWateringSchedule(
                testUser, null, "Фикус", 7, waterDue);

        plantService.addCareSchedule(plant, TaskType.MISTING, 3,
                LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).plusDays(3));
        plantService.addCareSchedule(plant, TaskType.FERTILIZING, 14,
                LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).plusDays(14));

        // Деактивируем удобрение
        plantService.deactivateCareSchedule(plant, TaskType.FERTILIZING);

        List<CareSchedule> active = plantService.getActiveSchedules(plant.getId());

        assertThat(active).hasSize(2);
        assertThat(active).extracting(CareSchedule::getTaskType)
                .containsExactlyInAnyOrder(TaskType.WATERING, TaskType.MISTING);
    }

    // ==================== Валидация (статические методы) ====================

    @Test
    @DisplayName("isValidPlantName: допустимые имена")
    void validPlantName_accepted() {
        assertThat(PlantService.isValidPlantName("Монстера")).isTrue();
        assertThat(PlantService.isValidPlantName("A")).isTrue();
        assertThat(PlantService.isValidPlantName("a".repeat(100))).isTrue();
    }

    @Test
    @DisplayName("isValidPlantName: недопустимые имена")
    void invalidPlantName_rejected() {
        assertThat(PlantService.isValidPlantName(null)).isFalse();
        assertThat(PlantService.isValidPlantName("")).isFalse();
        assertThat(PlantService.isValidPlantName("   ")).isFalse();
        assertThat(PlantService.isValidPlantName("a".repeat(101))).isFalse();
    }

    @Test
    @DisplayName("isValidInterval: допустимые интервалы")
    void validInterval_accepted() {
        assertThat(PlantService.isValidInterval(1)).isTrue();
        assertThat(PlantService.isValidInterval(7)).isTrue();
        assertThat(PlantService.isValidInterval(365)).isTrue();
    }

    @Test
    @DisplayName("isValidInterval: недопустимые интервалы")
    void invalidInterval_rejected() {
        assertThat(PlantService.isValidInterval(0)).isFalse();
        assertThat(PlantService.isValidInterval(-1)).isFalse();
        assertThat(PlantService.isValidInterval(366)).isFalse();
    }

    // ==================== markCareDone (issue #26) ====================

    @Test
    @DisplayName("markCareDone: пишет CareHistory и сдвигает next_due_at от now")
    void markCareDone_writesHistoryAndReschedules() {
        LocalDateTime initialDue = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).plusDays(7);
        Plant plant = plantService.createPlantWithWateringSchedule(
                testUser, monstera.getId(), "Монстера", 7, initialDue);

        PlantService.MarkCareDoneResult result = plantService.markCareDone(
                testUser.getId(), plant.getId(), TaskType.WATERING);

        assertThat(result).isNotNull();
        assertThat(result.wasDuplicate()).isFalse();
        assertThat(result.schedule().getNextDueAt()).isAfter(initialDue.minusSeconds(1));

        // CareHistory должен появиться
        assertThat(careHistoryRepository.findFirstByPlantIdAndTaskTypeOrderByDoneAtDesc(
                plant.getId(), TaskType.WATERING)).isPresent();
    }

    @Test
    @DisplayName("markCareDone: повторное нажатие в течение 60с — дубликат, история не пишется заново")
    void markCareDone_deduplicatesRapidPresses() {
        LocalDateTime initialDue = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).plusDays(7);
        Plant plant = plantService.createPlantWithWateringSchedule(
                testUser, monstera.getId(), "Монстера", 7, initialDue);

        plantService.markCareDone(testUser.getId(), plant.getId(), TaskType.WATERING);
        long historyAfterFirst = careHistoryRepository.count();

        PlantService.MarkCareDoneResult second = plantService.markCareDone(
                testUser.getId(), plant.getId(), TaskType.WATERING);

        assertThat(second).isNotNull();
        assertThat(second.wasDuplicate()).isTrue();
        assertThat(careHistoryRepository.count()).isEqualTo(historyAfterFirst);
    }

    @Test
    @DisplayName("markCareDone: без активного расписания этого типа — возвращает null")
    void markCareDone_returnsNullWhenNoSchedule() {
        LocalDateTime initialDue = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).plusDays(7);
        Plant plant = plantService.createPlantWithWateringSchedule(
                testUser, monstera.getId(), "Монстера", 7, initialDue);

        // У растения создан только WATERING — MISTING не настроен
        PlantService.MarkCareDoneResult result = plantService.markCareDone(
                testUser.getId(), plant.getId(), TaskType.MISTING);

        assertThat(result).isNull();
        assertThat(careHistoryRepository.count()).isZero();
    }

    @Test
    @DisplayName("markCareDone: чужое растение → IllegalArgumentException")
    void markCareDone_rejectsForeignPlant() {
        Plant plant = plantService.createPlantWithWateringSchedule(
                testUser, monstera.getId(), "Монстера", 7,
                LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).plusDays(7));

        User otherUser = userRepository.save(User.builder()
                .telegramChatId(900L)
                .username("other_user")
                .build());

        try {
            plantService.markCareDone(otherUser.getId(), plant.getId(), TaskType.WATERING);
            org.assertj.core.api.Assertions.fail("Should have thrown IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage()).contains("не найдено");
        }
    }

    // ==================== edit mode (issue #27) ====================

    private Plant createTestPlant(String name) {
        LocalDateTime nextDue = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).plusDays(7);
        return plantService.createPlantWithWateringSchedule(
                testUser, monstera.getId(), name, 7, nextDue);
    }

    @Test
    @DisplayName("renamePlant: новое имя сохраняется")
    void renamePlant_savesNewName() {
        Plant plant = createTestPlant("Original");
        plantService.renamePlant(testUser.getId(), plant.getId(), "  New name  ");

        Plant reloaded = plantRepository.findById(plant.getId()).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("New name");
    }

    @Test
    @DisplayName("renamePlant: пустое имя → IllegalArgumentException")
    void renamePlant_rejectsEmpty() {
        Plant plant = createTestPlant("X");
        try {
            plantService.renamePlant(testUser.getId(), plant.getId(), "  ");
            org.assertj.core.api.Assertions.fail("Should have thrown");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    @DisplayName("updateNotes: текст и очистка работают")
    void updateNotes_setAndClear() {
        Plant plant = createTestPlant("X");

        plantService.updateNotes(testUser.getId(), plant.getId(), "Поливает соседка пока в отпуске");
        assertThat(plantRepository.findById(plant.getId()).orElseThrow().getNotes())
                .isEqualTo("Поливает соседка пока в отпуске");

        plantService.updateNotes(testUser.getId(), plant.getId(), null);
        assertThat(plantRepository.findById(plant.getId()).orElseThrow().getNotes()).isNull();

        plantService.updateNotes(testUser.getId(), plant.getId(), "   ");
        assertThat(plantRepository.findById(plant.getId()).orElseThrow().getNotes()).isNull();
    }

    @Test
    @DisplayName("updateNotes: слишком длинная заметка → IllegalArgumentException")
    void updateNotes_rejectsTooLong() {
        Plant plant = createTestPlant("X");
        String overLimit = "a".repeat(PlantService.NOTE_MAX_LENGTH + 1);
        try {
            plantService.updateNotes(testUser.getId(), plant.getId(), overLimit);
            org.assertj.core.api.Assertions.fail("Should have thrown");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    @DisplayName("archivePlant: ставит archived_at, потом не находится как активное")
    void archivePlant_marksArchived() {
        Plant plant = createTestPlant("X");
        plantService.archivePlant(testUser.getId(), plant.getId());

        assertThat(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(
                testUser.getId(), plant.getId())).isEmpty();
        assertThat(plantRepository.findById(plant.getId()).orElseThrow().getArchivedAt()).isNotNull();
    }

    @Test
    @DisplayName("updateScheduleInterval: меняет interval_days, не трогает next_due_at")
    void updateScheduleInterval_changesIntervalOnly() {
        Plant plant = createTestPlant("X");
        LocalDateTime originalNext = scheduleRepository
                .findByPlantIdAndTaskType(plant.getId(), TaskType.WATERING)
                .orElseThrow().getNextDueAt();

        plantService.updateScheduleInterval(
                testUser.getId(), plant.getId(), TaskType.WATERING, 14);

        CareSchedule s = scheduleRepository
                .findByPlantIdAndTaskType(plant.getId(), TaskType.WATERING).orElseThrow();
        assertThat(s.getIntervalDays()).isEqualTo(14);
        assertThat(s.getNextDueAt()).isEqualTo(originalNext);
    }

    @Test
    @DisplayName("updateScheduleInterval: невалидный интервал → IllegalArgumentException")
    void updateScheduleInterval_rejectsBadInterval() {
        Plant plant = createTestPlant("X");
        try {
            plantService.updateScheduleInterval(
                    testUser.getId(), plant.getId(), TaskType.WATERING, 0);
            org.assertj.core.api.Assertions.fail("Should have thrown");
        } catch (IllegalArgumentException expected) { /* ok */ }
    }

    @Test
    @DisplayName("updateScheduleInterval: расписание не настроено → IllegalArgumentException")
    void updateScheduleInterval_rejectsMissingSchedule() {
        Plant plant = createTestPlant("X");
        try {
            // MISTING не настроен у тестового растения
            plantService.updateScheduleInterval(
                    testUser.getId(), plant.getId(), TaskType.MISTING, 5);
            org.assertj.core.api.Assertions.fail("Should have thrown");
        } catch (IllegalArgumentException expected) { /* ok */ }
    }

    @Test
    @DisplayName("rescheduleSchedule: новое значение next_due_at применяется")
    void rescheduleSchedule_setsNewDate() {
        Plant plant = createTestPlant("X");
        LocalDateTime newNext = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).plusDays(2);

        plantService.rescheduleSchedule(
                testUser.getId(), plant.getId(), TaskType.WATERING, newNext);

        CareSchedule s = scheduleRepository
                .findByPlantIdAndTaskType(plant.getId(), TaskType.WATERING).orElseThrow();
        assertThat(s.getNextDueAt()).isEqualTo(newNext);
    }

    @Test
    @DisplayName("toggleSchedule: выключает активное расписание")
    void toggleSchedule_disablesActive() {
        Plant plant = createTestPlant("X");

        CareSchedule s = plantService.toggleSchedule(
                testUser.getId(), plant.getId(), TaskType.WATERING);

        assertThat(s.isActive()).isFalse();
        assertThat(scheduleRepository.findByPlantIdAndTaskType(
                plant.getId(), TaskType.WATERING).orElseThrow().isActive()).isFalse();
    }

    @Test
    @DisplayName("toggleSchedule: включает снова и подвигает next_due_at если оно в прошлом")
    void toggleSchedule_reactivatesAndFixesPastDue() {
        Plant plant = createTestPlant("X");
        // Выключаем
        plantService.toggleSchedule(testUser.getId(), plant.getId(), TaskType.WATERING);

        // Принудительно делаем next_due_at в прошлом и сохраняем
        CareSchedule s = scheduleRepository
                .findByPlantIdAndTaskType(plant.getId(), TaskType.WATERING).orElseThrow();
        s.setNextDueAt(LocalDateTime.now().minusDays(30));
        scheduleRepository.save(s);

        // Включаем — должен сдвинуть на now + interval
        CareSchedule reactivated = plantService.toggleSchedule(
                testUser.getId(), plant.getId(), TaskType.WATERING);

        assertThat(reactivated.isActive()).isTrue();
        assertThat(reactivated.getNextDueAt()).isAfter(LocalDateTime.now().minusMinutes(1));
    }

    @Test
    @DisplayName("toggleSchedule: для несуществующего расписания создаёт новое с дефолтным интервалом")
    void toggleSchedule_createsFreshScheduleWithDefaults() {
        Plant plant = createTestPlant("X");
        // MISTING ещё не существует у этого растения

        CareSchedule created = plantService.toggleSchedule(
                testUser.getId(), plant.getId(), TaskType.MISTING);

        assertThat(created.isActive()).isTrue();
        assertThat(created.getTaskType()).isEqualTo(TaskType.MISTING);
        // Дефолт для misting = 3 дн (если у вида не задано — Monstera в seed-данных
        // может иметь mistingDays — допускаем оба варианта, главное чтобы > 0)
        assertThat(created.getIntervalDays()).isGreaterThan(0).isLessThanOrEqualTo(365);
        assertThat(created.getNextDueAt()).isAfter(LocalDateTime.now().minusMinutes(1));
    }
}