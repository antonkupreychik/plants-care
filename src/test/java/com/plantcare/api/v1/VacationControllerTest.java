package com.plantcare.api.v1;

import com.plantcare.api.ApiExceptionHandler;
import com.plantcare.api.CurrentUserProvider;
import com.plantcare.api.auth.exception.AuthTokenException;
import com.plantcare.core.domain.User;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Слайс-тест {@link VacationController} (issue #189).
 *
 * <p>Проверяет веб-слой: маршрутизацию, формат запроса/ответа, Bean Validation,
 * маппинг ошибок и делегирование {@link UserService}. Сервис и провайдер замоканы.
 */
@WebMvcTest(VacationController.class)
@Import(ApiExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class VacationControllerTest {

    /** Фиксированный момент: 2026-06-04T12:00:00Z. */
    private static final Instant FIXED_NOW = Instant.parse("2026-06-04T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    /** Фиксированные часы для детерминированного active-check. */
    @MockitoBean
    private Clock clock;

    private User mockUser;

    @BeforeEach
    void stubCurrentUser() {
        mockUser = mock(User.class);
        when(mockUser.getId()).thenReturn(42L);
        when(currentUserProvider.currentUser()).thenReturn(mockUser);
        // Фиксируем часы для детерминированного active-check в VacationController
        when(clock.instant()).thenReturn(FIXED_NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    // ------------------------------------------------------------------ GET happy

    @Test
    void should_return_active_vacation_status_when_paused_until_is_in_the_future() throws Exception {
        // arrange — paused_until в будущем (2026-06-14 23:59:59 UTC)
        LocalDateTime future = LocalDateTime.of(2026, 6, 14, 23, 59, 59);
        when(mockUser.getPausedUntil()).thenReturn(future);

        // act + assert
        mockMvc.perform(get("/api/v1/vacation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.pausedUntil").exists());
    }

    @Test
    void should_return_inactive_vacation_status_when_paused_until_is_null() throws Exception {
        // arrange — отпуск не активен
        when(mockUser.getPausedUntil()).thenReturn(null);

        // act + assert
        mockMvc.perform(get("/api/v1/vacation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.pausedUntil").doesNotExist());
    }

    @Test
    void should_return_inactive_when_paused_until_is_in_the_past() throws Exception {
        // arrange — paused_until в прошлом (отпуск завершён)
        LocalDateTime past = LocalDateTime.of(2026, 6, 3, 10, 0, 0);
        when(mockUser.getPausedUntil()).thenReturn(past);

        // act + assert
        mockMvc.perform(get("/api/v1/vacation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    // ------------------------------------------------------------------ POST happy

    @Test
    void should_start_vacation_and_return_active_response_when_valid_date_range() throws Exception {
        // arrange — сервис возвращает юзера с paused_until в будущем
        LocalDateTime pausedUntil = LocalDateTime.of(2026, 6, 14, 23, 59, 59);
        User updatedUser = mock(User.class);
        when(updatedUser.getPausedUntil()).thenReturn(pausedUntil);
        when(userService.startVacationByDateRange(any(User.class), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(updatedUser);

        String body = """
                {"from": "2026-06-01", "to": "2026-06-14"}
                """;

        // act + assert
        mockMvc.perform(post("/api/v1/vacation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.pausedUntil").exists());

        // Проверяем, что в сервис ушли правильные даты
        verify(userService).startVacationByDateRange(
                any(User.class),
                eq(LocalDate.of(2026, 6, 1)),
                eq(LocalDate.of(2026, 6, 14))
        );
    }

    @Test
    void should_return_400_when_to_is_before_from() throws Exception {
        // arrange — сервис кидает IllegalArgumentException на «to < from»
        when(userService.startVacationByDateRange(any(User.class), any(LocalDate.class), any(LocalDate.class)))
                .thenThrow(new IllegalArgumentException("'to' date must not be before 'from' date"));

        String body = """
                {"from": "2026-06-14", "to": "2026-06-01"}
                """;

        // act + assert
        mockMvc.perform(post("/api/v1/vacation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
    }

    @Test
    void should_return_400_when_duration_exceeds_60_days() throws Exception {
        // arrange — сервис кидает IllegalArgumentException на превышение лимита
        when(userService.startVacationByDateRange(any(User.class), any(LocalDate.class), any(LocalDate.class)))
                .thenThrow(new IllegalArgumentException("Vacation duration must not exceed 60 days"));

        String body = """
                {"from": "2026-06-01", "to": "2026-09-01"}
                """;

        // act + assert
        mockMvc.perform(post("/api/v1/vacation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
    }

    @Test
    void should_return_400_validation_error_when_from_is_missing() throws Exception {
        // arrange — 'from' обязателен по спеке (@NotNull на DTO)
        String body = """
                {"to": "2026-06-14"}
                """;

        // act + assert — Bean Validation отбивает до сервиса
        mockMvc.perform(post("/api/v1/vacation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(userService, never()).startVacationByDateRange(any(), any(), any());
    }

    @Test
    void should_return_400_validation_error_when_to_is_missing() throws Exception {
        // arrange — 'to' обязателен по спеке (@NotNull на DTO)
        String body = """
                {"from": "2026-06-01"}
                """;

        // act + assert
        mockMvc.perform(post("/api/v1/vacation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(userService, never()).startVacationByDateRange(any(), any(), any());
    }

    @Test
    void should_return_400_when_date_format_is_invalid() throws Exception {
        // arrange — некорректный формат даты
        String body = """
                {"from": "01-06-2026", "to": "14-06-2026"}
                """;

        // act + assert — Jackson не может распарсить дату → 400
        mockMvc.perform(post("/api/v1/vacation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(userService, never()).startVacationByDateRange(any(), any(), any());
    }

    // ------------------------------------------------------------------ DELETE happy

    @Test
    void should_return_204_when_vacation_is_ended() throws Exception {
        // arrange — endVacation возвращает юзера (не используем ответ)
        when(userService.endVacation(any(User.class))).thenReturn(mockUser);

        // act + assert
        mockMvc.perform(delete("/api/v1/vacation"))
                .andExpect(status().isNoContent());

        verify(userService).endVacation(mockUser);
    }

    @Test
    void should_return_204_when_vacation_is_already_inactive_idempotent() throws Exception {
        // arrange — отпуск неактивен, endVacation идемпотентна (ставит null → null)
        when(mockUser.getPausedUntil()).thenReturn(null);
        when(userService.endVacation(any(User.class))).thenReturn(mockUser);

        // act + assert — 204 в любом случае
        mockMvc.perform(delete("/api/v1/vacation"))
                .andExpect(status().isNoContent());

        verify(userService).endVacation(mockUser);
    }

    // ------------------------------------------------------------------ Auth

    @Test
    void should_return_401_when_no_authenticated_user_on_get() throws Exception {
        // arrange — нет JWT
        when(currentUserProvider.currentUser())
                .thenThrow(AuthTokenException.invalid("No authenticated user in security context"));

        // act + assert
        mockMvc.perform(get("/api/v1/vacation"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_INVALID"));
    }

    @Test
    void should_return_401_when_no_authenticated_user_on_post() throws Exception {
        // arrange — нет JWT
        when(currentUserProvider.currentUser())
                .thenThrow(AuthTokenException.invalid("No authenticated user in security context"));

        String body = """
                {"from": "2026-06-01", "to": "2026-06-14"}
                """;

        // act + assert
        mockMvc.perform(post("/api/v1/vacation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_INVALID"));
    }

}
