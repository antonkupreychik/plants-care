package com.plantcare.api.v1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plantcare.api.ApiExceptionHandler;
import com.plantcare.api.CurrentUserProvider;
import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.Species;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.HealthZone;
import com.plantcare.core.service.HealthScoreService;
import com.plantcare.core.service.LocationService;
import com.plantcare.core.service.PlantDiagnosisReportService;
import com.plantcare.core.service.PlantService;
import com.plantcare.core.service.UserService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @WebMvcTest for {@link PlantController} — covers all CRUD endpoints, validation,
 * ownership checks, and soft-delete semantics.
 *
 * <p>Filters disabled so {@link AccessDeniedException}
 * reaches the {@link ApiExceptionHandler} advice instead of being intercepted by
 * Spring Security's {@code ExceptionTranslationFilter}.
 */
@WebMvcTest(PlantController.class)
@Import(ApiExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class PlantControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PlantService plantService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private LocationService locationService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @org.junit.jupiter.api.BeforeEach
    void stubCurrentUser() {
        when(currentUserProvider.currentUserId()).thenReturn(1L);
    }

    private Plant mockPlant(long plantId, long userId, String name) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(userId);

        Location location = mock(Location.class);
        when(location.getId()).thenReturn(1L);
        when(location.getName()).thenReturn("Living room");

        Plant plant = mock(Plant.class);
        when(plant.getId()).thenReturn(plantId);
        when(plant.getName()).thenReturn(name);
        when(plant.getNotes()).thenReturn(null);
        when(plant.getPhotoFileId()).thenReturn(null);
        when(plant.getLocation()).thenReturn(location);
        when(plant.getSpecies()).thenReturn(null);
        when(plant.isArchived()).thenReturn(false);
        when(plant.getArchivedAt()).thenReturn(null);
        when(plant.getCreatedAt()).thenReturn(null);
        when(plant.getUser()).thenReturn(user);

        return plant;
    }

    private PlantService.PlantWithHealth insufficientHealth(Plant plant) {
        return new PlantService.PlantWithHealth(plant, HealthScoreService.HealthScore.insufficient());
    }

    @Test
    void should_return_plants_page_when_listing_all() throws Exception {
        Plant p1 = mockPlant(1L, 1L, "Ficus");
        Plant p2 = mockPlant(2L, 1L, "Monstera");

        when(plantService.listPlantsWithHealth(eq(1L), isNull(), eq(0), eq(20)))
                .thenReturn(List.of(insufficientHealth(p1), insufficientHealth(p2)));
        when(plantService.countPlants(1L, null)).thenReturn(2L);

        mockMvc.perform(get("/api/v1/plants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.offset").value(0))
                .andExpect(jsonPath("$.limit").value(20));
    }

    @Test
    void should_return_plants_filtered_by_location() throws Exception {
        Plant p = mockPlant(1L, 1L, "Ficus");

        when(plantService.listPlantsWithHealth(eq(1L), eq(5L), eq(0), eq(20)))
                .thenReturn(List.of(insufficientHealth(p)));
        when(plantService.countPlants(1L, 5L)).thenReturn(1L);

        mockMvc.perform(get("/api/v1/plants?locationId=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Ficus"));
    }

    @Test
    void should_return_plant_when_found() throws Exception {
        Plant plant = mockPlant(1L, 1L, "Ficus");

        when(plantService.getPlantWithHealth(1L, 1L))
                .thenReturn(insufficientHealth(plant));

        mockMvc.perform(get("/api/v1/plants/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Ficus"));
    }

    @Test
    void should_return_404_when_plant_not_found() throws Exception {
        when(plantService.getPlantWithHealth(1L, 999L))
                .thenThrow(new EntityNotFoundException("Plant not found: 999"));

        mockMvc.perform(get("/api/v1/plants/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void should_create_plant() throws Exception {
        User user = User.builder().telegramChatId(1L).build();
        Plant plant = mockPlant(10L, 1L, "Ficus");

        when(userService.getByIdOrThrow(1L)).thenReturn(user);
        when(plantService.createPlant(
                eq(user),
                eq("Ficus"),
                isNull(),
                isNull(),
                isNull(),
                isNull()
        )).thenReturn(plant);

        String body = """
                {"name": "Ficus"}
                """;

        mockMvc.perform(post("/api/v1/plants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.name").value("Ficus"));
    }

    @Test
    void should_create_plant_with_speciesId() throws Exception {
        User user = User.builder().telegramChatId(1L).build();
        Plant plant = mockPlant(11L, 1L, "Монстера");

        Species species = mock(Species.class);
        when(species.getId()).thenReturn(7L);
        when(species.getName()).thenReturn("Monstera deliciosa");
        when(plant.getSpecies()).thenReturn(species);

        when(userService.getByIdOrThrow(1L)).thenReturn(user);
        when(plantService.createPlant(
                eq(user),
                eq("Монстера"),
                isNull(),
                isNull(),
                eq(7L),
                isNull()
        )).thenReturn(plant);

        String body = """
                {"name": "Монстера", "speciesId": 7}
                """;

        mockMvc.perform(post("/api/v1/plants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(11))
                .andExpect(jsonPath("$.speciesId").value(7))
                .andExpect(jsonPath("$.speciesName").value("Monstera deliciosa"));
    }

    @Test
    void should_create_plant_with_parentPlantId() throws Exception {
        User user = User.builder().telegramChatId(1L).build();
        Plant plant = mockPlant(12L, 1L, "Отводок");

        when(userService.getByIdOrThrow(1L)).thenReturn(user);
        when(plantService.createPlant(
                eq(user),
                eq("Отводок"),
                isNull(),
                isNull(),
                isNull(),
                eq(10L)
        )).thenReturn(plant);

        String body = """
                {"name": "Отводок", "parentPlantId": 10}
                """;

        mockMvc.perform(post("/api/v1/plants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(12))
                .andExpect(jsonPath("$.name").value("Отводок"));
    }

    @Test
    void should_return_400_when_name_blank() throws Exception {
        String body = """
                {"name": ""}
                """;

        mockMvc.perform(post("/api/v1/plants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("name"));
    }

    @Test
    void should_return_400_when_name_missing() throws Exception {
        String body = "{}";

        mockMvc.perform(post("/api/v1/plants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void should_return_400_when_name_too_long() throws Exception {
        String longName = "X".repeat(101);
        String body = objectMapper.writeValueAsString(java.util.Map.of("name", longName));

        mockMvc.perform(post("/api/v1/plants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void should_update_plant() throws Exception {
        Plant updated = mockPlant(1L, 1L, "Updated");

        when(plantService.updatePlant(eq(1L), eq(1L), eq("Updated"), isNull(), isNull()))
                .thenReturn(updated);

        String body = """
                {"name": "Updated"}
                """;

        mockMvc.perform(put("/api/v1/plants/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated"));
    }

    @Test
    void should_delete_plant_and_return_204() throws Exception {
        doNothing().when(plantService).archivePlant(1L, 1L);

        mockMvc.perform(delete("/api/v1/plants/1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void should_return_403_when_plant_belongs_to_other_user() throws Exception {
        when(plantService.getPlantWithHealth(1L, 1L))
                .thenThrow(new AccessDeniedException("Access denied"));

        mockMvc.perform(get("/api/v1/plants/1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
    }

    @Test
    void should_return_plant_family_with_children() throws Exception {
        Plant child = mockPlant(25L, 1L, "Отводок 1");

        when(plantService.getPlantFamily(1L, 10L))
                .thenReturn(new PlantService.PlantFamily(null, List.of(child)));

        mockMvc.perform(get("/api/v1/plants/10/family"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.children.length()").value(1))
                .andExpect(jsonPath("$.children[0].id").value(25))
                .andExpect(jsonPath("$.children[0].name").value("Отводок 1"));
    }

    @Test
    void should_return_parent_for_child_family() throws Exception {
        Plant parent = mockPlant(10L, 1L, "Монстера мама");

        when(plantService.getPlantFamily(1L, 25L))
                .thenReturn(new PlantService.PlantFamily(parent, List.of()));

        mockMvc.perform(get("/api/v1/plants/25/family"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parent.id").value(10))
                .andExpect(jsonPath("$.parent.name").value("Монстера мама"))
                .andExpect(jsonPath("$.children.length()").value(0));
    }

    @Test
    void should_return_404_for_family_of_missing_plant() throws Exception {
        when(plantService.getPlantFamily(1L, 999L))
                .thenThrow(new EntityNotFoundException("Plant not found: 999"));

        mockMvc.perform(get("/api/v1/plants/999/family"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void should_include_health_fields_when_listing_plants() throws Exception {
        // arrange
        Plant plant = mockPlant(1L, 1L, "Ficus");
        HealthScoreService.HealthScore health = HealthScoreService.HealthScore.of(85, HealthZone.GREEN);

        when(plantService.listPlantsWithHealth(eq(1L), isNull(), eq(0), eq(20)))
                .thenReturn(List.of(new PlantService.PlantWithHealth(plant, health)));
        when(plantService.countPlants(1L, null)).thenReturn(1L);

        // act + assert
        mockMvc.perform(get("/api/v1/plants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].healthInsufficientData").value(false))
                .andExpect(jsonPath("$.items[0].healthScore").value(85))
                .andExpect(jsonPath("$.items[0].healthZone").value("GREEN"));
    }

    @Test
    void should_include_health_fields_when_getting_plant() throws Exception {
        // arrange
        Plant plant = mockPlant(1L, 1L, "Ficus");
        HealthScoreService.HealthScore health = HealthScoreService.HealthScore.of(85, HealthZone.GREEN);

        when(plantService.getPlantWithHealth(1L, 1L))
                .thenReturn(new PlantService.PlantWithHealth(plant, health));

        // act + assert
        mockMvc.perform(get("/api/v1/plants/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthInsufficientData").value(false))
                .andExpect(jsonPath("$.healthScore").value(85))
                .andExpect(jsonPath("$.healthZone").value("GREEN"));
    }

    @Test
    void should_return_insufficient_data_when_health_insufficient_in_list() throws Exception {
        // arrange
        Plant plant = mockPlant(1L, 1L, "Ficus");
        HealthScoreService.HealthScore insufficientHealth = HealthScoreService.HealthScore.insufficient();

        when(plantService.listPlantsWithHealth(eq(1L), isNull(), eq(0), eq(20)))
                .thenReturn(List.of(new PlantService.PlantWithHealth(plant, insufficientHealth)));
        when(plantService.countPlants(1L, null)).thenReturn(1L);

        // act + assert — score/zone remain null when insufficientData = true
        mockMvc.perform(get("/api/v1/plants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].healthInsufficientData").value(true))
                .andExpect(jsonPath("$.items[0].healthScore").isEmpty())
                .andExpect(jsonPath("$.items[0].healthZone").isEmpty());
    }

    @Test
    void should_return_insufficient_data_when_health_insufficient_for_single_plant() throws Exception {
        // arrange
        Plant plant = mockPlant(1L, 1L, "Ficus");
        HealthScoreService.HealthScore insufficientHealth = HealthScoreService.HealthScore.insufficient();

        when(plantService.getPlantWithHealth(1L, 1L))
                .thenReturn(new PlantService.PlantWithHealth(plant, insufficientHealth));

        // act + assert — score/zone remain null when insufficientData = true
        mockMvc.perform(get("/api/v1/plants/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthInsufficientData").value(true))
                .andExpect(jsonPath("$.healthScore").isEmpty())
                .andExpect(jsonPath("$.healthZone").isEmpty());
    }

    @Test
    void should_return_health_score_when_sufficient_data() throws Exception {
        when(plantService.getPlantHealth(1L, 1L))
                .thenReturn(HealthScoreService.HealthScore.of(82, HealthZone.GREEN));

        mockMvc.perform(get("/api/v1/plants/1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.insufficientData").value(false))
                .andExpect(jsonPath("$.score").value(82))
                .andExpect(jsonPath("$.zone").value("GREEN"));
    }

    @Test
    void should_return_insufficient_health_when_little_data() throws Exception {
        when(plantService.getPlantHealth(1L, 1L))
                .thenReturn(HealthScoreService.HealthScore.insufficient());

        mockMvc.perform(get("/api/v1/plants/1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.insufficientData").value(true))
                .andExpect(jsonPath("$.score").isEmpty())
                .andExpect(jsonPath("$.zone").isEmpty());
    }

    @Test
    void should_return_404_for_health_of_missing_plant() throws Exception {
        when(plantService.getPlantHealth(1L, 999L))
                .thenThrow(new EntityNotFoundException("Plant not found: 999"));

        mockMvc.perform(get("/api/v1/plants/999/health"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void should_return_diagnosis_json_shape_when_plant_has_issues() throws Exception {
        var issues = List.of(
                new PlantDiagnosisReportService.Issue(
                        "UNDERWATERED",
                        PlantDiagnosisReportService.Severity.HIGH,
                        "Пересушен",
                        List.of("Полей растение сегодня", "Проверь, не пересох ли грунт")),
                new PlantDiagnosisReportService.Issue(
                        "UNDERFED",
                        PlantDiagnosisReportService.Severity.LOW,
                        "Не хватает подкормки",
                        List.of("Подкорми растение по графику")));
        var report = new PlantDiagnosisReportService.DiagnosisReport(
                issues,
                List.of(
                        "Полей растение сегодня",
                        "Проверь, не пересох ли грунт",
                        "Подкорми растение по графику"
                )
        );

        when(plantService.getPlantDiagnosis(1L, 1L)).thenReturn(report);

        mockMvc.perform(get("/api/v1/plants/1/diagnosis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issues.length()").value(2))
                .andExpect(jsonPath("$.issues[0].code").value("UNDERWATERED"))
                .andExpect(jsonPath("$.issues[0].severity").value("HIGH"))
                .andExpect(jsonPath("$.issues[0].title").value("Пересушен"))
                .andExpect(jsonPath("$.issues[1].code").value("UNDERFED"))
                .andExpect(jsonPath("$.issues[1].severity").value("LOW"))
                .andExpect(jsonPath("$.recommendations.length()").value(3))
                .andExpect(jsonPath("$.recommendations[0]").value("Полей растение сегодня"))
                .andExpect(jsonPath("$.recommendations[2]").value("Подкорми растение по графику"));
    }

    @Test
    void should_return_404_for_diagnosis_of_missing_plant() throws Exception {
        when(plantService.getPlantDiagnosis(1L, 999L))
                .thenThrow(new EntityNotFoundException("Plant not found: 999"));

        mockMvc.perform(get("/api/v1/plants/999/diagnosis"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }
}