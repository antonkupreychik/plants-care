package com.plantcare.bot.service;

import com.plantcare.bot.domain.CareSchedule;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.PlantTemplate;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.TaskType;
import com.plantcare.bot.repository.CareScheduleRepository;
import com.plantcare.bot.repository.PlantRepository;
import com.plantcare.bot.repository.PlantTemplateCareRuleRepository;
import com.plantcare.bot.repository.PlantTemplateRepository;
import com.plantcare.bot.repository.UserRepository;
import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Интеграционные тесты PlantTemplateService — проверяют все требования issue #68:
 * - saveFromPlant копирует расписания
 * - createPlantFromTemplate переносит интервалы
 * - удаление шаблона не трогает растения
 * - лимит 30 шаблонов
 * - валидация имени (пустое / длинное / дубль)
 */
@DisplayName("PlantTemplateService — интеграционные тесты (issue #68)")
class PlantTemplateServiceTest extends IntegrationTestBase {

    @Autowired private PlantTemplateService plantTemplateService;
    @Autowired private PlantService plantService;
    @Autowired private PlantTemplateRepository templateRepository;
    @Autowired private PlantTemplateCareRuleRepository careRuleRepository;
    @Autowired private PlantRepository plantRepository;
    @Autowired private CareScheduleRepository careScheduleRepository;
    @Autowired private UserRepository userRepository;

    private User testUser;
    private Plant testPlant;

    @BeforeEach
    void setUp() {
        testUser = userRepository.save(User.builder()
                .telegramChatId(9991_001L)
                .timezone("Europe/Riga")
                .build());

        LocalDateTime nextDue = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).plusDays(7);
        testPlant = plantService.createPlantWithWateringSchedule(
                testUser, null, "Тестовая монстера", 7, nextDue);

