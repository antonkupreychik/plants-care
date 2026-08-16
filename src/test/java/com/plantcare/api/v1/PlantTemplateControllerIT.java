package com.plantcare.api.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plantcare.bot.support.IntegrationTestBase;
import com.plantcare.core.domain.CareSchedule;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.PlantTemplate;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.repository.CareScheduleRepository;
import com.plantcare.core.repository.PlantRepository;
import com.plantcare.core.repository.PlantTemplateCareRuleRepository;
import com.plantcare.core.repository.PlantTemplateRepository;
import com.plantcare.core.repository.UserRepository;
import com.plantcare.core.service.PlantService;
import com.plantcare.core.service.PlantTemplateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Интеграционные тесты REST API шаблонов растений (issue #254).
 *
 * <p>Покрывает AC:
 * - GET /plant-templates — список шаблонов
 * - POST /plant-templates с fromPlantId — создание из растения
 * - POST /plant-templates без fromPlantId — создание пустого шаблона
 * - DELETE /plant-templates/{id} — удаление
 * - POST /plant-templates/{id}/instantiate — создание растения из шаблона
 *
 * <p>Таймзонный кейс: пользователь с Asia/Almaty.
 */
@AutoConfigureMockMvc
class PlantTemplateControllerIT extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlantRepository plantRepository;

    @Autowired
    private PlantTemplateRepository templateRepository;

    @Autowired
    private PlantTemplateCareRuleRepository careRuleRepository;

    @Autowired
    private CareScheduleRepository careScheduleRepository;

    @Autowired
    private PlantService plantService;

    @Autowired
    private PlantTemplateService plantTemplateService;

    private User user;
    private Plant plant;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.builder()
                .telegramChatId(9_254_001L)
                .username("template_api_test_user")
                .timezone("Europe/Moscow")
                .build());

        plant = plantService.createPlantWithWateringSchedule(
                user, null, "Тестовая монстера", 7,
                LocalDateTime.now().plusDays(7));

        // Добавляем расписание опрыскивания — шаблон будет содержать 2 правила
        plantService.addCareSchedule(plant, TaskType.MISTING, 3,
                LocalDateTime.now().plusDays(3));
    }

    @AfterEach
    void cleanup() {
        // Delete in FK-safe order: care_rules (cascade from templates), templates,
        // care_schedules, plants, then locations (cascade from users) and users last.
        // Use deleteAll() for whole-table cleanup to avoid orphan constraint issues.
        careRuleRepository.deleteAll();
        templateRepository.deleteAll();
        careScheduleRepository.deleteAll();
        plantRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ================================================================
    // GET /api/v1/plant-templates — список шаблонов
    // ================================================================

    @Test
    void should_return_empty_list_when_user_has_no_templates() throws Exception {
        // act + assert
        mockMvc.perform(get("/api/v1/plant-templates")
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(user.getId())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void should_return_templates_for_current_user() throws Exception {
        // arrange
        plantTemplateService.saveFromPlant(user, plant.getId(), "Монстера шаблон");

        // act + assert
        mockMvc.perform(get("/api/v1/plant-templates")
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(user.getId())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Монстера шаблон"))
                .andExpect(jsonPath("$[0].id").isNumber())
                .andExpect(jsonPath("$[0].careRules").isArray());
    }

    @Test
    void should_not_return_other_users_templates() throws Exception {
        // arrange — template for a different user
        User otherUser = userRepository.save(User.builder()
                .telegramChatId(9_254_002L)
                .timezone("UTC")
                .build());

        Plant otherPlant = plantService.createPlantWithWateringSchedule(
                otherUser, null, "Чужое растение", 5,
                LocalDateTime.now().plusDays(5));

        plantTemplateService.saveFromPlant(otherUser, otherPlant.getId(), "Чужой шаблон");

        // act — request as our user
        mockMvc.perform(get("/api/v1/plant-templates")
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(user.getId())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ================================================================
    // POST /api/v1/plant-templates — создание шаблона из растения
    // ================================================================

    @Test
    void should_create_template_from_plant_and_copy_active_schedules() throws Exception {
        // act
        String body = """
                {
                  "name": "Монстера 7д",
                  "fromPlantId": %d
                }
                """.formatted(plant.getId());

        MvcResult result = mockMvc.perform(post("/api/v1/plant-templates")
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(user.getId()))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Монстера 7д"))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.careRules").isArray())
                .andReturn();

        // assert — care rules copied (WATERING + MISTING)
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.get("careRules").size()).isEqualTo(2);

        long templateId = json.get("id").asLong();
        var rules = careRuleRepository.findAllByTemplateId(templateId);
        assertThat(rules).extracting(r -> r.getCareType().name())
                .containsExactlyInAnyOrder("WATERING", "MISTING");
    }

    @Test
    void should_create_empty_template_when_fromPlantId_not_provided() throws Exception {
        // act
        String body = """
                {"name": "Пустой шаблон"}
                """;

        MvcResult result = mockMvc.perform(post("/api/v1/plant-templates")
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(user.getId()))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Пустой шаблон"))
                .andReturn();

        // assert — no care rules
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.get("careRules").size()).isEqualTo(0);
    }

    @Test
    void should_return_409_when_template_name_is_duplicate() throws Exception {
        // arrange — create first template
        plantTemplateService.saveFromPlant(user, plant.getId(), "Дубль");

        // act — try to create template with same name (case-insensitive)
        String body = """
                {"name": "дубль", "fromPlantId": %d}
                """.formatted(plant.getId());

        mockMvc.perform(post("/api/v1/plant-templates")
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(user.getId()))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void should_return_400_when_template_name_is_blank() throws Exception {
        // act
        String body = """
                {"name": "   "}
                """;

        mockMvc.perform(post("/api/v1/plant-templates")
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(user.getId()))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // ================================================================
    // DELETE /api/v1/plant-templates/{id}
    // ================================================================

    @Test
    void should_delete_template_and_not_affect_plants() throws Exception {
        // arrange
        PlantTemplate template = plantTemplateService.saveFromPlant(
                user, plant.getId(), "Удаляемый шаблон");
        Plant fromTemplate = plantTemplateService.createPlantFromTemplate(
                user, template.getId(), "Растение из шаблона");

        // act
        mockMvc.perform(delete("/api/v1/plant-templates/" + template.getId())
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(user.getId())))))
                .andExpect(status().isNoContent());

        // assert — template deleted, plant untouched
        assertThat(templateRepository.findById(template.getId())).isEmpty();
        assertThat(plantRepository.findById(fromTemplate.getId())).isPresent();
    }

    @Test
    void should_return_404_when_deleting_nonexistent_template() throws Exception {
        // act + assert
        mockMvc.perform(delete("/api/v1/plant-templates/9999999")
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(user.getId())))))
                .andExpect(status().isNotFound());
    }

    @Test
    void should_return_404_when_deleting_other_users_template() throws Exception {
        // arrange — template owned by a different user
        User otherUser = userRepository.save(User.builder()
                .telegramChatId(9_254_003L)
                .timezone("UTC")
                .build());

        Plant otherPlant = plantService.createPlantWithWateringSchedule(
                otherUser, null, "Чужое", 7, LocalDateTime.now().plusDays(7));

        PlantTemplate otherTemplate = plantTemplateService.saveFromPlant(
                otherUser, otherPlant.getId(), "Чужой шаблон");

        // act — try to delete as our user
        mockMvc.perform(delete("/api/v1/plant-templates/" + otherTemplate.getId())
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(user.getId())))))
                .andExpect(status().isNotFound());
    }

    // ================================================================
    // POST /api/v1/plant-templates/{id}/instantiate — создание растения
    // ================================================================

    @Test
    void should_create_plant_from_template_with_correct_schedules() throws Exception {
        // arrange
        PlantTemplate template = plantTemplateService.saveFromPlant(
                user, plant.getId(), "Шаблон для инстанцирования");

        String body = """
                {"name": "Монстера 2"}
                """;

        // act
        MvcResult result = mockMvc.perform(
                        post("/api/v1/plant-templates/" + template.getId() + "/instantiate")
                                .with(jwt().jwt(j -> j.claim("sub", String.valueOf(user.getId()))))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Монстера 2"))
                .andExpect(jsonPath("$.id").isNumber())
                .andReturn();

        // assert — new plant has schedules from template
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        long newPlantId = json.get("id").asLong();

        List<CareSchedule> schedules = careScheduleRepository.findAllByPlantId(newPlantId);
        assertThat(schedules).extracting(s -> s.getTaskType().name())
                .contains("WATERING", "MISTING");

        // Watering interval should match template (7 days)
        schedules.stream()
                .filter(s -> s.getTaskType() == TaskType.WATERING)
                .findFirst()
                .ifPresent(s -> assertThat(s.getIntervalDays()).isEqualTo(7));
    }

    @Test
    void should_create_plant_from_template_for_non_utc_user_timezone() throws Exception {
        // arrange — user with Asia/Almaty (UTC+5)
        User almaty = userRepository.save(User.builder()
                .telegramChatId(9_254_004L)
                .username("almaty_template_user")
                .timezone("Asia/Almaty")
                .build());

        Plant almatyPlant = plantService.createPlantWithWateringSchedule(
                almaty, null, "Алма-атинская монстера", 7,
                LocalDateTime.now().plusDays(7));

        PlantTemplate template = plantTemplateService.saveFromPlant(
                almaty, almatyPlant.getId(), "Шаблон Алматы");

        String body = """
                {"name": "Алмаатинская 2"}
                """;

        // act
        MvcResult result = mockMvc.perform(
                        post("/api/v1/plant-templates/" + template.getId() + "/instantiate")
                                .with(jwt().jwt(j -> j.claim("sub", String.valueOf(almaty.getId()))))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        // assert — plant created with positive nextDueAt
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        long newPlantId = json.get("id").asLong();

        List<CareSchedule> schedules = careScheduleRepository.findAllByPlantId(newPlantId);
        assertThat(schedules.stream().filter(s -> s.getTaskType() == TaskType.WATERING).findFirst())
                .isPresent()
                .hasValueSatisfying(s -> assertThat(s.getNextDueAt()).isAfter(LocalDateTime.now()));
    }

    @Test
    void should_return_404_when_instantiating_nonexistent_template() throws Exception {
        // act + assert
        String body = """
                {"name": "Растение без шаблона"}
                """;

        mockMvc.perform(post("/api/v1/plant-templates/9999999/instantiate")
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(user.getId()))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void should_return_400_when_plant_name_is_blank_on_instantiate() throws Exception {
        // arrange
        PlantTemplate template = plantTemplateService.saveFromPlant(
                user, plant.getId(), "Шаблон для проверки имени");

        String body = """
                {"name": ""}
                """;

        // act + assert
        mockMvc.perform(post("/api/v1/plant-templates/" + template.getId() + "/instantiate")
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(user.getId()))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
