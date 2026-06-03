package com.plantcare.core.service;

import com.plantcare.core.domain.CareHistory;
import com.plantcare.core.domain.CareSchedule;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.repository.CareHistoryRepository;
import com.plantcare.core.repository.CareScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Сервис для экрана «📜 История» в карточке растения и стриков (issue #51).
 *
 * Логика стриков:
 *
 *  <b>Per-plant streak</b> — «выполнений подряд без пропусков».
 *  Алгоритм:
 *   1) Если у любого активного расписания этого растения {@code nextDueAt + 1 day < now}
 *      и нет более свежего "сделано" — стрик 0 (просроченная задача висит).
 *   2) Иначе идём по истории DESC: пока {@code onTime=true} → +1, на первой
 *      late-записи стоп.
 *  Compensating (cancelled) записи пропускаются.
 *
 *  <b>Per-user streak</b> — «дни подряд, в которые юзер сделал хотя бы одно действие».
 *  День определяется в TZ юзера. Допускается дыра «сегодня ещё ничего», тогда
 *  стрик отсчитывается от вчерашнего дня.
 *
 *  <b>On-time %</b> — на окне 30 дней. Если в окне совсем мало действий — UI
 *  спрячет блок («Пока мало данных») по правилу total &lt; 3.
 *
 * Кэширования сознательно нет — запросы дешёвые, и любая инвалидация добавит
 * сложности (история пишется после каждого done). Если нагрузка вырастет —
 * подключим Caffeine отдельным PR.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CareHistoryService {

    /** Размер страницы истории (ТЗ: «по 20 записей»). */
    public static final int HISTORY_PAGE_SIZE = 20;

    /** Сколько последних записей берём для расчёта стрика по растению. */
    private static final int STREAK_LOOKBACK = 100;

    /** Сколько done_at-точек тянем для расчёта user-стрика. */
    private static final int USER_STREAK_LOOKBACK = 100;

    /** Окно on-time-% (ТЗ: «за последние 30 дней»). */
    private static final int ON_TIME_WINDOW_DAYS = 30;

    /**
     * Минимальное число действий, чтобы вообще показывать стрик/проценты.
     * При меньшем — UI выводит «Пока мало данных, продолжай ухаживать 🌱».
     */
    public static final int MIN_ACTIONS_FOR_STATS = 3;

    /**
     * Стрик в главном меню юзера показываем только если он ≥ 3 дней.
     */
    public static final int MIN_USER_STREAK_TO_SHOW = 3;

    private final CareHistoryRepository careHistoryRepository;
    private final CareScheduleRepository careScheduleRepository;
    private final Clock clock;

    /**
     * Одна страница истории растения в обратном хронологическом порядке.
     * Страницы нумеруются с 0.
     */
    @Transactional(readOnly = true)
    public List<CareHistory> getHistoryPage(Long plantId, int page) {
        int safePage = Math.max(0, page);
        return careHistoryRepository.findActiveByPlantIdPaged(
                plantId, PageRequest.of(safePage, HISTORY_PAGE_SIZE));
    }

    /**
     * Срез истории с реальным offset — для REST API (issue #86).
     * Используется эндпоинтом GET /api/v1/plants/{id}/history?limit=N&offset=M.
     *
     * <p>Использует нативный SQL с LIMIT/OFFSET вместо page-based пагинации,
     * чтобы offset=5, limit=20 возвращал записи 5-24, а не 0-19.
     *
     * @param plantId  id растения
     * @param offset   смещение от начала (0-based)
     * @param limit    количество записей [1, 100]
     * @param taskType опциональный фильтр по типу ухода; {@code null} — все типы
     */
    @Transactional(readOnly = true)
    public List<CareHistory> getHistoryPageWithLimit(Long plantId, int offset, int limit, TaskType taskType) {
        int safeOffset = Math.max(0, offset);
        String typeStr = taskType != null ? taskType.name() : null;
        return careHistoryRepository.findActiveByPlantIdWithRealOffsetAndType(plantId, limit, safeOffset, typeStr);
    }

    /**
     * Срез истории с реальным offset без фильтра по типу — делегирует к основному методу.
     *
     * @param plantId id растения
     * @param offset  смещение от начала (0-based)
     * @param limit   количество записей [1, 100]
     */
    @Transactional(readOnly = true)
    public List<CareHistory> getHistoryPageWithLimit(Long plantId, int offset, int limit) {
        return getHistoryPageWithLimit(plantId, offset, limit, null);
    }

    /**
     * Сводная статистика по всей истории растения (issue #206).
     *
     * <p>Возвращает: общее число активных записей, число on-time, процент on-time,
     * разбивку по типам ухода (без SOIL_CHECK, только типы с count > 0).
     *
     * @param plantId id растения
     * @return сводка
     */
    @Transactional(readOnly = true)
    public HistorySummary getHistorySummary(Long plantId) {
        long total = countHistory(plantId);
        long onTimeCount = careHistoryRepository.countActiveOnTimeByPlantId(plantId);
        int onTimePercent = total == 0 ? 0 : (int) Math.round(100.0 * onTimeCount / total);

        Map<TaskType, Long> byType = careHistoryRepository.countActiveByPlantIdGroupedByType(plantId)
                .stream()
                .filter(r -> r.getTaskType() != TaskType.SOIL_CHECK)
                .filter(r -> r.getCount() > 0)
                .collect(Collectors.toMap(
                        CareHistoryRepository.TaskTypeCount::getTaskType,
                        CareHistoryRepository.TaskTypeCount::getCount,
                        Long::sum,
                        () -> new EnumMap<>(TaskType.class)
                ));

        return new HistorySummary(total, onTimeCount, onTimePercent, byType);
    }

    /**
     * Сколько всего активных записей у растения — для пагинатора и для решения
     * показывать ли блок статистики (≥ MIN_ACTIONS_FOR_STATS).
     */
    @Transactional(readOnly = true)
    public long countHistory(Long plantId) {
        return careHistoryRepository.countActiveByPlantId(plantId);
    }

    /**
     * Сколько активных записей у растения с опциональным фильтром по типу ухода.
     * При {@code taskType == null} возвращает общее число — аналогично {@link #countHistory(Long)}.
     *
     * @param plantId  id растения
     * @param taskType тип ухода для фильтрации, или {@code null} — все типы
     */
    @Transactional(readOnly = true)
    public long countHistory(Long plantId, TaskType taskType) {
        String typeStr = taskType != null ? taskType.name() : null;
        return careHistoryRepository.countActiveByPlantIdAndType(plantId, typeStr);
    }

    /**
     * Сводка статистики по растению. Если {@link PlantStats#total()} меньше
     * {@link #MIN_ACTIONS_FOR_STATS} — UI должен скрыть блок и показать заглушку.
     */
    @Transactional(readOnly = true)
    public PlantStats getPlantStats(Long plantId) {
        long total = countHistory(plantId);

        if (total == 0) {
            return new PlantStats(0, 0, 0);
        }

        LocalDateTime since = LocalDateTime.now(clock).minusDays(ON_TIME_WINDOW_DAYS);
        long actionsInWindow = careHistoryRepository.countActiveByPlantIdSince(plantId, since);
        long onTimeInWindow = careHistoryRepository.countActiveOnTimeByPlantIdSince(plantId, since);

        int onTimePct = actionsInWindow == 0
                ? 0
                : (int) Math.round(100.0 * onTimeInWindow / actionsInWindow);

        int streak = computePlantStreak(plantId);

        return new PlantStats(streak, onTimePct, total);
    }

    /**
     * Стрик по конкретному растению. Алгоритм описан в javadoc класса.
     * Публичный для удобства тестирования и переиспользования.
     */
    @Transactional(readOnly = true)
    public int computePlantStreak(Long plantId) {
        // 1) Просроченные расписания (без своего done) сразу обнуляют стрик.
        LocalDateTime now = LocalDateTime.now(clock);
        List<CareSchedule> schedules = careScheduleRepository.findAllByPlantId(plantId);
        for (CareSchedule s : schedules) {
            if (!s.isActive() || s.getNextDueAt() == null) continue;
            // Прошло >1 дня после nextDueAt — задача не отмечена, стрик сорван.
            if (s.getNextDueAt().plusDays(1).isBefore(now)) {
                return 0;
            }
        }

        // 2) Иначе идём по истории с конца, пока все on-time.
        List<CareHistory> recent = careHistoryRepository.findAllByPlantIdOrderByDoneAtDesc(
                plantId, Limit.of(STREAK_LOOKBACK));

        int count = 0;
        for (CareHistory h : recent) {
            if (h.isCancelled()) continue; // компенсирующие игнорируем
            if (h.isOnTime()) {
                count++;
            } else {
                break;
            }
        }
        return count;
    }

    /**
     * Стрик пользователя по дням — «подряд идущие дни, в которые он сделал
     * хотя бы одно действие». Допускается, что сегодня действий ещё нет:
     * тогда стрик отсчитывается от вчерашнего дня (юзер просто ещё не успел).
     *
     * Если последняя активность была раньше чем «вчера» — стрик 0.
     */
    @Transactional(readOnly = true)
    public int computeUserStreak(Long userId, String timezone) {
        ZoneId tz = safeZone(timezone);
        List<LocalDateTime> doneAts = careHistoryRepository.findUserDoneAtsDesc(
                userId, Limit.of(USER_STREAK_LOOKBACK));

        if (doneAts.isEmpty()) return 0;

        // Уникальные дни (в TZ юзера) в порядке убывания.
        TreeSet<LocalDate> uniqueDays = new TreeSet<>();
        Set<LocalDate> seen = new HashSet<>();
        for (LocalDateTime utc : doneAts) {
            LocalDate day = utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(tz).toLocalDate();
            if (seen.add(day)) {
                uniqueDays.add(day);
            }
        }

        LocalDate today = LocalDate.now(clock.withZone(tz));
        LocalDate latest = uniqueDays.last();

        // Если последняя активность раньше «вчера» — стрик сломан.
        if (latest.isBefore(today.minusDays(1))) {
            return 0;
        }

        // Стрик начинается с latest и идёт вниз по последовательным дням.
        int streak = 0;
        LocalDate expected = latest;
        for (LocalDate day : uniqueDays.descendingSet()) {
            if (day.equals(expected)) {
                streak++;
                expected = expected.minusDays(1);
            } else {
                break;
            }
        }
        return streak;
    }

    private ZoneId safeZone(String tz) {
        try {
            return tz == null || tz.isBlank() ? ZoneOffset.UTC : ZoneId.of(tz);
        } catch (Exception e) {
            log.warn("Invalid timezone '{}' — falling back to UTC", tz);
            return ZoneOffset.UTC;
        }
    }

    /**
     * Сводка статистики по растению для UI карточки/истории.
     *
     * @param streak    выполнений подряд без пропусков (по on_time)
     * @param onTimePct процент on-time действий за последние 30 дней
     * @param total     общее число активных (не-cancelled) записей
     */
    public record PlantStats(int streak, int onTimePct, long total) {
        /** Хватает ли данных, чтобы показывать блок статистики. */
        public boolean hasEnoughData() {
            return total >= MIN_ACTIONS_FOR_STATS;
        }
    }

    /**
     * Агрегированная статистика по всей истории растения (issue #206).
     *
     * @param total          всего активных (не-cancelled) записей
     * @param onTimeCount    из них on-time
     * @param onTimePercent  процент on-time; 0 если total = 0
     * @param byType         разбивка по типам (без SOIL_CHECK, только count > 0)
     */
    public record HistorySummary(long total, long onTimeCount, int onTimePercent, Map<TaskType, Long> byType) {}
}
