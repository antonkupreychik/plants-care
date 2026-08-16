package com.plantcare.api.v1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plantcare.api.auth.exception.AuthTokenException;
import com.plantcare.api.ApiExceptionHandler;
import com.plantcare.api.CurrentUserProvider;
import com.plantcare.api.generated.model.CareEventType;
import com.plantcare.api.generated.model.CreateCareEventRequest;
import com.plantcare.core.domain.CareHistory;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.service.CareEventApiService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Слайс-тест контроллера {@link CareEventController}.
 *
 * <p>Мокируем сервис и резолвер пользователя — проверяем только HTTP-контракт,
 * маппинг запросов/ответов и коды ответа.
 */
@WebMvcTest(CareEventController.class)
@Import(ApiExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class CareEventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CareEventApiService careEventApiService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    // ===================== POST /api/v1/care-events =====================

    @Test
    void should_return_201_with_response_body_when_valid_request() throws Exception {
        // arrange
        User user = mockUserWithId(42L, 42L);
        when(currentUserProvider.currentUser()).thenReturn(user);

        CareHistory stubbedHistory = mockCareHistory(1L, 10L, "Монстера");
        when(careEventApiService.registerEvent(
                eq(42L), eq(10L), any(), any(Instant.class), any(), any()))
                .thenReturn(stubbedHistory);

        CreateCareEventRequest request = new CreateCareEventRequest(
                10L, CareEventType.WATER, OffsetDateTime.parse("2026-05-22T10:00:00Z"));

        // act + assert
        mockMvc.perform(post("/api/v1/care-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.plantId").value(10))
                .andExpect(jsonPath("$.plantName").value("Монстера"))
                .andExpect(jsonPath("$.type").value("WATER"))
                .andExpect(jsonPath("$.onTime").value(true));
    }

    @Test
    void should_return_201_and_map_soil_check_when_type_is_soil_check() throws Exception {
        // arrange — issue #222: SOIL_CHECK теперь публичный тип
        User user = mockUserWithId(42L, 42L);
        when(currentUserProvider.currentUser()).thenReturn(user);

        CareHistory stubbedHistory = mockCareHistory(2L, 10L, "Фикус", TaskType.SOIL_CHECK);
        when(careEventApiService.registerEvent(
                eq(42L), eq(10L), eq(TaskType.SOIL_CHECK), any(Instant.class), any(), any()))
                .thenReturn(stubbedHistory);

        CreateCareEventRequest request = new CreateCareEventRequest(
                10L, CareEventType.SOIL_CHECK, OffsetDateTime.parse("2026-05-22T10:00:00Z"));

        // act + assert
        mockMvc.perform(post("/api/v1/care-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(2))
                .andExpect(jsonPath("$.type").value("SOIL_CHECK"));

        // контроллер должен передать в сервис именно доменный SOIL_CHECK
        verify(careEventApiService).registerEvent(
                eq(42L), eq(10L), eq(TaskType.SOIL_CHECK), any(Instant.class), any(), any());
    }

    @Test
    void should_return_401_when_no_authenticated_user() throws Exception {
        // arrange — нет JWT в SecurityContext → провайдер бросает AuthTokenException
        when(currentUserProvider.currentUser())
                .thenThrow(AuthTokenException.invalid("No authenticated user in security context"));

        CreateCareEventRequest request = new CreateCareEventRequest(
                10L, CareEventType.WATER, OffsetDateTime.now(ZoneOffset.UTC));

        // act + assert
        mockMvc.perform(post("/api/v1/care-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_INVALID"));
    }

    @Test
    void should_return_400_with_validation_error_when_plant_id_is_null() throws Exception {
        // arrange — создаём мок заранее, иначе Mockito запутается в вложенных when()
        User user = mockUserWithId(42L, 42L);
        when(currentUserProvider.currentUser()).thenReturn(user);

        // plantId = null → @NotNull нарушен
        String body = """
                {"plantId": null, "type": "WATER", "performedAt": "2026-05-22T10:00:00Z"}
                """;

        // act + assert
        mockMvc.perform(post("/api/v1/care-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("plantId"));
    }

    @Test
    void should_return_400_with_validation_error_when_performed_at_is_null() throws Exception {
        // arrange
        User user = mockUserWithId(42L, 42L);
        when(currentUserProvider.currentUser()).thenReturn(user);

        // performedAt = null → @NotNull нарушен
        String body = """
                {"plantId": 10, "type": "WATER", "performedAt": null}
                """;

        // act + assert
        mockMvc.perform(post("/api/v1/care-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void should_return_404_when_plant_not_found_for_user() throws Exception {
        // arrange
        User user = mockUserWithId(42L, 42L);
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(careEventApiService.registerEvent(any(), any(), any(), any(), any(), any()))
                .thenThrow(new EntityNotFoundException("Plant not found: id=99 for userId=42"));

        CreateCareEventRequest request = new CreateCareEventRequest(
                99L, CareEventType.WATER, OffsetDateTime.now(ZoneOffset.UTC));

        // act + assert
        mockMvc.perform(post("/api/v1/care-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ===================== DELETE /api/v1/care-events/{id} =====================

    @Test
    void should_return_204_when_cancel_care_event_succeeds() throws Exception {
        // arrange
        User user = mockUserWithId(42L, 42L);
        when(currentUserProvider.currentUser()).thenReturn(user);

        // act + assert
        mockMvc.perform(delete("/api/v1/care-events/7"))
                .andExpect(status().isNoContent());

        verify(careEventApiService).cancelEvent(42L, 7L);
    }

    @Test
    void should_return_404_when_cancel_nonexistent_care_event() throws Exception {
        // arrange
        User user = mockUserWithId(42L, 42L);
        when(currentUserProvider.currentUser()).thenReturn(user);
        doThrow(new EntityNotFoundException("CareHistory not found: id=999"))
                .when(careEventApiService).cancelEvent(42L, 999L);

        // act + assert
        mockMvc.perform(delete("/api/v1/care-events/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void should_return_409_when_cancel_already_cancelled_care_event() throws Exception {
        // arrange
        User user = mockUserWithId(42L, 42L);
        when(currentUserProvider.currentUser()).thenReturn(user);
        doThrow(new IllegalStateException("CareHistory id=5 is already cancelled"))
                .when(careEventApiService).cancelEvent(42L, 5L);

        // act + assert
        mockMvc.perform(delete("/api/v1/care-events/5"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"))
                .andExpect(jsonPath("$.error.message").value("CareHistory id=5 is already cancelled"));
    }

    // ===================== helpers =====================

    /**
     * Создаёт Mockito-мок {@link User} с предзаданными id и chatId.
     * Использование мока оправдано: id назначается БД, здесь нам нужен
     * предсказуемый id без поднятия контейнера.
     */
    private User mockUserWithId(Long id, Long chatId) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(id);
        when(user.getTelegramChatId()).thenReturn(chatId);
        when(user.getTimezone()).thenReturn("UTC");
        return user;
    }

    /**
     * Создаёт Mockito-мок {@link CareHistory} с предзаданными полями.
     * Мок оправдан: тест проверяет только HTTP → JSON маппинг, не сохранение в БД.
     */
    private CareHistory mockCareHistory(Long historyId, Long plantId, String plantName) {
        return mockCareHistory(historyId, plantId, plantName, TaskType.WATERING);
    }

    private CareHistory mockCareHistory(Long historyId, Long plantId, String plantName, TaskType taskType) {
        Plant plant = mock(Plant.class);
        when(plant.getId()).thenReturn(plantId);
        when(plant.getName()).thenReturn(plantName);

        CareHistory history = mock(CareHistory.class);
        when(history.getId()).thenReturn(historyId);
        when(history.getPlant()).thenReturn(plant);
        when(history.getTaskType()).thenReturn(taskType);
        when(history.getDoneAt()).thenReturn(LocalDateTime.parse("2026-05-22T10:00:00"));
        when(history.isOnTime()).thenReturn(true);
        when(history.getNote()).thenReturn(null);
        when(history.getClientId()).thenReturn(null);
        when(history.isCancelled()).thenReturn(false);
        return history;
    }
}