        // Добавляем расписание опрыскивания — чтобы шаблон содержал 2 правила
        plantService.addCareSchedule(testPlant, TaskType.MISTING, 3,
                LocalDateTime.now().plusDays(3));
    }

    @AfterEach
    void cleanup() {
        plantRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ================================================================
    // saveFromPlant
    // ================================================================

    @Test
    @DisplayName("saveFromPlant: создаёт шаблон с активными расписаниями")
    void saveFromPlant_createsTemplateWithCareRules() {
        PlantTemplate template = plantTemplateService.saveFromPlant(
                testUser, testPlant.getId(), "Мой шаблон");

        assertThat(template.getId()).isNotNull();
        assertThat(template.getName()).isEqualTo("Мой шаблон");
        assertThat(template.getUserId()).isEqualTo(testUser.getId());

        var rules = careRuleRepository.findAllByTemplateId(template.getId());
        assertThat(rules).hasSize(2);
        assertThat(rules).extracting(r -> r.getCareType())
                .containsExactlyInAnyOrder(TaskType.WATERING, TaskType.MISTING);
    }

    @Test
    @DisplayName("saveFromPlant: пробелы в имени тримятся")
    void saveFromPlant_trimsName() {
        PlantTemplate template = plantTemplateService.saveFromPlant(
                testUser, testPlant.getId(), "  Обрезанный  ");
        assertThat(template.getName()).isEqualTo("Обрезанный");
    }

    @Test
    @DisplayName("saveFromPlant: дубль имени (case-insensitive) — ошибка")
    void saveFromPlant_rejectsDuplicateName() {
        plantTemplateService.saveFromPlant(testUser, testPlant.getId(), "Дубль");

        assertThatThrownBy(() ->
                plantTemplateService.saveFromPlant(testUser, testPlant.getId(), "дубль"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("уже существует");
    }

    @Test
    @DisplayName("saveFromPlant: пустое имя — ошибка")
    void saveFromPlant_rejectsBlankName() {
        assertThatThrownBy(() ->
                plantTemplateService.saveFromPlant(testUser, testPlant.getId(), "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("saveFromPlant: имя > 40 символов — ошибка")
    void saveFromPlant_rejectsTooLongName() {
        String longName = "А".repeat(41);
        assertThatThrownBy(() ->
                plantTemplateService.saveFromPlant(testUser, testPlant.getId(), longName))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("40");
    }

    @Test
    @DisplayName("saveFromPlant: лимит 30 — 31-й не создаётся (issue #68)")
    void saveFromPlant_rejectsWhenLimitReached() {
        for (int i = 1; i <= PlantTemplateService.MAX_TEMPLATES_PER_USER; i++) {
            plantTemplateService.saveFromPlant(testUser, testPlant.getId(), "Шаблон " + i);
        }

        assertThatThrownBy(() ->
                plantTemplateService.saveFromPlant(testUser, testPlant.getId(), "Лишний"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("лимит");
    }

    // ================================================================
    // createPlantFromTemplate
    // ================================================================

    @Test
    @DisplayName("createPlantFromTemplate: растение создаётся с правильными расписаниями")
    void createPlantFromTemplate_createsPlantWithSchedules() {
        PlantTemplate template = plantTemplateService.saveFromPlant(
                testUser, testPlant.getId(), "Шаблон монстеры");

        Plant newPlant = plantTemplateService.createPlantFromTemplate(
                testUser, template.getId(), "Монстера 2");

        assertThat(newPlant.getName()).isEqualTo("Монстера 2");

        var schedules = careScheduleRepository.findAllByPlantId(newPlant.getId());
        assertThat(schedules).extracting(CareSchedule::getTaskType)
                .contains(TaskType.WATERING, TaskType.MISTING);
    }

    @Test
    @DisplayName("createPlantFromTemplate: интервал полива копируется точно")
    void createPlantFromTemplate_copiesWateringInterval() {
        PlantTemplate template = plantTemplateService.saveFromPlant(
                testUser, testPlant.getId(), "Интервальный");

        Plant newPlant = plantTemplateService.createPlantFromTemplate(
                testUser, template.getId(), "Копия");

        CareSchedule watering = careScheduleRepository
                .findByPlantIdAndTaskType(newPlant.getId(), TaskType.WATERING)
                .orElseThrow();
        assertThat(watering.getIntervalDays()).isEqualTo(7);
    }

    // ================================================================
    // deleteTemplate
    // ================================================================

    @Test
    @DisplayName("deleteTemplate: шаблон удаляется, растения остаются (требование issue #68)")
    void deleteTemplate_doesNotAffectPlants() {
        PlantTemplate template = plantTemplateService.saveFromPlant(
                testUser, testPlant.getId(), "Удаляемый");
        Plant fromTemplate = plantTemplateService.createPlantFromTemplate(
                testUser, template.getId(), "Растение из шаблона");

        plantTemplateService.deleteTemplate(testUser, template.getId());

        assertThat(templateRepository.findById(template.getId())).isEmpty();
        // Растение и его расписания не тронуты
        assertThat(plantRepository.findById(fromTemplate.getId())).isPresent();
        assertThat(careScheduleRepository.findAllByPlantId(fromTemplate.getId()))
                .isNotEmpty();
    }

    // ================================================================
    // renameTemplate
    // ================================================================

    @Test
    @DisplayName("renameTemplate: успешное переименование")
    void renameTemplate_updatesName() {
        PlantTemplate template = plantTemplateService.saveFromPlant(
                testUser, testPlant.getId(), "Старое имя");

        PlantTemplate renamed = plantTemplateService.renameTemplate(
                testUser, template.getId(), "Новое имя");

        assertThat(renamed.getName()).isEqualTo("Новое имя");
        assertThat(templateRepository.findById(template.getId()))
                .map(PlantTemplate::getName)
                .hasValue("Новое имя");
    }

    @Test
    @DisplayName("renameTemplate: чужой шаблон не доступен")
    void renameTemplate_rejectsForeignTemplate() {
        PlantTemplate template = plantTemplateService.saveFromPlant(
                testUser, testPlant.getId(), "Мой");

        User otherUser = userRepository.save(User.builder()
                .telegramChatId(999_002L)
                .timezone("UTC")
                .build());

        assertThatThrownBy(() ->
                plantTemplateService.renameTemplate(otherUser, template.getId(), "Угнан"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ================================================================
    // hasReachedLimit
    // ================================================================

    @Test
    @DisplayName("hasReachedLimit: до лимита — false, после — true")
    void hasReachedLimit_reflectsState() {
        assertThat(plantTemplateService.hasReachedLimit(testUser.getId())).isFalse();

        for (int i = 1; i <= PlantTemplateService.MAX_TEMPLATES_PER_USER; i++) {
            plantTemplateService.saveFromPlant(testUser, testPlant.getId(), "T" + i);
        }

        assertThat(plantTemplateService.hasReachedLimit(testUser.getId())).isTrue();
    }
}
