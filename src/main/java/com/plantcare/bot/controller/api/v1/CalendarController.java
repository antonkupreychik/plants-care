package com.plantcare.bot.controller.api.v1;

import com.plantcare.bot.controller.api.UserApiResolver;
import com.plantcare.bot.controller.api.v1.dto.TaskDto;
import com.plantcare.bot.domain.CareSchedule;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.service.CalendarApiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * REST API для получения календаря ухода за произвольный период (issue #86).
 *
 * <p>Принимает произвольный диапазон [from, to] (до 60 дней).
 * Проекция расписаний на даты выполняется в {@link CalendarApiService}.
 */
@Slf4j
@RestController("calendarApiV1Controller")
@RequestMapping("/api/v1/calendar")
@RequiredArgsConstructor
public class CalendarController {

    private static final int MAX_RANGE_DAYS = 60;

    private final CalendarApiService calendarApiService;
    private final UserApiResolver userApiResolver;

    /**
     * GET /api/v1/calendar?from=2024-01-01&to=2024-01-31
     *
     * <p>Возвращает {@link TreeMap} LocalDate → список TaskDto, чтобы даты в JSON
     * шли строго по возрастанию. Дни без задач в ответ не включаются.
     *
     * @param from начало диапазона (включительно)
     * @param to   конец диапазона (включительно), разница не более 60 дней
     * @return карта дата → задачи
     */
    @GetMapping
    public Map<LocalDate, List<TaskDto>> calendar(
            @RequestParam LocalDate from,
            @RequestParam LocalDate to,
            @RequestHeader("X-Chat-Id") Long chatId
    ) {
        if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw new IllegalArgumentException(
                    "Date range exceeds maximum of " + MAX_RANGE_DAYS + " days");
        }

        User user = userApiResolver.resolve(chatId);
        log.info("GET /api/v1/calendar: userId={}, from={}, to={}", user.getId(), from, to);

        List<CareSchedule> schedules = calendarApiService.getActiveSchedules(user.getId());

        // to включительно → winEnd = to + 1 день (алгоритм проекции работает с [winStart, winEnd))
        LocalDate winEnd = to.plusDays(1);

        Map<LocalDate, List<TaskDto>> result = new TreeMap<>();

        for (CareSchedule schedule : schedules) {
            for (LocalDate eventDay : calendarApiService.projectEvents(schedule, from, winEnd, user.getTimezone())) {
                result.computeIfAbsent(eventDay, k -> new ArrayList<>())
                        .add(TaskDto.from(schedule));
            }
        }

        return result;
    }
}
