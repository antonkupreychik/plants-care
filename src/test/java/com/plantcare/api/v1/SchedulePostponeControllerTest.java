package com.plantcare.api.v1;

import com.plantcare.api.ApiExceptionHandler;
import com.plantcare.api.CurrentUserProvider;
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

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} для {@link ScheduleController#postponePlantSchedule} (issue #257).
 *
 * <p>REST-parity gap #5: разовый перенос ближайшего срабатывания расписания.
 * Аналог кнопки «Отложить на N дней» в Telegram-боте ({@code PLANT:SCHED:POSTPONE}).
 * Базовый интервал ({@code every}) не меняется.
 */
@WebMvcTest(ScheduleController.class)
@Import(ApiExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class SchedulePostponeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PlantService plantService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    private static final SeasonalInfo SEASONAL_INACTIVE = new SeasonalInfo(false, 7, 7);

    @BeforeEach
    void setUp() {
        when(currentUserProvider.currentUserId()).thenReturn(1L);
    }

    @Test
    void should_return_updated_schedule_when_valid_postpone() throws Exception {
        // arrange
        ScheduleView view = new ScheduleView(
                TaskType.WATERING, 7, 250, true,
                LocalDateTime.of(2026, 6, 10, 9, 0),
                SEASONAL_INACTIVE);

        when(plantService.postponeSchedule(1L, 42L, TaskType.WATERING, 3)).thenReturn(view);

        // act + assert
        mockMvc.perform(post("/api/v1/plants/42/schedules/WATERING/postpone")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"days\": 3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("WATERING"))
                .andExpect(jsonPath("$.every").value(7))
                .andExpect(jsonPath("$.unit").value("DAY"))
                .andExpect(jsonPath("$.amountMl").value(250))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.nextDueAt").exists());

        verify(plantService).postponeSchedule(1L, 42L, TaskType.WATERING, 3);
    }

    @Test
    void should_return_400_when_days_zero() throws Exception {
        // arrange — @Min(1) на days нарушено Bean Validation
        mockMvc.perform(post("/api/v1/plants/42/schedules/WATERING/postpone")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"days\": 0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void should_return_400_when_days_exceeds_365() throws Exception {
        // arrange — @Max(365) на days нарушено Bean Validation
        mockMvc.perform(post("/api/v1/plants/42/schedules/WATERING/postpone")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"days\": 400}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void should_return_400_when_unknown_schedule_type() throws Exception {
        // arrange — TaskType.valueOf("UNKNOWN") → IllegalArgumentException → 400
        mockMvc.perform(post("/api/v1/plants/42/schedules/UNKNOWN/postpone")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"days\": 3}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void should_return_404_when_plant_not_found() throws Exception {
        // arrange — сервис бросает EntityNotFoundException
        when(plantService.postponeSchedule(1L, 999L, TaskType.WATERING, 3))
                .thenThrow(new EntityNotFoundException("Plant not found: 999"));

        // act + assert
        mockMvc.perform(post("/api/v1/plants/999/schedules/WATERING/postpone")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"days\": 3}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void should_return_400_when_schedule_not_configured() throws Exception {
        // arrange — сервис бросает IllegalArgumentException (расписание не настроено) → 400
        when(plantService.postponeSchedule(1L, 42L, TaskType.MISTING, 5))
                .thenThrow(new IllegalArgumentException("Расписание MISTING не настроено"));

        // act + assert
        mockMvc.perform(post("/api/v1/plants/42/schedules/MISTING/postpone")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"days\": 5}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void should_return_nextDueAt_as_utc_when_schedule_found() throws Exception {
        // arrange — Тест с не-UTC wall-clock time: убеждаемся что ZoneOffset.UTC применён.
        // Это важно для корректного отображения в мобайле (все даты в UTC).
        ScheduleView view = new ScheduleView(
                TaskType.FERTILIZING, 14, null, true,
                LocalDateTime.of(2026, 6, 15, 10, 30),    // wall-clock in БД (без TZ)
                SEASONAL_INACTIVE);

        when(plantService.postponeSchedule(1L, 42L, TaskType.FERTILIZING, 7))
                .thenReturn(view);

        // act + assert — nextDueAt должен отдаваться как ISO-8601 UTC
        mockMvc.perform(post("/api/v1/plants/42/schedules/FERTILIZING/postpone")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"days\": 7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextDueAt").value("2026-06-15T10:30:00Z"));
    }
}
