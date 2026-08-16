package com.plantcare.api.v1;

import tools.jackson.databind.ObjectMapper;
import com.plantcare.api.ApiExceptionHandler;
import com.plantcare.api.CurrentUserProvider;
import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.service.HealthScoreService;
import com.plantcare.core.service.LocationService;
import com.plantcare.core.service.PlantAcclimationService;
import com.plantcare.core.service.PlantDiagnosisReportService;
import com.plantcare.core.service.PlantService;
import com.plantcare.core.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link PlantController} tests covering issue #221:
 * acquiredAt and isNew fields in POST /plants.
 */
@WebMvcTest(PlantController.class)
@Import(ApiExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class PlantCreateAcquiredAtControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PlantService plantService;

    @MockitoBean
    private PlantAcclimationService plantAcclimationService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private LocationService locationService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @MockitoBean
    private Clock clock;

    private User testUser;

    @BeforeEach
    void setUp() {
        when(currentUserProvider.currentUserId()).thenReturn(1L);
        testUser = User.builder().telegramChatId(1L).build();
        when(userService.getByIdOrThrow(1L)).thenReturn(testUser);
        // Stub clock to return fixed "now" for inAcclimation computation
        Instant fixedNow = Instant.parse("2026-06-03T00:00:00Z");
        when(clock.instant()).thenReturn(fixedNow);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    /** Fixed "now" that matches the stubbed clock — 2026-06-03T00:00:00Z. */
    private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 6, 3, 0, 0, 0);

    private Plant mockPlantWithAcquiredAt(long id, LocalDate acquiredAt, LocalDateTime acclimationUntil) {
        Location location = mock(Location.class);
        when(location.getId()).thenReturn(1L);
        when(location.getName()).thenReturn("Подоконник");

        Plant plant = mock(Plant.class);
        when(plant.getId()).thenReturn(id);
        when(plant.getName()).thenReturn("Монстера");
        when(plant.getNotes()).thenReturn(null);
        when(plant.getPhotoFileId()).thenReturn(null);
        when(plant.getLocation()).thenReturn(location);
        when(plant.getSpecies()).thenReturn(null);
        when(plant.isArchived()).thenReturn(false);
        when(plant.getCreatedAt()).thenReturn(null);
        when(plant.getUser()).thenReturn(mock(com.plantcare.core.domain.User.class));
        when(plant.getAcquiredAt()).thenReturn(acquiredAt);
        when(plant.getAcclimationUntil()).thenReturn(acclimationUntil);
        // isInAcclimation is called with LocalDateTime.now(clock) = FIXED_NOW
        when(plant.isInAcclimation(any(LocalDateTime.class)))
                .thenReturn(acclimationUntil != null && acclimationUntil.isAfter(FIXED_NOW));
        return plant;
    }

    // ==================== acquiredAt ====================

    @Test
    void should_save_acquiredAt_when_provided_in_request() throws Exception {
        // arrange
        LocalDate acquired = LocalDate.of(2026, 5, 20);
        Plant plant = mockPlantWithAcquiredAt(30L, acquired, null);

        when(plantService.createPlant(
                eq(testUser),
                eq("Монстера"),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                eq(acquired),
                isNull()
        )).thenReturn(plant);

        String body = """
                {"name": "Монстера", "acquiredAt": "2026-05-20"}
                """;

        // act + assert
        mockMvc.perform(post("/api/v1/plants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(30))
                .andExpect(jsonPath("$.acquiredAt").value("2026-05-20"))
                .andExpect(jsonPath("$.inAcclimation").value(false))
                .andExpect(jsonPath("$.acclimationUntil").isEmpty());

        verify(plantAcclimationService, never()).enable(any(), any());
    }

    @Test
    void should_not_set_acquiredAt_when_absent_in_request() throws Exception {
        // arrange
        Plant plant = mockPlantWithAcquiredAt(31L, null, null);

        when(plantService.createPlant(
                eq(testUser),
                eq("Фикус"),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull()
        )).thenReturn(plant);

        String body = """
                {"name": "Фикус"}
                """;

        // act + assert
        mockMvc.perform(post("/api/v1/plants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.acquiredAt").isEmpty())
                .andExpect(jsonPath("$.inAcclimation").value(false))
                .andExpect(jsonPath("$.acclimationUntil").isEmpty());

        verify(plantAcclimationService, never()).enable(any(), any());
    }

    // ==================== isNew + acclimation ====================

    @Test
    void should_enable_acclimation_when_isNew_is_true() throws Exception {
        // arrange
        LocalDateTime acclimationUntil = FIXED_NOW.plusDays(21);
        Plant createdPlant = mockPlantWithAcquiredAt(32L, null, null);
        Plant plantAfterAcclimation = mockPlantWithAcquiredAt(32L, null, acclimationUntil);

        when(plantService.createPlant(
                eq(testUser),
                eq("Новая Монстера"),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull()
        )).thenReturn(createdPlant);

        when(plantAcclimationService.enable(eq(testUser), eq(32L))).thenReturn(plantAfterAcclimation);

        when(plantService.getPlantOrThrow(any(), eq(32L))).thenReturn(plantAfterAcclimation);

        String body = """
                {"name": "Новая Монстера", "isNew": true}
                """;

        // act + assert
        mockMvc.perform(post("/api/v1/plants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(32))
                .andExpect(jsonPath("$.inAcclimation").value(true))
                .andExpect(jsonPath("$.acclimationUntil").isNotEmpty());

        verify(plantAcclimationService).enable(eq(testUser), eq(32L));
        verify(plantService).getPlantOrThrow(any(), eq(32L));
    }

    @Test
    void should_not_enable_acclimation_when_isNew_is_false() throws Exception {
        // arrange
        Plant plant = mockPlantWithAcquiredAt(33L, null, null);

        when(plantService.createPlant(
                eq(testUser),
                eq("Фикус"),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull()
        )).thenReturn(plant);

        String body = """
                {"name": "Фикус", "isNew": false}
                """;

        // act + assert
        mockMvc.perform(post("/api/v1/plants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.inAcclimation").value(false));

        verify(plantAcclimationService, never()).enable(any(), any());
    }

    @Test
    void should_not_enable_acclimation_when_isNew_is_absent() throws Exception {
        // arrange
        Plant plant = mockPlantWithAcquiredAt(34L, null, null);

        when(plantService.createPlant(
                eq(testUser),
                eq("Фикус"),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull()
        )).thenReturn(plant);

        String body = """
                {"name": "Фикус"}
                """;

        // act + assert
        mockMvc.perform(post("/api/v1/plants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        verify(plantAcclimationService, never()).enable(any(), any());
    }

    @Test
    void should_save_acquiredAt_and_enable_acclimation_together() throws Exception {
        // arrange: оба поля переданы одновременно
        LocalDate acquired = LocalDate.of(2026, 5, 15);
        LocalDateTime acclimationUntil = FIXED_NOW.plusDays(21);
        Plant createdPlant = mockPlantWithAcquiredAt(35L, acquired, null);
        Plant plantAfterAcclimation = mockPlantWithAcquiredAt(35L, acquired, acclimationUntil);

        when(plantService.createPlant(
                eq(testUser),
                eq("Растение"),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                eq(acquired),
                isNull()
        )).thenReturn(createdPlant);

        when(plantAcclimationService.enable(eq(testUser), eq(35L))).thenReturn(plantAfterAcclimation);
        when(plantService.getPlantOrThrow(any(), eq(35L))).thenReturn(plantAfterAcclimation);

        String body = """
                {"name": "Растение", "acquiredAt": "2026-05-15", "isNew": true}
                """;

        // act + assert
        mockMvc.perform(post("/api/v1/plants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.acquiredAt").value("2026-05-15"))
                .andExpect(jsonPath("$.inAcclimation").value(true))
                .andExpect(jsonPath("$.acclimationUntil").isNotEmpty());
    }
}
