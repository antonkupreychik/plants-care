package com.plantcare.api.v1;

import com.plantcare.api.ApiExceptionHandler;
import com.plantcare.api.CurrentUserProvider;
import com.plantcare.core.domain.CareHistory;
import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.repository.PlantRepository;
import com.plantcare.core.service.CareHistoryService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WebMvcTest для {@link PlantHistoryController} — issue #206.
 *
 * <p>Покрывает HTTP-контракт эндпоинтов GET /history и GET /history/summary:
 * роутинг, маппинг query-параметра type, валидацию и JSON-форму ответа.
 */
@WebMvcTest(PlantHistoryController.class)
@Import(ApiExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class PlantHistoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CareHistoryService careHistoryService;

    @MockitoBean
    private PlantRepository plantRepository;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    private static final long USER_ID = 1L;
    private static final long PLANT_ID = 42L;

    @BeforeEach
    void stubCurrentUser() {
        User user = mock(User.class);
        when(user.getId()).thenReturn(USER_ID);
        when(currentUserProvider.currentUser()).thenReturn(user);
    }

    private Plant stubPlant() {
        User user = mock(User.class);
        when(user.getId()).thenReturn(USER_ID);

        Location location = mock(Location.class);
        when(location.getId()).thenReturn(1L);
        when(location.getName()).thenReturn("Windowsill");

        Plant plant = mock(Plant.class);
        when(plant.getId()).thenReturn(PLANT_ID);
        when(plant.getName()).thenReturn("Monstera");
        when(plant.getNotes()).thenReturn(null);
        when(plant.getPhotoFileId()).thenReturn(null);
        when(plant.getLocation()).thenReturn(location);
        when(plant.getSpecies()).thenReturn(null);
        when(plant.isArchived()).thenReturn(false);
        when(plant.getArchivedAt()).thenReturn(null);
        when(plant.getCreatedAt()).thenReturn(null);
        when(plant.getUser()).thenReturn(user);

        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(USER_ID, PLANT_ID))
                .thenReturn(Optional.of(plant));

        return plant;
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/plants/{id}/history — type-фильтр
    // -------------------------------------------------------------------------

    @Test
    void should_return_history_page_without_filter_when_type_param_absent() throws Exception {
        // arrange
        Plant plant = stubPlant();
        CareHistory item = careHistoryEntry(plant, TaskType.WATERING);
        when(careHistoryService.getHistoryPageWithLimit(eq(PLANT_ID), eq(0), eq(20), isNull()))
                .thenReturn(List.of(item));
        when(careHistoryService.countHistory(eq(PLANT_ID), isNull())).thenReturn(1L);

        // act
        mockMvc.perform(get("/api/v1/plants/{id}/history", PLANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items").isArray());

        // assert — сервис вызван без фильтра по типу (null)
        verify(careHistoryService).getHistoryPageWithLimit(eq(PLANT_ID), eq(0), eq(20), isNull());
    }

    @Test
    void should_return_filtered_history_when_type_param_provided() throws Exception {
        // arrange
        Plant plant = stubPlant();
        CareHistory item = careHistoryEntry(plant, TaskType.WATERING);
        when(careHistoryService.getHistoryPageWithLimit(eq(PLANT_ID), eq(0), eq(20), eq(TaskType.WATERING)))
                .thenReturn(List.of(item));
        when(careHistoryService.countHistory(eq(PLANT_ID), eq(TaskType.WATERING))).thenReturn(1L);

        // act
        mockMvc.perform(get("/api/v1/plants/{id}/history", PLANT_ID)
                        .queryParam("type", "WATER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());

        // assert — сервис вызван с TaskType.WATERING
        verify(careHistoryService).getHistoryPageWithLimit(eq(PLANT_ID), eq(0), eq(20), eq(TaskType.WATERING));
    }

    @Test
    void should_return_400_when_unknown_type_param() throws Exception {
        // arrange — plant stub не нужен, Spring отклонит до входа в контроллер
        stubPlant();

        // act & assert
        mockMvc.perform(get("/api/v1/plants/{id}/history", PLANT_ID)
                        .queryParam("type", "INVALID"))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/plants/{id}/history/summary
    // -------------------------------------------------------------------------

    @Test
    void should_return_history_summary_with_all_fields() throws Exception {
        // arrange
        stubPlant();

        Map<TaskType, Long> byTypeDomain = new EnumMap<>(TaskType.class);
        byTypeDomain.put(TaskType.WATERING, 5L);
        byTypeDomain.put(TaskType.MISTING, 3L);
        byTypeDomain.put(TaskType.FERTILIZING, 2L);

        CareHistoryService.HistorySummary summary =
                new CareHistoryService.HistorySummary(10L, 8L, 80, byTypeDomain);

        when(careHistoryService.getHistorySummary(PLANT_ID)).thenReturn(summary);

        // act & assert
        mockMvc.perform(get("/api/v1/plants/{id}/history/summary", PLANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(10))
                .andExpect(jsonPath("$.onTimeCount").value(8))
                .andExpect(jsonPath("$.onTimePercent").value(80))
                .andExpect(jsonPath("$.byType.WATER").value(5))
                .andExpect(jsonPath("$.byType.SPRAY").value(3))
                .andExpect(jsonPath("$.byType.FERTILIZE").value(2));
    }

    @Test
    void should_return_404_for_summary_when_plant_not_found() throws Exception {
        // arrange
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(USER_ID, 999L))
                .thenReturn(Optional.empty());

        // act & assert
        mockMvc.perform(get("/api/v1/plants/{id}/history/summary", 999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private CareHistory careHistoryEntry(Plant plant, TaskType type) {
        CareHistory h = new CareHistory();
        h.setPlant(plant);
        h.setTaskType(type);
        h.setDoneAt(LocalDateTime.of(2026, 6, 1, 10, 0));
        h.setOnTime(true);
        return h;
    }
}
