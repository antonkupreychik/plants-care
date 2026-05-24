package com.plantcare.bot.controller.api.v1;

import com.plantcare.bot.controller.api.ApiExceptionHandler;
import com.plantcare.bot.controller.api.UserApiResolver;
import com.plantcare.bot.domain.CareSchedule;
import com.plantcare.bot.domain.Location;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.TaskType;
import com.plantcare.bot.service.CalendarApiService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
 * Слайс-тест {@link CalendarController}.
 *
 * <p>Проверяем HTTP-контракт:
 * <ul>
 *   <li>200 с корректно сгруппированной по датам структурой.</li>
 *   <li>400 при диапазоне > 60 дней.</li>
 *   <li>400 при отсутствии заголовка X-Chat-Id.</li>
 *   <li>Сортировка по дате (TreeMap гарантирует восходящий порядок).</li>
 * </ul>
 */
@WebMvcTest(CalendarController.class)
@Import(ApiExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class CalendarControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CalendarApiService calendarApiService;

    @MockBean
    private UserApiResolver userApiResolver;

    // ===================== GET /api/v1/calendar =====================

    @Test
    void should_return_200_with_grouped_tasks_when_valid_range() throws Exception {
        // arrange — расписание с nextDueAt = 2026-01-03, interval = 7 дней
        // В окне 2026-01-01..2026-01-07 попадает ровно 2026-01-03
        User user = mockUserWithTimezone(1L, 1L, "UTC");
        when(userApiResolver.resolve(1L)).thenReturn(user);

        CareSchedule schedule = mockSchedule(
                10L, 100L, "Монстера", TaskType.WATERING,
                LocalDateTime.parse("2026-01-03T00:00:00"), 7);
        when(calendarApiService.getActiveSchedules(anyLong()))
                .thenReturn(List.of(schedule));
        when(calendarApiService.projectEvents(any(CareSchedule.class), any(LocalDate.class), any(LocalDate.class), anyString()))
                .thenReturn(List.of(LocalDate.parse("2026-01-03")));

        // act + assert
        mockMvc.perform(get("/api/v1/calendar")
                        .header("X-Chat-Id", 1L)
                        .param("from", "2026-01-01")
                        .param("to", "2026-01-07"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$['2026-01-03']").isArray())
                .andExpect(jsonPath("$['2026-01-03'].length()").value(1))
                .andExpect(jsonPath("$['2026-01-03'][0].plantName").value("Монстера"))
                .andExpect(jsonPath("$['2026-01-03'][0].taskType").value("WATERING"));
    }

    @Test
    void should_return_400_when_range_exceeds_60_days() throws Exception {
        // arrange
        User user = mockUserWithTimezone(2L, 2L, "UTC");
        when(userApiResolver.resolve(2L)).thenReturn(user);
        when(calendarApiService.getActiveSchedules(anyLong()))
                .thenReturn(List.of());

        // act + assert — диапазон 61 день → ошибка
        mockMvc.perform(get("/api/v1/calendar")
                        .header("X-Chat-Id", 2L)
                        .param("from", "2026-01-01")
                        .param("to", "2026-03-03"))  // 61 день
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("60 days"));
    }

    @Test
    void should_return_400_when_x_chat_id_header_missing() throws Exception {
        // act + assert
        mockMvc.perform(get("/api/v1/calendar")
                        .param("from", "2026-01-01")
                        .param("to", "2026-01-07"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void should_return_empty_map_when_no_schedules_in_range() throws Exception {
        // arrange — расписания есть, но все nextDueAt за пределами окна
        User user = mockUserWithTimezone(3L, 3L, "UTC");
        when(userApiResolver.resolve(3L)).thenReturn(user);
        when(calendarApiService.getActiveSchedules(anyLong()))
                .thenReturn(List.of());

        // act + assert
        mockMvc.perform(get("/api/v1/calendar")
                        .header("X-Chat-Id", 3L)
                        .param("from", "2026-01-01")
                        .param("to", "2026-01-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isMap())
                // пустая Map → пустой JSON объект {}
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .isEqualTo("{}"));
    }

    @Test
    void should_return_dates_in_ascending_order_when_multiple_dates_in_range() throws Exception {
        // arrange — два расписания: одно на 2026-01-05, другое на 2026-01-02
        User user = mockUserWithTimezone(4L, 4L, "UTC");
        when(userApiResolver.resolve(4L)).thenReturn(user);

        CareSchedule scheduleA = mockSchedule(
                11L, 101L, "Фикус", TaskType.WATERING,
                LocalDateTime.parse("2026-01-05T00:00:00"), 30);
        CareSchedule scheduleB = mockSchedule(
                12L, 102L, "Алоэ", TaskType.MISTING,
                LocalDateTime.parse("2026-01-02T00:00:00"), 30);
        when(calendarApiService.getActiveSchedules(anyLong()))
                .thenReturn(List.of(scheduleA, scheduleB));
        when(calendarApiService.projectEvents(any(CareSchedule.class), any(LocalDate.class), any(LocalDate.class), anyString()))
                .thenAnswer(invocation -> {
                    CareSchedule s = invocation.getArgument(0);
                    if (s.getId().equals(11L)) return List.of(LocalDate.parse("2026-01-05"));
                    if (s.getId().equals(12L)) return List.of(LocalDate.parse("2026-01-02"));
                    return List.of();
                });

        // act
        MvcResult result = mockMvc.perform(get("/api/v1/calendar")
                        .header("X-Chat-Id", 4L)
                        .param("from", "2026-01-01")
                        .param("to", "2026-01-07"))
                .andExpect(status().isOk())
                .andReturn();

        // assert — TreeMap гарантирует порядок ключей; "2026-01-02" должна идти раньше "2026-01-05"
        String body = result.getResponse().getContentAsString();
        int indexJan02 = body.indexOf("2026-01-02");
        int indexJan05 = body.indexOf("2026-01-05");
        assertThat(indexJan02).isLessThan(indexJan05);
    }

    @Test
    void should_group_multiple_tasks_on_same_day_when_multiple_schedules_due() throws Exception {
        // arrange — два расписания с одной датой
        User user = mockUserWithTimezone(5L, 5L, "UTC");
        when(userApiResolver.resolve(5L)).thenReturn(user);

        CareSchedule watering = mockSchedule(
                13L, 103L, "Монстера", TaskType.WATERING,
                LocalDateTime.parse("2026-01-03T00:00:00"), 30);
        CareSchedule misting = mockSchedule(
                14L, 103L, "Монстера", TaskType.MISTING,
                LocalDateTime.parse("2026-01-03T12:00:00"), 30);
        when(calendarApiService.getActiveSchedules(anyLong()))
                .thenReturn(List.of(watering, misting));
        when(calendarApiService.projectEvents(any(CareSchedule.class), any(LocalDate.class), any(LocalDate.class), anyString()))
                .thenReturn(List.of(LocalDate.parse("2026-01-03")));

        // act + assert — оба задания на один день
        mockMvc.perform(get("/api/v1/calendar")
                        .header("X-Chat-Id", 5L)
                        .param("from", "2026-01-01")
                        .param("to", "2026-01-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['2026-01-03'].length()").value(2));
    }

    @Test
    void should_return_200_when_range_is_exactly_60_days() throws Exception {
        // arrange — граничное значение: ровно 60 дней
        User user = mockUserWithTimezone(6L, 6L, "UTC");
        when(userApiResolver.resolve(6L)).thenReturn(user);
        when(calendarApiService.getActiveSchedules(anyLong()))
                .thenReturn(List.of());

        // act + assert — 60 дней — допустимо
        mockMvc.perform(get("/api/v1/calendar")
                        .header("X-Chat-Id", 6L)
                        .param("from", "2026-01-01")
                        .param("to", "2026-03-02"))  // 60 дней
                .andExpect(status().isOk());
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
                                       TaskType taskType, LocalDateTime nextDueAt, int intervalDays) {
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
        when(schedule.getIntervalDays()).thenReturn(intervalDays);
        when(schedule.isActive()).thenReturn(true);
        return schedule;
    }
}
