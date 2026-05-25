package com.plantcare.bot.controller.api.v1;

import com.plantcare.bot.controller.api.ApiExceptionHandler;
import com.plantcare.bot.controller.api.UserApiResolver;
import com.plantcare.core.domain.CareSchedule;
import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.service.TodayApiService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Слайс-тест {@link TodayController}.
 *
 * <p>Ключевые сценарии:
 * <ul>
 *   <li>200 с задачами для корректного пользователя.</li>
 *   <li>400 при отсутствии заголовка X-Chat-Id.</li>
 *   <li>Проверка расчёта конца дня для не-UTC таймзоны (Asia/Almaty, UTC+5).</li>
 * </ul>
 */
@WebMvcTest(TodayController.class)
@Import(ApiExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class TodayControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TodayApiService todayApiService;

    @MockBean
    private UserApiResolver userApiResolver;

    // ===================== GET /api/v1/today =====================

    @Test
    void should_return_200_with_tasks_when_schedules_are_due() throws Exception {
        // arrange
        User user = mockUserWithTimezone(1L, 1L, "UTC");
        when(userApiResolver.resolve(1L)).thenReturn(user);

        CareSchedule schedule = mockSchedule(10L, 100L, "Монстера", TaskType.WATERING,
                LocalDateTime.now().plusHours(1));
        when(todayApiService.getTodaySchedules(anyLong(), anyString()))
                .thenReturn(List.of(schedule));

        // act + assert
        mockMvc.perform(get("/api/v1/today")
                        .header("X-Chat-Id", 1L))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.tasks").isArray())
                .andExpect(jsonPath("$.tasks.length()").value(1))
                .andExpect(jsonPath("$.tasks[0].plantName").value("Монстера"))
                .andExpect(jsonPath("$.tasks[0].taskType").value("WATERING"))
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void should_return_200_with_empty_tasks_when_no_schedules_due() throws Exception {
        // arrange
        User user = mockUserWithTimezone(2L, 2L, "UTC");
        when(userApiResolver.resolve(2L)).thenReturn(user);
        when(todayApiService.getTodaySchedules(anyLong(), anyString()))
                .thenReturn(List.of());

        // act + assert
        mockMvc.perform(get("/api/v1/today")
                        .header("X-Chat-Id", 2L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks").isArray())
                .andExpect(jsonPath("$.tasks.length()").value(0))
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void should_return_400_when_x_chat_id_header_missing() throws Exception {
        // act + assert
        mockMvc.perform(get("/api/v1/today"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void should_compute_end_of_day_correctly_for_non_utc_timezone() {
        // arrange — таймзона Asia/Almaty = UTC+5
        // Конец дня в Алматы = 23:59:59+05:00 = 18:59:59 UTC
        ZoneId almaty = ZoneId.of("Asia/Almaty");
        LocalDate today = LocalDate.of(2026, 5, 22);

        ZonedDateTime endOfDayInAlmaty = today.atTime(LocalTime.of(23, 59, 59)).atZone(almaty);
        LocalDateTime expectedUtc = endOfDayInAlmaty
                .withZoneSameInstant(ZoneId.of("UTC"))
                .toLocalDateTime();

        // assert — 23:59:59 Almaty time = 18:59:59 UTC (UTC+5, смещение -5 часов)
        assertThat(expectedUtc.getHour()).isEqualTo(18);
        assertThat(expectedUtc.getMinute()).isEqualTo(59);
        assertThat(expectedUtc.getSecond()).isEqualTo(59);
    }

    @Test
    void should_compute_end_of_day_correctly_for_moscow_timezone() {
        // arrange — таймзона Europe/Moscow = UTC+3
        // Конец дня в Москве = 23:59:59+03:00 = 20:59:59 UTC
        ZoneId moscow = ZoneId.of("Europe/Moscow");
        LocalDate today = LocalDate.of(2026, 5, 22);

        ZonedDateTime endOfDayInMoscow = today.atTime(LocalTime.of(23, 59, 59)).atZone(moscow);
        LocalDateTime expectedUtc = endOfDayInMoscow
                .withZoneSameInstant(ZoneId.of("UTC"))
                .toLocalDateTime();

        // assert
        assertThat(expectedUtc.getHour()).isEqualTo(20);
        assertThat(expectedUtc.getMinute()).isEqualTo(59);
        assertThat(expectedUtc.getSecond()).isEqualTo(59);
    }

    @Test
    void should_return_multiple_tasks_for_user_with_several_plants() throws Exception {
        // arrange
        User user = mockUserWithTimezone(3L, 3L, "Europe/Moscow");
        when(userApiResolver.resolve(3L)).thenReturn(user);

        CareSchedule watering = mockSchedule(11L, 101L, "Фикус", TaskType.WATERING,
                LocalDateTime.now().plusHours(2));
        CareSchedule misting = mockSchedule(12L, 102L, "Орхидея", TaskType.MISTING,
                LocalDateTime.now().plusHours(3));
        when(todayApiService.getTodaySchedules(anyLong(), anyString()))
                .thenReturn(List.of(watering, misting));

        // act + assert
        mockMvc.perform(get("/api/v1/today")
                        .header("X-Chat-Id", 3L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(2))
                .andExpect(jsonPath("$.count").value(2));
    }

    // ===================== helpers =====================

    private User mockUserWithTimezone(Long id, Long chatId, String timezone) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(id);
        when(user.getTelegramChatId()).thenReturn(chatId);
        when(user.getTimezone()).thenReturn(timezone);
        return user;
    }

    private CareSchedule mockSchedule(Long scheduleId, Long plantId, String plantName,
                                       TaskType taskType, LocalDateTime nextDueAt) {
        Location location = mock(Location.class);
        when(location.getName()).thenReturn("Гостиная");

        Plant plant = mock(Plant.class);
        when(plant.getId()).thenReturn(plantId);
        when(plant.getName()).thenReturn(plantName);
        when(plant.getLocation()).thenReturn(location);

        CareSchedule schedule = mock(CareSchedule.class);
        when(schedule.getId()).thenReturn(scheduleId);
        when(schedule.getPlant()).thenReturn(plant);
        when(schedule.getTaskType()).thenReturn(taskType);
        when(schedule.getNextDueAt()).thenReturn(nextDueAt);
        return schedule;
    }
}
