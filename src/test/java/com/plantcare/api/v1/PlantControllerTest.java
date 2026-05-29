package com.plantcare.api.v1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plantcare.api.ApiExceptionHandler;
import com.plantcare.api.CurrentUserProvider;
import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.Species;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.HealthZone;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import com.plantcare.core.service.HealthScoreService;
import com.plantcare.core.service.LocationService;
import com.plantcare.core.service.PlantDiagnosisReportService;
import com.plantcare.core.service.PlantService;
import com.plantcare.core.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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
 * ownership checks, and soft-delete semantics (issue #85).
 *
 * <p>Filters disabled so {@link org.springframework.security.access.AccessDeniedException}
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
        // По умолчанию аутентифицированный пользователь — userId=1 (как в прежних X-User-Id=1).
        when(currentUserProvider.currentUserId()).thenReturn(1L);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Builds a Plant mock where {@code plant.getUser().getId()} returns {@code userId}
     * and the plant is not archived (archivedAt == null).
     */
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

    // ------------------------------------------------------------------ tests

    @Test
    void should_return_plants_page_when_listing_all() throws Exception {
        // arrange
        Plant p1 = mockPlant(1L, 1L, "Ficus");
        Plant p2 = mockPlant(2L, 1L, "Monstera");

        when(plantService.listPlants(eq(1L), isNull(), eq(0), eq(20)))
                .thenReturn(List.of(p1, p2));
        when(plantService.countPlants(1L, null)).thenReturn(2L);

        // act + assert
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
        // arrange
        Plant p = mockPlant(1L, 1L, "Ficus");

        when(plantService.listPlants(eq(1L), eq(5L), eq(0), eq(20)))
                .thenReturn(List.of(p));
        when(plantService.countPlants(1L, 5L)).thenReturn(1L);

        // act + assert
        mockMvc.perform(get("/api/v1/plants?locationId=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Ficus"));
    }

    @Test
    void should_return_plant_when_found() throws Exception {
        // arrange
        Plant plant = mockPlant(1L, 1L, "Ficus");

        when(plantService.getPlantOrThrow(1L, 1L)).thenReturn(plant);

        // act + assert
        mockMvc.perform(get("/api/v1/plants/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Ficus"));
    }

    @Test
    void should_return_404_when_plant_not_found() throws Exception {
        // arrange — service throws EntityNotFoundException → handler maps to 404
        when(plantService.getPlantOrThrow(1L, 999L))
                .thenThrow(new EntityNotFoundException("Plant not found: 999"));

        // act + assert
        mockMvc.perform(get("/api/v1/plants/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void should_create_plant() throws Exception {
        // arrange
        User user = User.builder().telegramChatId(1L).build();
        Plant plant = mockPlant(10L, 1L, "Ficus");

        when(userService.getByIdOrThrow(1L)).thenReturn(user);
        when(plantService.createPlantWithDefaultSchedules(eq(user), eq("Ficus"), isNull(), isNull(), isNull()))
                .thenReturn(plant);

        String body = """
                {"name": "Ficus"}
                """;

        // act + assert
        mockMvc.perform(post("/api/v1/plants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.name").value("Ficus"));
    }

    @Test
    void should_create_plant_with_speciesId() throws Exception {
        // arrange — клиент передаёт speciesId (mobile gap G13)
        User user = User.builder().telegramChatId(1L).build();
        Plant plant = mockPlant(11L, 1L, "Монстера");
        Species species = mock(Species.class);
        when(species.getId()).thenReturn(7L);
        when(species.getName()).thenReturn("Monstera deliciosa");
        when(plant.getSpecies()).thenReturn(species);

        when(userService.getByIdOrThrow(1L)).thenReturn(user);
        when(plantService.createPlantWithDefaultSchedules(eq(user), eq("Монстера"), isNull(), isNull(), eq(7L)))
                .thenReturn(plant);

        String body = """
                {"name": "Монстера", "speciesId": 7}
                """;

        // act + assert
        mockMvc.perform(post("/api/v1/plants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(11))
                .andExpect(jsonPath("$.speciesId").value(7))
                .andExpect(jsonPath("$.speciesName").value("Monstera deliciosa"));
    }

    @Test
    void should_return_400_when_name_blank() throws Exception {
        // arrange
        String body = """
                {"name": ""}
                """;

        // act + assert
        mockMvc.perform(post("/api/v1/plants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("name"));
    }

    @Test
    void should_return_400_when_name_missing() throws Exception {
        // arrange — no "name" field in body at all
        String body = "{}";

        // act + assert
        mockMvc.perform(post("/api/v1/plants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void should_return_400_when_name_too_long() throws Exception {
        // arrange — name exceeds @Size(max = 100)
        String longName = "X".repeat(101);
        String body = objectMapper.writeValueAsString(java.util.Map.of("name", longName));

        // act + assert
        mockMvc.perform(post("/api/v1/plants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void should_update_plant() throws Exception {
        // arrange
        Plant updated = mockPlant(1L, 1L, "Updated");

        when(plantService.updatePlant(eq(1L), eq(1L), eq("Updated"), isNull(), isNull()))
                .thenReturn(updated);

        String body = """
                {"name": "Updated"}
                """;

        // act + assert
        mockMvc.perform(put("/api/v1/plants/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated"));
    }

    @Test
    void should_delete_plant_and_return_204() throws Exception {
        // arrange
        doNothing().when(plantService).archivePlant(1L, 1L);

        // act + assert
        mockMvc.perform(delete("/api/v1/plants/1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void should_return_403_when_plant_belongs_to_other_user() throws Exception {
        // arrange — service throws AccessDeniedException when plant belongs to another user
        when(plantService.getPlantOrThrow(1L, 1L))
                .thenThrow(new AccessDeniedException("Access denied"));

        // act + assert
        mockMvc.perform(get("/api/v1/plants/1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
    }

    // ------------------------------------------------------------- health (G1)

    @Test
    void should_return_health_score_when_sufficient_data() throws Exception {
        // arrange
        when(plantService.getPlantHealth(1L, 1L))
                .thenReturn(HealthScoreService.HealthScore.of(82, HealthZone.GREEN));

        // act + assert
        mockMvc.perform(get("/api/v1/plants/1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.insufficientData").value(false))
                .andExpect(jsonPath("$.score").value(82))
                .andExpect(jsonPath("$.zone").value("GREEN"));
    }

    @Test
    void should_return_insufficient_health_when_little_data() throws Exception {
        // arrange — данных мало → score/zone null
        when(plantService.getPlantHealth(1L, 1L))
                .thenReturn(HealthScoreService.HealthScore.insufficient());

        // act + assert
        mockMvc.perform(get("/api/v1/plants/1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.insufficientData").value(true))
                .andExpect(jsonPath("$.score").isEmpty())
                .andExpect(jsonPath("$.zone").isEmpty());
    }

    @Test
    void should_return_404_for_health_of_missing_plant() throws Exception {
        // arrange — ownership/not-found пробрасывается из сервиса
        when(plantService.getPlantHealth(1L, 999L))
                .thenThrow(new EntityNotFoundException("Plant not found: 999"));

        // act + assert
        mockMvc.perform(get("/api/v1/plants/999/health"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ---------------------------------------------------- diagnosis (#193)

    @Test
    void should_return_diagnosis_json_shape_when_plant_has_issues() throws Exception {
        // arrange — отчёт с двумя проблемами (HIGH первой) и deduped-рекомендациями
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
                List.of("Полей растение сегодня", "Проверь, не пересох ли грунт",
                        "Подкорми растение по графику"));

        when(plantService.getPlantDiagnosis(1L, 1L)).thenReturn(report);

        // act + assert
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
        // arrange — чужое/архивное растение → not-found пробрасывается из сервиса
        when(plantService.getPlantDiagnosis(1L, 999L))
                .thenThrow(new EntityNotFoundException("Plant not found: 999"));

        // act + assert
        mockMvc.perform(get("/api/v1/plants/999/diagnosis"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }
}
