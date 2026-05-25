package com.plantcare.core.service;

import com.plantcare.core.domain.CareSchedule;
import com.plantcare.core.repository.CareScheduleRepository;
import com.plantcare.core.util.TimeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Сервис для REST API календаря ухода (issue #86).
 *
 * <p>Вынесен из {@link com.plantcare.api.v1.CalendarController},
 * чтобы репозиторий не был виден контроллеру напрямую (CLAUDE.md: «никаких репозиториев в хендлерах»).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CalendarApiService {

    private final CareScheduleRepository careScheduleRepository;

    /**
     * Все активные расписания пользователя.
     *
     * @param userId id пользователя
     * @return список активных расписаний
     */
    public List<CareSchedule> getActiveSchedules(Long userId) {
        return careScheduleRepository.findActiveSchedulesByUserId(userId);
    }

    /**
     * Проекция событий одного расписания в окно [winStart, winEnd).
     *
     * <p>Алгоритм идентичен {@code CalendarService.projectEvents}:
     * начинаем от {@code nextDueAt} (конвертируем в дату в TZ пользователя),
     * делаем fast-forward если событие до окна, затем собираем все даты до winEnd.
     *
     * @param schedule  расписание
     * @param winStart  начало окна (включительно)
     * @param winEnd    конец окна (исключительно)
     * @param timezone  строка таймзоны пользователя
     * @return список дат событий в окне
     */
    public List<LocalDate> projectEvents(CareSchedule schedule, LocalDate winStart,
                                         LocalDate winEnd, String timezone) {
        if (schedule.getNextDueAt() == null || schedule.getIntervalDays() <= 0) {
            return List.of();
        }

        ZoneId tz = TimeUtils.safeZone(timezone);

        LocalDate eventDay = schedule.getNextDueAt()
                .toInstant(ZoneOffset.UTC)
                .atZone(tz)
                .toLocalDate();
        int interval = schedule.getIntervalDays();

        // Fast-forward: пропускаем интервалы, если событие раньше winStart.
        if (eventDay.isBefore(winStart)) {
            long behind = ChronoUnit.DAYS.between(eventDay, winStart);
            long skip = (behind + interval - 1) / interval; // ceil division
            eventDay = eventDay.plusDays(skip * interval);
        }

        List<LocalDate> events = new ArrayList<>();
        while (eventDay.isBefore(winEnd)) {
            events.add(eventDay);
            eventDay = eventDay.plusDays(interval);
        }
        return events;
    }
}
