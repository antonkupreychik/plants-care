package com.plantcare.bot.controller.api.v1;

import com.plantcare.bot.api.generated.CalendarApi;
import com.plantcare.bot.api.generated.model.TaskDto;
import com.plantcare.core.domain.CareSchedule;
import com.plantcare.core.domain.User;
import com.plantcare.bot.controller.api.UserApiResolver;
import com.plantcare.core.service.CalendarApiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * REST API для получения календаря ухода (issue #86).
 *
 * <p>Документация и mapping живут в сгенерированном {@link CalendarApi}.
 *
 * <p>Spec-генератор отдаёт ответ как {@code Map<String, List<TaskDto>>} —
 * ключи дат сериализуются строго в формате ISO `YYYY-MM-DD` (через
 * {@link LocalDate#toString()}).
 */
@Slf4j
@RestController("calendarApiV1Controller")
@RequiredArgsConstructor
public class CalendarController implements CalendarApi {

    private static final int MAX_RANGE_DAYS = 60;

    private final CalendarApiService calendarApiService;
    private final UserApiResolver userApiResolver;

    @Override
    public Map<String, List<TaskDto>> getCalendar(Long chatId, LocalDate from, LocalDate to) {
        if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw new IllegalArgumentException(
                    "Date range exceeds maximum of " + MAX_RANGE_DAYS + " days");
        }

        User user = userApiResolver.resolve(chatId);
        log.info("GET /api/v1/calendar: userId={}, from={}, to={}", user.getId(), from, to);

        List<CareSchedule> schedules = calendarApiService.getActiveSchedules(user.getId());

        LocalDate winEnd = to.plusDays(1);

        Map<String, List<TaskDto>> result = new TreeMap<>();

        for (CareSchedule schedule : schedules) {
            for (LocalDate eventDay : calendarApiService.projectEvents(schedule, from, winEnd, user.getTimezone())) {
                result.computeIfAbsent(eventDay.toString(), k -> new ArrayList<>())
                        .add(TodayController.toTaskDto(schedule));
            }
        }

        return result;
    }
}
