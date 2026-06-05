package com.plantcare.api.v1;

import com.plantcare.api.ApiExceptionHandler;
import com.plantcare.api.CurrentUserProvider;
import com.plantcare.core.domain.CareHistory;
import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.service.SyncService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Слайс-тест {@link SyncController}.
 *
 * <p>Проверяет HTTP-контракт и маппинг: статус 200, структуру ответа,
 * передачу параметра since в сервис. Сервис мокируется.
 */
@WebMvcTest(SyncController.class)
@Import(ApiExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class SyncControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SyncService syncService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    // ===================== GET /api/v1/sync — full sync (no since) =====================

    @Test
    void should_return_200_with_sync_response_when_no_since_param() throws Exception {
        // arrange
        when(currentUserProvider.currentUserId()).thenReturn(42L);

        Plant plant = stubPlant(10L, "Монстера", 1L, "Гостиная");
        Location location = stubLocation(1L, "Гостиная", "🌿");
        Instant serverTime = Instant.parse("2026-06-05T12:00:00Z");

        SyncService.SyncResult result = new SyncService.SyncResult(
                List.of(plant),
                List.of(location),
                List.of(),
                List.of(),
                serverTime
        );

        when(syncService.sync(eq(42L), any(Instant.class))).thenReturn(result);

        // act + assert
        mockMvc.perform(get("/api/v1/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plants").isArray())
                .andExpect(jsonPath("$.plants[0].id").value(10))
                .andExpect(jsonPath("$.plants[0].name").value("Монстера"))
                .andExpect(jsonPath("$.plants[0].locationId").value(1))
                .andExpect(jsonPath("$.locations").isArray())
                .andExpect(jsonPath("$.locations[0].id").value(1))
                .andExpect(jsonPath("$.locations[0].name").value("Гостиная"))
                .andExpect(jsonPath("$.careEvents").isArray())
                .andExpect(jsonPath("$.deletions").isArray())
                .andExpect(jsonPath("$.serverTime").value("2026-06-05T12:00:00Z"));
    }

    // ===================== GET /api/v1/sync?since=... =====================

    @Test
    void should_pass_since_param_to_service_when_provided() throws Exception {
        // arrange
        when(currentUserProvider.currentUserId()).thenReturn(7L);

        Instant sinceInstant = Instant.parse("2026-06-01T10:00:00Z");
        Instant serverTime = Instant.parse("2026-06-05T12:00:00Z");

        SyncService.SyncResult result = new SyncService.SyncResult(
                List.of(), List.of(), List.of(), List.of(), serverTime
        );

        when(syncService.sync(eq(7L), any(Instant.class))).thenReturn(result);

        // act
        mockMvc.perform(get("/api/v1/sync").param("since", "2026-06-01T10:00:00Z"))
                .andExpect(status().isOk());

        // assert — сервис получил корректный since
        verify(syncService).sync(eq(7L), eq(sinceInstant));
    }

    // ===================== GET /api/v1/sync — care events in response =====================

    @Test
    void should_include_care_events_in_response() throws Exception {
        // arrange
        when(currentUserProvider.currentUserId()).thenReturn(5L);

        Plant plant = stubPlant(10L, "Фикус", 2L, "Балкон");
        CareHistory history = stubCareHistory(99L, plant, TaskType.WATERING, "client-uuid-1");
        Instant serverTime = Instant.parse("2026-06-05T12:00:00Z");

        SyncService.SyncResult result = new SyncService.SyncResult(
                List.of(), List.of(), List.of(history), List.of(), serverTime
        );

        when(syncService.sync(eq(5L), any(Instant.class))).thenReturn(result);

        // act + assert
        mockMvc.perform(get("/api/v1/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.careEvents[0].id").value(99))
                .andExpect(jsonPath("$.careEvents[0].plantId").value(10))
                .andExpect(jsonPath("$.careEvents[0].type").value("WATER"))
                .andExpect(jsonPath("$.careEvents[0].onTime").value(true))
                .andExpect(jsonPath("$.careEvents[0].clientId").value("client-uuid-1"));
    }

    // ===================== GET /api/v1/sync — deletions in response =====================

    @Test
    void should_include_deletions_in_response() throws Exception {
        // arrange
        when(currentUserProvider.currentUserId()).thenReturn(3L);

        Instant deletedAt = Instant.parse("2026-06-02T08:00:00Z");
        SyncService.DeletionDto deletion = new SyncService.DeletionDto("plant", 42L, deletedAt);
        Instant serverTime = Instant.parse("2026-06-05T12:00:00Z");

        SyncService.SyncResult result = new SyncService.SyncResult(
                List.of(), List.of(), List.of(), List.of(deletion), serverTime
        );

        when(syncService.sync(eq(3L), any(Instant.class))).thenReturn(result);

        // act + assert
        mockMvc.perform(get("/api/v1/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletions[0].entityType").value("plant"))
                .andExpect(jsonPath("$.deletions[0].id").value(42))
                .andExpect(jsonPath("$.deletions[0].deletedAt").value("2026-06-02T08:00:00Z"));
    }

    // ===================== helpers =====================

    private static Plant stubPlant(long id, String name, long locationId, String locationName) {
        Location location = mock(Location.class);
        when(location.getId()).thenReturn(locationId);
        when(location.getName()).thenReturn(locationName);

        Plant plant = mock(Plant.class);
        when(plant.getId()).thenReturn(id);
        when(plant.getName()).thenReturn(name);
        when(plant.getNotes()).thenReturn(null);
        when(plant.getLocation()).thenReturn(location);
        when(plant.getSpecies()).thenReturn(null);
        when(plant.isArchived()).thenReturn(false);
        when(plant.getClientId()).thenReturn(null);
        LocalDateTime updatedAt = LocalDateTime.of(2026, 6, 1, 10, 0, 0);
        when(plant.getUpdatedAt()).thenReturn(updatedAt);
        when(plant.getCreatedAt()).thenReturn(updatedAt);

        return plant;
    }

    private static Location stubLocation(long id, String name, String emoji) {
        Location location = mock(Location.class);
        when(location.getId()).thenReturn(id);
        when(location.getName()).thenReturn(name);
        when(location.getEmoji()).thenReturn(emoji);
        when(location.isDefaultLocation()).thenReturn(true);
        when(location.getClientId()).thenReturn(null);
        LocalDateTime updatedAt = LocalDateTime.of(2026, 6, 1, 9, 0, 0);
        when(location.getUpdatedAt()).thenReturn(updatedAt);
        when(location.getCreatedAt()).thenReturn(updatedAt);

        return location;
    }

    private static CareHistory stubCareHistory(long id, Plant plant, TaskType taskType,
                                               String clientId) {
        CareHistory history = mock(CareHistory.class);
        when(history.getId()).thenReturn(id);
        when(history.getPlant()).thenReturn(plant);
        when(history.getTaskType()).thenReturn(taskType);
        LocalDateTime doneAt = LocalDateTime.of(2026, 6, 3, 8, 0, 0);
        when(history.getDoneAt()).thenReturn(doneAt);
        when(history.isOnTime()).thenReturn(true);
        when(history.getNote()).thenReturn(null);
        when(history.getClientId()).thenReturn(clientId);
        when(history.getUpdatedAt()).thenReturn(doneAt);
        when(history.getCreatedAt()).thenReturn(doneAt);

        return history;
    }
}
