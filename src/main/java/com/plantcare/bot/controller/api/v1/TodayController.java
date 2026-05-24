package com.plantcare.bot.controller.api.v1;

import com.plantcare.bot.controller.api.UserApiResolver;
import com.plantcare.bot.controller.api.v1.dto.TaskDto;
import com.plantcare.bot.controller.api.v1.dto.TodayResponse;
import com.plantcare.bot.domain.CareSchedule;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.service.TodayApiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST API для получения задач ухода на сегодня (issue #86).
 *
 * <p>Конец сегодняшнего дня вычисляется в TZ пользователя через {@link TodayApiService}.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class TodayController {

    private final TodayApiService todayApiService;
    private final UserApiResolver userApiResolver;

    /**
     * GET /api/v1/today — задачи ухода, дедлайн которых до конца сегодняшнего дня
     * в таймзоне пользователя.
     */
    @GetMapping("/today")
    public TodayResponse today(@RequestHeader("X-Chat-Id") Long chatId) {
        User user = userApiResolver.resolve(chatId);
        log.info("GET /api/v1/today: userId={}, timezone={}", user.getId(), user.getTimezone());

        List<CareSchedule> schedules = todayApiService.getTodaySchedules(user.getId(), user.getTimezone());

        List<TaskDto> tasks = schedules.stream()
                .map(TaskDto::from)
                .toList();

        return new TodayResponse(tasks, tasks.size());
    }
}
