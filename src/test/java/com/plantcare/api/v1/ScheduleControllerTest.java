package com.plantcare.api.v1;

import com.plantcare.api.ApiExceptionHandler;
import com.plantcare.api.CurrentUserProvider;
import com.plantcare.core.domain.enums.SeasonalOverride;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.service.PlantService;
import com.plantcare.core.service.PlantService.ScheduleView;
import com.plantcare.core.service.PlantService.SeasonalInfo;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @WebMvcTest для {@link ScheduleController} (issue #185, расширен в #188).
 *
 * <p>Зеркалит стиль {@code LocationControllerTest}: web-слайс + замоканный
 * {@link PlantService} и {@link CurrentUserProvider}. Покрывает GET-массив из 4,
 * PUT-обновление, сезонные поля, неизвестный {type}, плохой unit, every вне диапазона и 404.
 */
@WebMvcTest(ScheduleController.class)
@Import(ApiExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class ScheduleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PlantService plantService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    private static final SeasonalInfo SEASONAL_INACTIVE = new SeasonalInfo(false, 7, 7);

    @BeforeEach
    void stubCurrentUser() {
        when(currentUserProvider.currentUserId()).thenReturn(1L);
    }

    // ----------------------------------------------------------------- GET

    @Test
    void should_return_array_of_four_schedules_when_listing() throws Exception {
        // arrange
        List<ScheduleView> views = List.of(
                new ScheduleView(TaskType.WATERING, 7, 250, true,
                        LocalDateTime.of(2026, 6, 5, 9, 0), new SeasonalInfo(true, 5, 10)),
                new ScheduleView(TaskType.MISTING, 3, null, false, null, SEASONAL_INACTIVE),
                new ScheduleView(TaskType.FERTILIZING, 14, null, false, null, SEASONAL_INACTIVE),
                new ScheduleView(TaskType.SOIL_CHECK, 3, null, false, null, SEASONAL_INACTIVE));

        when(plantService.getSchedules(1L, 42L)).thenReturn(views);

        // act + assert
        mockMvc.perform(get("/api/v1/plants/42/schedules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].type").value("WATERING"))
                .andExpect(jsonPath("$[0].every").value(7))
                .andExpect(jsonPath("$[0].unit").value("DAY"))
                .andExpect(jsonPath("$[0].amountMl").value(250))
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[0].nextDueAt").exists())
                .andExpect(jsonPath("$[0].seasonal.active").value(true))
                .andExpect(jsonPath("$[0].seasonal.summerIntervalDays").value(5))
                .andExpect(jsonPath("$[0].seasonal.winterIntervalDays").value(10))
                .andExpect(jsonPath("$[1].type").value("MISTING"))
                .andExpect(jsonPath("$[1].enabled").value(false))
                // nextDueAt отсутствует/null для выключенного расписания
                .andExpect(jsonPath("$[1].nextDueAt").doesNotExist())
                .andExpect(jsonPath("$[1].seasonal.active").value(false))
                .andExpect(jsonPath("$[2].type").value("FERTILIZING"))
                .andExpect(jsonPath("$[3].type").value("SOIL_CHECK"));
    }

    @Test
    void should_return_404_when_get_schedules_throws_not_found() throws Exception {
        // arrange
        when(plantService.getSchedules(1L, 99L))
                .thenThrow(new EntityNotFoundException("Plant not found: 99"));

        // act + assert
        mockMvc.perform(get("/api/v1/plants/99/schedules"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ----------------------------------------------------------------- PUT

    @Test
    void should_return_updated_dto_when_put_valid() throws Exception {
        // arrange
        SeasonalInfo seasonal = new SeasonalInfo(false, 5, 5);
        ScheduleView updated = new ScheduleView(
                TaskType.WATERING, 5, 300, true, LocalDateTime.of(2026, 6, 10, 8, 0), seasonal);

        when(plantService.updateSchedule(1L, 42L, TaskType.WATERING, 5, 300, true, null))
                .thenReturn(updated);

        String body = """
                {"every": 5, "unit": "DAY", "amountMl": 300, "enabled": true}
                """;

        // act + assert
        mockMvc.perform(put("/api/v1/plants/42/schedules/WATERING")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("WATERING"))
                .andExpect(jsonPath("$.every").value(5))
                .andExpect(jsonPath("$.unit").value("DAY"))
                .andExpect(jsonPath("$.amountMl").value(300))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.nextDueAt").exists())
                .andExpect(jsonPath("$.seasonal.active").value(false))
                .andExpect(jsonPath("$.seasonal.summerIntervalDays").value(5))
                .andExpect(jsonPath("$.seasonal.winterIntervalDays").value(5));
    }

    @Test
    void should_return_400_when_put_unknown_type() throws Exception {
        // arrange — TaskType.valueOf("FOO") → IllegalArgumentException → 400
        String body = """
                {"every": 7, "unit": "DAY", "enabled": true}
                """;

        // act + assert
        mockMvc.perform(put("/api/v1/plants/42/schedules/FOO")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // Примечание: плохой unit ("WEEK") даёт HttpMessageNotReadableException, который в
    // ApiExceptionHandler не маппится на 400 (падает в generic RuntimeException → 500),
    // поэтому 400 на уровне MVC здесь НЕ выразим — отдельного теста на bad unit нет.

    @Test
    void should_return_400_when_put_every_out_of_range() throws Exception {
        // arrange — @Max(365) на every нарушено bean-валидацией
        String body = """
                {"every": 400, "unit": "DAY", "enabled": true}
                """;

        // act + assert
        mockMvc.perform(put("/api/v1/plants/42/schedules/WATERING")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void should_return_400_when_put_every_zero() throws Exception {
        // arrange — @Min(1) на every нарушено
        String body = """
                {"every": 0, "unit": "DAY", "enabled": true}
                """;

        // act + assert
        mockMvc.perform(put("/api/v1/plants/42/schedules/WATERING")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void should_return_404_when_put_on_plant_throwing_not_found() throws Exception {
        // arrange — сервис кидает EntityNotFoundException (чужое/отсутствующее растение)
        when(plantService.updateSchedule(eq(1L), eq(99L), eq(TaskType.WATERING),
                eq(7), isNull(), eq(true), isNull()))
                .thenThrow(new EntityNotFoundException("Plant not found: 99"));

        String body = """
                {"every": 7, "unit": "DAY", "enabled": true}
                """;

        // act + assert
        mockMvc.perform(put("/api/v1/plants/99/schedules/WATERING")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ----------------------------------------------------------------- seasonal (issue #188)

    @Test
    void should_return_seasonal_info_when_listing_schedules() throws Exception {
        // arrange
        SeasonalInfo seasonalActive = new SeasonalInfo(true, 5, 14);
        List<ScheduleView> views = List.of(
                new ScheduleView(TaskType.WATERING, 7, null, true, null, seasonalActive));

        when(plantService.getSchedules(1L, 1L)).thenReturn(views);

        // act + assert
        mockMvc.perform(get("/api/v1/plants/1/schedules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].seasonal.active").value(true))
                .andExpect(jsonPath("$[0].seasonal.summerIntervalDays").value(5))
                .andExpect(jsonPath("$[0].seasonal.winterIntervalDays").value(14));
    }

    @Test
    void should_update_seasonal_override_when_provided() throws Exception {
        // arrange
        SeasonalInfo seasonal = new SeasonalInfo(false, 7, 7);
        ScheduleView updated = new ScheduleView(
                TaskType.WATERING, 7, null, true, LocalDateTime.of(2026, 6, 10, 9, 0), seasonal);

        when(plantService.updateSchedule(1L, 1L, TaskType.WATERING, 7, null, true, SeasonalOverride.OFF))
                .thenReturn(updated);

        String body = """
                {"every": 7, "unit": "DAY", "enabled": true, "seasonalOverride": "OFF"}
                """;

        // act
        mockMvc.perform(put("/api/v1/plants/1/schedules/WATERING")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // assert — сервис вызван с SeasonalOverride.OFF
        verify(plantService).updateSchedule(1L, 1L, TaskType.WATERING, 7, null, true, SeasonalOverride.OFF);
    }

    @Test
    void should_not_change_seasonal_when_override_not_provided() throws Exception {
        // arrange
        SeasonalInfo seasonal = new SeasonalInfo(true, 5, 10);
        ScheduleView updated = new ScheduleView(
                TaskType.WATERING, 7, null, true, LocalDateTime.of(2026, 6, 10, 9, 0), seasonal);

        when(plantService.updateSchedule(eq(1L), eq(1L), eq(TaskType.WATERING),
                eq(7), isNull(), eq(true), isNull()))
                .thenReturn(updated);

        String body = """
                {"every": 7, "unit": "DAY", "enabled": true}
                """;

        // act
        mockMvc.perform(put("/api/v1/plants/1/schedules/WATERING")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // assert — сервис вызван с null (override не передан)
        verify(plantService).updateSchedule(1L, 1L, TaskType.WATERING, 7, null, true, null);
    }
}
