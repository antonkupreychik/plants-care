package com.plantcare.core.service;

import com.plantcare.core.domain.CareSchedule;
import com.plantcare.core.repository.CareScheduleRepository;
import com.plantcare.core.util.TimeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Сервис для REST API «сегодняшних» задач ухода (issue #86).
 *
 * <p>Вынесен из {@link com.plantcare.bot.controller.api.v1.TodayController},
 * чтобы репозиторий не был виден контроллеру напрямую (CLAUDE.md: «никаких репозиториев в хендлерах»).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TodayApiService {

    private final CareScheduleRepository careScheduleRepository;
    private final Clock clock;

    /**
     * Расписания ухода, дедлайн которых наступает до конца сегодняшнего дня
     * в таймзоне пользователя.
     *
     * @param userId   внутренний id пользователя
     * @param timezone строка таймзоны пользователя
     * @return список расписаний, отсортированный по nextDueAt ASC
     */
    public List<CareSchedule> getTodaySchedules(Long userId, String timezone) {
        LocalDateTime endOfToday = TimeUtils.endOfTodayInUtc(timezone, clock);
        log.debug("getTodaySchedules: userId={}, timezone={}, endOfToday={}", userId, timezone, endOfToday);
        return careScheduleRepository.findUserSchedulesDueBefore(userId, endOfToday);
    }
}
