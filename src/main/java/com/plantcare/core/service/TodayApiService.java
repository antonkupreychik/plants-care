package com.plantcare.core.service;

import com.plantcare.core.domain.CareSchedule;
import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.repository.CareHistoryRepository;
import com.plantcare.core.repository.CareScheduleRepository;
import com.plantcare.core.util.TimeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Сервис для REST API «сегодняшних» задач ухода (issue #86, #184).
 *
 * <p>Вынесен из {@link com.plantcare.api.v1.TodayController},
 * чтобы репозиторий не был виден контроллеру напрямую (CLAUDE.md: «никаких репозиториев в хендлерах»).
 *
 * <p>Done-фид (issue #184, ADR-014): выдача = UNION (а) pending — активные
 * расписания с {@code nextDueAt} до конца сегодняшнего дня в TZ юзера;
 * (б) done-сегодня — активные расписания, по которым есть активная (не-cancelled)
 * запись {@code care_history} в окне сегодняшнего дня. Дедуп по (plantId, taskType)
 * в пользу done.
 *
 * <p>Issue #250 (мультидомность): растения локаций на паузе исключаются из выдачи.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TodayApiService {

    private final CareScheduleRepository careScheduleRepository;
    private final CareHistoryRepository careHistoryRepository;
    private final Clock clock;

    /**
     * Задачи ухода на сегодня в таймзоне пользователя: pending (дедлайн до конца дня)
     * и выполненные сегодня. Дедуп по (plantId, taskType) — done перекрывает pending.
     *
     * <p>Растения локаций, у которых выставлен {@code paused_until} в будущем,
     * исключаются (issue #250 — пауза локации).
     *
     * @param userId   внутренний id пользователя
     * @param timezone строка таймзоны пользователя
     * @return задачи, отсортированные pending (по nextDueAt ASC), затем done
     */
    public List<TodayTask> getTodayTasks(Long userId, String timezone) {
        LocalDateTime startOfToday = TimeUtils.startOfTodayInUtc(timezone, clock);
        LocalDateTime endOfToday = TimeUtils.endOfTodayInUtc(timezone, clock);
        log.debug("getTodayTasks: userId={}, timezone={}, startOfToday={}, endOfToday={}",
                userId, timezone, startOfToday, endOfToday);

        Map<TaskKey, LocalDateTime> doneByKey = new HashMap<>();
        for (CareHistoryRepository.DoneTaskKey key :
                careHistoryRepository.findDoneTaskKeysInRange(userId, startOfToday, endOfToday)) {
            doneByKey.put(new TaskKey(key.getPlantId(), key.getTaskType()), key.getDoneAt());
        }

        List<CareSchedule> schedules = careScheduleRepository.findActiveSchedulesByUserId(userId);

        List<TodayTask> tasks = new ArrayList<>();
        for (CareSchedule schedule : schedules) {
            // Пропускаем растения из локаций на паузе (issue #250).
            Location location = schedule.getPlant().getLocation();
            if (location != null && location.isPaused()) {
                continue;
            }

            TaskKey key = new TaskKey(schedule.getPlant().getId(), schedule.getTaskType());
            LocalDateTime doneAt = doneByKey.get(key);
            boolean pending = !schedule.getNextDueAt().isAfter(endOfToday);
            if (pending || doneAt != null) {
                tasks.add(new TodayTask(schedule, doneAt));
            }
        }

        // pending (doneAt == null) сначала, по nextDueAt ASC; done — в конце, по doneAt ASC.
        tasks.sort(Comparator
                .comparing((TodayTask t) -> t.doneAt() != null)
                .thenComparing(t -> t.doneAt() != null ? t.doneAt() : t.schedule().getNextDueAt()));

        return tasks;
    }

    /**
     * Текущий момент в UTC wall-clock — граница для расчёта overdue.
     */
    public LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /**
     * Задача на сегодня: расписание + опциональный момент отметки «сделано».
     * {@code doneAt == null} — pending.
     */
    public record TodayTask(CareSchedule schedule, LocalDateTime doneAt) {
    }

    private record TaskKey(Long plantId, TaskType taskType) {
    }
}
