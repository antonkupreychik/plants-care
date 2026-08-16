package com.plantcare.api.v1;

import com.plantcare.api.ApiExceptionHandler;
import com.plantcare.api.CurrentUserProvider;
import com.plantcare.api.auth.exception.AuthTokenException;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.service.MonthlyReportApiService;
import com.plantcare.core.service.MonthlyReportApiService.MonthlyReport;
import com.plantcare.core.service.MonthlyReportApiService.WeeklyHealthBucket;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Слайс-тест {@link ReportsController} (issue #192).
 *
 * <p>Проверяет JSON-форму ответа {@code GET /api/v1/reports/monthly}, скоуп по
 * текущему пользователю (id и TZ берутся из {@link CurrentUserProvider}, не из
 * запроса) и маппинг ошибок: 400 при невалидном месяце, 401 без аутентификации.
 *
 * <p>Сервис замокан — здесь тестируется только веб-слой и сериализация.
 */
@WebMvcTest(ReportsController.class)
@Import(ApiExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class ReportsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MonthlyReportApiService monthlyReportApiService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @Test
    void should_return_200_with_full_report_shape_when_month_valid() throws Exception {
        // arrange
        User user = mockUser(42L, "Europe/Moscow");
        when(currentUserProvider.currentUser()).thenReturn(user);

        Map<TaskType, Long> byType = new LinkedHashMap<>();
        byType.put(TaskType.WATERING, 20L);
        byType.put(TaskType.MISTING, 15L);
        byType.put(TaskType.FERTILIZING, 5L);
        byType.put(TaskType.SOIL_CHECK, 0L);

        MonthlyReport report = new MonthlyReport(
                "2026-04", 40L, 5L, byType, 12,
                List.of(new WeeklyHealthBucket("2026-W15", 18L, 0.67),
                        new WeeklyHealthBucket("2026-W16", 22L, 1.0)));
        when(monthlyReportApiService.getMonthlyReport(anyLong(), anyString(), anyString()))
                .thenReturn(report);

        // act + assert
        mockMvc.perform(get("/api/v1/reports/monthly").param("month", "2026-04"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.month").value("2026-04"))
                .andExpect(jsonPath("$.done").value(40))
                .andExpect(jsonPath("$.overdue").value(5))
                .andExpect(jsonPath("$.streak").value(12))
                .andExpect(jsonPath("$.byType.WATERING").value(20))
                .andExpect(jsonPath("$.byType.MISTING").value(15))
                .andExpect(jsonPath("$.byType.FERTILIZING").value(5))
                .andExpect(jsonPath("$.byType.SOIL_CHECK").value(0))
                .andExpect(jsonPath("$.healthTrend.length()").value(2))
                .andExpect(jsonPath("$.healthTrend[0].week").value("2026-W15"))
                .andExpect(jsonPath("$.healthTrend[0].done").value(18))
                .andExpect(jsonPath("$.healthTrend[0].onTimePct").value(0.67))
                .andExpect(jsonPath("$.healthTrend[1].week").value("2026-W16"))
                .andExpect(jsonPath("$.healthTrend[1].onTimePct").value(1.0));
    }

    @Test
    void should_pass_current_user_id_and_timezone_not_request_when_building_report() throws Exception {
        // arrange — скоуп берётся из CurrentUserProvider, не из параметров запроса
        User user = mockUser(99L, "Asia/Almaty");
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(monthlyReportApiService.getMonthlyReport(anyLong(), anyString(), anyString()))
                .thenReturn(emptyReport());

        // act
        mockMvc.perform(get("/api/v1/reports/monthly").param("month", "2026-04"))
                .andExpect(status().isOk());

        // assert — сервис вызван именно с id и TZ текущего пользователя
        verify(monthlyReportApiService).getMonthlyReport(eq(99L), eq("Asia/Almaty"), eq("2026-04"));
    }

    @Test
    void should_return_400_when_month_value_is_invalid() throws Exception {
        // arrange — "2026-13" проходит regex ^\d{4}-\d{2}$, но сервис кидает IllegalArgumentException
        User user = mockUser(42L, "UTC");
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(monthlyReportApiService.getMonthlyReport(anyLong(), anyString(), eq("2026-13")))
                .thenThrow(new IllegalArgumentException("Invalid 'month' format, expected YYYY-MM: 2026-13"));

        // act + assert
        mockMvc.perform(get("/api/v1/reports/monthly").param("month", "2026-13"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
    }

    @Test
    void should_reject_pattern_violating_month_before_reaching_service() throws Exception {
        // arrange — "garbage" не проходит @Pattern(^\d{4}-\d{2}$) на @RequestParam.
        // Нарушение @Pattern на query-параметре поднимает HandlerMethodValidationException,
        // которую ApiExceptionHandler маппит в 400 VALIDATION_ERROR. Невалидный параметр
        // отбивается ДО бизнес-логики (сервис не вызывается).
        User user = mockUser(42L, "UTC");
        when(currentUserProvider.currentUser()).thenReturn(user);

        // act + assert
        mockMvc.perform(get("/api/v1/reports/monthly").param("month", "garbage"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        verify(monthlyReportApiService, never()).getMonthlyReport(anyLong(), anyString(), anyString());
    }

    @Test
    void should_return_401_when_no_authenticated_user() throws Exception {
        // arrange — нет JWT в SecurityContext
        when(currentUserProvider.currentUser())
                .thenThrow(AuthTokenException.invalid("No authenticated user in security context"));

        // act + assert
        mockMvc.perform(get("/api/v1/reports/monthly").param("month", "2026-04"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_INVALID"));
    }

    // ===================== helpers =====================

    private User mockUser(Long id, String timezone) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(id);
        when(user.getTimezone()).thenReturn(timezone);
        return user;
    }

    private MonthlyReport emptyReport() {
        Map<TaskType, Long> byType = new LinkedHashMap<>();
        for (TaskType type : TaskType.values()) {
            byType.put(type, 0L);
        }
        return new MonthlyReport("2026-04", 0L, 0L, byType, 0, List.of());
    }
}
