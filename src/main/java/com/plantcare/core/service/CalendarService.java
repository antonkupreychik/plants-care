package com.plantcare.core.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.plantcare.core.domain.CareSchedule;
import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.repository.CareScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Сборка «недельного календаря ухода» (issue #52).
 *
 * Бизнес-логика:
 *   - Окно: 7 дней начиная с сегодня в TZ юзера, со сдвигом по неделям (offset).
 *     offset=0 — текущая неделя, offset=-1 — предыдущая, offset=1 — следующая.
 *   - Для каждого активного расписания юзера проецируем «события» по формуле
 *     {@code event = nextDueAt + N * interval_days} (N=0,1,2,…), пока event &lt;
 *     winEnd. События, которые попадают в окно [winStart, winEnd), включаем.
 *   - Если nextDueAt раньше winStart (юзер просрочил, или мы смотрим будущую неделю
 *     для расписания с малым интервалом) — быстро пропускаем N интервалов,
 *     чтобы не итерироваться сотни раз.
 *
 * Кэш: Caffeine, TTL 1 мин, по ключу (userId, offset). Если юзер быстро жмёт
 * «Следующая →» / «← Прошлая» — БД не дёргаем повторно. На markCareDone мы не
 * инвалидируем кэш: одна минута устаревания приемлема для UX календаря.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CalendarService {

    public static final int DAYS_PER_WEEK = 7;

    /** Лимит длины одного Telegram-сообщения; берём с запасом от реального 4096. */
    public static final int TELEGRAM_MESSAGE_SOFT_LIMIT = 3500;

    private final CareScheduleRepository careScheduleRepository;

    private final Cache<CacheKey, WeekView> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1))
            .maximumSize(2_000)
            .build();

    /**
     * Построить view недели для юзера.
     *
     * @param weekOffset 0=текущая неделя, ±N=сдвиг по неделям
     */
    @Transactional(readOnly = true)
    public WeekView buildWeekView(User user, int weekOffset) {
        CacheKey key = new CacheKey(user.getId(), weekOffset);
        WeekView cached = cache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        WeekView fresh = computeWeekView(user, weekOffset);
        cache.put(key, fresh);
        return fresh;
    }

    private WeekView computeWeekView(User user, int weekOffset) {
        ZoneId tz = safeZone(user.getTimezone());
        LocalDate today = LocalDate.now(tz);
        LocalDate weekStart = today.plusDays((long) weekOffset * DAYS_PER_WEEK);
        LocalDate weekEnd = weekStart.plusDays(DAYS_PER_WEEK);

        List<CareSchedule> schedules =
                careScheduleRepository.findActiveSchedulesByUserId(user.getId());

        // Группировка событий по дню в TZ юзера.
        Map<LocalDate, List<CareTask>> tasksByDay = new TreeMap<>();
        for (CareSchedule s : schedules) {
            for (LocalDate eventDay : projectEvents(s, weekStart, weekEnd, tz)) {
                tasksByDay.computeIfAbsent(eventDay, k -> new ArrayList<>())
                        .add(new CareTask(s.getPlant(), s.getTaskType(), s.getPlant().getLocation()));
            }
        }

        // Внутри каждого дня сортируем: сначала по комнате (для группировки),
        // потом по имени растения, потом по типу задачи.
        for (List<CareTask> dayTasks : tasksByDay.values()) {
            dayTasks.sort(Comparator
                    .<CareTask, String>comparing(t -> t.location() == null ? "" : t.location().getName())
                    .thenComparing(t -> t.plant().getName())
                    .thenComparing(t -> t.taskType().ordinal()));
        }

        // 7 строк, включая пустые дни.
        List<DayView> days = new ArrayList<>(DAYS_PER_WEEK);
        for (int i = 0; i < DAYS_PER_WEEK; i++) {
            LocalDate d = weekStart.plusDays(i);
            int dayOffsetFromToday = (int) ChronoUnit.DAYS.between(today, d);
            days.add(new DayView(d, dayOffsetFromToday,
                    tasksByDay.getOrDefault(d, List.of())));
        }

        return new WeekView(weekStart, weekOffset, today, days);
    }

    /**
     * Проекция событий одного расписания в окно [winStart, winEnd).
     * Алгоритм описан в javadoc класса.
     */
    private List<LocalDate> projectEvents(
            CareSchedule s, LocalDate winStart, LocalDate winEnd, ZoneId tz
    ) {
        if (s.getNextDueAt() == null || s.getIntervalDays() <= 0) {
            return List.of();
        }

        LocalDate eventDay = s.getNextDueAt()
                .atOffset(ZoneOffset.UTC)
                .atZoneSameInstant(tz)
                .toLocalDate();
        int interval = s.getIntervalDays();

        // Если событие далеко в прошлом — фастфорвардим до winStart, чтобы
        // не крутить цикл тысячи раз для давно просроченных задач.
        if (eventDay.isBefore(winStart)) {
            long behind = ChronoUnit.DAYS.between(eventDay, winStart);
            long skip = (behind + interval - 1) / interval;  // ceil
            eventDay = eventDay.plusDays(skip * interval);
        }

        List<LocalDate> events = new ArrayList<>();
        while (eventDay.isBefore(winEnd)) {
            events.add(eventDay);
            eventDay = eventDay.plusDays(interval);
        }
        return events;
    }

    /** Сгруппировать задачи дня по комнатам, сохраняя порядок появления. */
    public Map<Location, List<CareTask>> groupByLocation(List<CareTask> tasks) {
        Map<Location, List<CareTask>> grouped = new LinkedHashMap<>();
        for (CareTask t : tasks) {
            grouped.computeIfAbsent(t.location(), k -> new ArrayList<>()).add(t);
        }
        return grouped;
    }

    /**
     * Сколько уникальных комнат в неделе. Если 0 или 1 — UI выводит плоский
     * список задач, иначе группирует по комнатам.
     */
    public int distinctLocationsInWeek(WeekView week) {
        return (int) week.days().stream()
                .flatMap(d -> d.tasks().stream())
                .map(CareTask::location)
                .distinct()
                .count();
    }

    private ZoneId safeZone(String tz) {
        try {
            return tz == null || tz.isBlank() ? ZoneOffset.UTC : ZoneId.of(tz);
        } catch (Exception e) {
            log.warn("Invalid timezone '{}' — falling back to UTC", tz);
            return ZoneOffset.UTC;
        }
    }

    // =============================================
    // Records (составляют публичный API сервиса).
    // =============================================

    public record CacheKey(Long userId, int offset) {}

    public record CareTask(Plant plant, TaskType taskType, Location location) {}

    /**
     * @param date                дата дня
     * @param dayOffsetFromToday  0=сегодня, 1=завтра, -1=вчера и т.д. (-N допустим
     *                            при просмотре прошлых недель)
     * @param tasks               задачи на этот день
     */
    public record DayView(LocalDate date, int dayOffsetFromToday, List<CareTask> tasks) {

        public boolean isEmpty() {
            return tasks.isEmpty();
        }

        public boolean isToday() {
            return dayOffsetFromToday == 0;
        }

        public boolean isTomorrow() {
            return dayOffsetFromToday == 1;
        }

        public boolean isYesterday() {
            return dayOffsetFromToday == -1;
        }
    }

    /**
     * @param weekStart  первый день недели в TZ юзера
     * @param offset     смещение в неделях относительно "текущей" (0=текущая)
     * @param today      сегодня в TZ юзера (для подписи дней «сегодня/завтра»)
     * @param days       ровно 7 элементов, по дням
     */
    public record WeekView(LocalDate weekStart, int offset, LocalDate today, List<DayView> days) {

        public LocalDate weekEnd() {
            return weekStart.plusDays(DAYS_PER_WEEK - 1);
        }

        /**
         * Сколько задач во всей неделе (для UI «пусто»).
         */
        public long totalTasks() {
            return days.stream().mapToLong(d -> d.tasks().size()).sum();
        }
    }
}
