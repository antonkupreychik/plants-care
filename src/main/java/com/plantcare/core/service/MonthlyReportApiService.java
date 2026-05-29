package com.plantcare.core.service;

import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.repository.CareHistoryRepository;
import com.plantcare.core.repository.CareHistoryRepository.DoneAtOnTime;
import com.plantcare.core.repository.CareHistoryRepository.TaskTypeCount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Сервис месячного REST-отчёта по уходу (issue #192, gap G23, мобильный экран 14).
 *
 * <p>Скоуп — текущий пользователь. Границы отчётного месяца считаются в TZ
 * пользователя ({@code users.timezone}) и конвертируются в UTC {@link LocalDateTime}
 * для запросов по {@code care_history.done_at} (в БД done_at хранится как UTC
 * wall-clock). Паттерн границ повторяет {@link MonthlyReportService}.
 *
 * <p>Отдельное имя от {@link MonthlyReportService} (тот шлёт Telegram push-отчёт за
 * прошлый месяц) — здесь REST-агрегация за произвольный запрошенный месяц.
 *
 * <p>Метрики:
 * <ul>
 *   <li>{@code done} — активные (не-cancelled) действия в окне месяца;</li>
 *   <li>{@code overdue} — из них выполненные с опозданием ({@code onTime = false});</li>
 *   <li>{@code byType} — разбивка {@code done} по {@link TaskType}, все 4 ключа всегда
 *       присутствуют (отсутствующие = 0);</li>
 *   <li>{@code streak} — текущий стрик пользователя (переиспользуется
 *       {@link CareHistoryService#computeUserStreak});</li>
 *   <li>{@code healthTrend} — понедельные бакеты качества по ISO-неделям, в которые
 *       попали действия месяца (бакетинг в TZ юзера, в Java).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MonthlyReportApiService {

    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final CareHistoryRepository careHistoryRepository;
    private final CareHistoryService careHistoryService;

    /**
     * Собрать месячный отчёт для пользователя.
     *
     * @param userId   внутренний id пользователя
     * @param timezone строка таймзоны пользователя ({@code users.timezone})
     * @param month    отчётный месяц в формате {@code YYYY-MM}
     * @return сводка за месяц
     * @throws IllegalArgumentException если {@code month} не парсится как {@code YYYY-MM}
     *                                  (маппится в 400 через {@code ApiExceptionHandler})
     */
    public MonthlyReport getMonthlyReport(Long userId, String timezone, String month) {
        YearMonth reportMonth = parseMonth(month);
        ZoneId zone = safeZone(timezone);

        LocalDateTime from = reportMonth.atDay(1).atStartOfDay(zone)
                .toInstant().atZone(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime toExclusive = reportMonth.plusMonths(1).atDay(1).atStartOfDay(zone)
                .toInstant().atZone(ZoneOffset.UTC).toLocalDateTime();

        log.debug("Monthly report: userId={}, month={}, from={}, toExclusive={}, zone={}",
                userId, month, from, toExclusive, zone);

        long done = careHistoryRepository.countActiveByUserIdInRange(userId, from, toExclusive);
        long overdue = careHistoryRepository.countLateActiveByUserIdInRange(userId, from, toExclusive);
        Map<TaskType, Long> byType = loadByType(userId, from, toExclusive);
        int streak = careHistoryService.computeUserStreak(userId, timezone);
        List<WeeklyHealthBucket> healthTrend = loadHealthTrend(userId, from, toExclusive, zone);

        return new MonthlyReport(reportMonth.format(MONTH_FORMAT), done, overdue, byType, streak, healthTrend);
    }

    /** Разбивка done по типам ухода: все 4 enum-значения, отсутствующие = 0. */
    private Map<TaskType, Long> loadByType(Long userId, LocalDateTime from, LocalDateTime toExclusive) {
        Map<TaskType, Long> byType = new LinkedHashMap<>();
        for (TaskType type : TaskType.values()) {
            byType.put(type, 0L);
        }
        for (TaskTypeCount row : careHistoryRepository.countActiveByUserIdGroupedByTaskType(
                userId, from, toExclusive)) {
            byType.put(row.getTaskType(), row.getCount());
        }
        return byType;
    }

    /**
     * Понедельный тренд: бакетим каждое done-действие по ISO-неделе локальной
     * (в TZ юзера) даты его {@code doneAt}. Недели упорядочены хронологически.
     */
    private List<WeeklyHealthBucket> loadHealthTrend(Long userId, LocalDateTime from,
                                                      LocalDateTime toExclusive, ZoneId zone) {
        // key — пара (week-based-year, week-of-week-based-year) для корректной сортировки
        // на стыке годов (например W52 2025 → W01 2026).
        Map<Long, long[] /* [done, onTime] */> buckets = new TreeMap<>();
        Map<Long, String> labels = new HashMap<>();

        for (DoneAtOnTime row : careHistoryRepository.findActiveDoneAtOnTimeByUserIdInRange(
                userId, from, toExclusive)) {
            LocalDate local = row.getDoneAt().atOffset(ZoneOffset.UTC).atZoneSameInstant(zone).toLocalDate();
            int week = local.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            int weekYear = local.get(IsoFields.WEEK_BASED_YEAR);
            long sortKey = (long) weekYear * 100 + week;

            long[] acc = buckets.computeIfAbsent(sortKey, k -> new long[2]);
            acc[0]++;
            if (row.getOnTime()) {
                acc[1]++;
            }
            labels.putIfAbsent(sortKey, String.format("%04d-W%02d", weekYear, week));
        }

        List<WeeklyHealthBucket> trend = new ArrayList<>(buckets.size());
        for (Map.Entry<Long, long[]> e : buckets.entrySet()) {
            long bucketDone = e.getValue()[0];
            long onTime = e.getValue()[1];
            double onTimePct = bucketDone == 0
                    ? 0.0
                    : Math.round(100.0 * onTime / bucketDone) / 100.0;
            trend.add(new WeeklyHealthBucket(labels.get(e.getKey()), bucketDone, onTimePct));
        }
        return trend;
    }

    private YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) {
            throw new IllegalArgumentException("Query param 'month' is required (format YYYY-MM)");
        }
        try {
            return YearMonth.parse(month, MONTH_FORMAT);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid 'month' format, expected YYYY-MM: " + month, e);
        }
    }

    private static ZoneId safeZone(String tz) {
        if (tz == null || tz.isBlank()) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(tz);
        } catch (Exception e) {
            return ZoneOffset.UTC;
        }
    }

    /**
     * Готовая сводка месячного отчёта. {@code byType} — все 4 {@link TaskType},
     * {@code healthTrend} — хронологически упорядоченные недельные бакеты.
     */
    public record MonthlyReport(
            String month,
            long done,
            long overdue,
            Map<TaskType, Long> byType,
            int streak,
            List<WeeklyHealthBucket> healthTrend
    ) {
    }

    /** Понедельный бакет качества: ISO-неделя, число done и доля on-time (0..1). */
    public record WeeklyHealthBucket(String week, long done, double onTimePct) {
    }
}
