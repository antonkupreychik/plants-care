package com.plantcare.bot.service;

import com.plantcare.bot.domain.CareSchedule;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.Species;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.TaskType;
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
}