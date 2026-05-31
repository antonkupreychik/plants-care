package com.plantcare.core.service;

import com.plantcare.core.domain.CareSchedule;
import com.plantcare.core.repository.CareHistoryRepository;
import com.plantcare.core.repository.CareScheduleRepository;
import com.plantcare.core.util.TimeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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
    private final CareHistoryRepository careHistoryRepository;

    /**
     * Прогресс выполнения по дням за диапазон {@code [from, to]} (включительно)
     * в таймзоне пользователя (issue #179, gap G11).
     *
     * <p>{@code planned} — число запланированных occurrence'ов (та же проекция
     * активных расписаний, что и в {@link #projectEvents}). {@code done} — число
     * активных (не-cancelled) записей {@code care_history}, попавших в этот
     * локальный день. Дни без планов и без выполнений в результат не попадают.
     *
     * @param userId   id пользователя
     * @param from     начало диапазона (включительно)
     * @param to       конец диапазона (включительно)
     * @param timezone строка таймзоны пользователя
     * @return отсортированная по дате карта «локальный день → прогресс»
     */
    public Map<LocalDate, DayProgress> getProgress(Long userId, LocalDate from,
                                                   LocalDate to, String timezone) {
        ZoneId tz = TimeUtils.safeZone(timezone);
        LocalDate winEnd = to.plusDays(1);

        // counts[0] = planned, counts[1] = done
        Map<LocalDate, int[]> counts = new TreeMap<>();

        for (CareSchedule schedule : careScheduleRepository.findActiveSchedulesByUserId(userId)) {
            for (LocalDate day : projectEvents(schedule, from, winEnd, timezone)) {
                counts.computeIfAbsent(day, k -> new int[2])[0]++;
            }
        }

        LocalDateTime fromUtc = from.atStartOfDay(tz).toInstant().atZone(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime toExclusiveUtc = winEnd.atStartOfDay(tz).toInstant().atZone(ZoneOffset.UTC).toLocalDateTime();

        for (LocalDateTime doneAtUtc : careHistoryRepository.findActiveDoneAtsByUserIdInRange(userId, fromUtc, toExclusiveUtc)) {
            LocalDate localDay = doneAtUtc.atOffset(ZoneOffset.UTC).atZoneSameInstant(tz).toLocalDate();
            counts.computeIfAbsent(localDay, k -> new int[2])[1]++;
        }

        Map<LocalDate, DayProgress> result = new TreeMap<>();
        counts.forEach((day, c) -> result.put(day, new DayProgress(c[0], c[1])));
        log.debug("getProgress: userId={}, from={}, to={}, days={}", userId, from, to, result.size());
        return result;
    }

    /**
     * Прогресс за один день: запланировано/выполнено. Числа в TZ пользователя.
     */
    public record DayProgress(int planned, int done) {
    }

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
