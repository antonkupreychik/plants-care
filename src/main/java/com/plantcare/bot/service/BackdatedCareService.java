package com.plantcare.bot.service;

import com.plantcare.bot.domain.CareHistory;
import com.plantcare.bot.domain.CareSchedule;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.TaskType;
import com.plantcare.bot.repository.CareHistoryRepository;
import com.plantcare.bot.repository.CareScheduleRepository;
import com.plantcare.bot.util.TimezoneSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Ретро-отметка ухода (issue #118): юзер фиксирует, что уже полил/опрыскал/удобрил
 * растение в прошлом, а бот пересчитывает следующее напоминание от этой даты,
 * а не от «сейчас».
 *
 * <p>Сервис изолирует бизнес-правила: окно «не старше 7 дней», запрет даты в
 * будущем и идемпотентность по «один тип-задачи в один локальный день юзера».
 * Возвращает {@link BackdatedCareResult} — нет исключений на нормальные сценарии,
 * чтобы callback-сервис мог легко сматчить и показать юзеру нормальный текст.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BackdatedCareService {

    /** Максимальная глубина ретро — старше этого ставить нельзя. */
    public static final int MAX_BACKDATE_DAYS = 7;

    /** Условный «полдень» локального дня — стандартное время, которым размечаем backdated done. */
    private static final LocalTime NOON = LocalTime.NOON;

    private final CareHistoryRepository careHistoryRepository;
    private final CareScheduleRepository careScheduleRepository;
    private final com.plantcare.bot.seasonal.service.SeasonalIntervalService seasonalIntervalService;
    private final Clock clock;

    /**
     * Записать ретро-ухаживание за {@code doneDate} (локальный день юзера).
     *
     * <p>Алгоритм:
     * <ol>
     *   <li>Валидируем диапазон: дата не в будущем, не старше {@link #MAX_BACKDATE_DAYS}.</li>
     *   <li>Идемпотентность: если уже есть активная запись того же {@code plant+taskType}
     *       за тот же локальный день юзера — возвращаем {@link Outcome#ALREADY_RECORDED}.</li>
     *   <li>Иначе пишем {@link CareHistory} с {@code doneAt} = полдень локального дня
     *       (в UTC). Ретро всегда считаем {@code onTime=true} — юзер сам признался,
     *       что ухаживал; «late» не имеет смысла в backdate-сценарии.</li>
     *   <li>Сдвигаем активное расписание {@code rescheduleFrom(doneAt, effective)}.
     *       Если активного расписания нет — это не ошибка (юзер мог отключить тип
     *       ухода), история всё равно пишется.</li>
     * </ol>
     */
    @Transactional
    public BackdatedCareResult recordBackdatedCare(Plant plant, TaskType taskType, LocalDate doneDate) {
        if (plant == null || taskType == null || doneDate == null) {
            throw new IllegalArgumentException("plant, taskType, doneDate must not be null");
        }

        User user = plant.getUser();
        ZoneId userZone = TimezoneSupport.zoneOf(user);
        LocalDate today = LocalDate.now(clock.withZone(userZone));

        if (doneDate.isAfter(today)) {
            return new BackdatedCareResult(Outcome.IN_FUTURE, doneDate, null);
        }
        if (doneDate.isBefore(today.minusDays(MAX_BACKDATE_DAYS))) {
            return new BackdatedCareResult(Outcome.TOO_OLD, doneDate, null);
        }

        // Полдень локального дня → UTC LocalDateTime (как остальной код проекта).
        LocalDateTime doneAtUtc = doneDate.atTime(NOON)
                .atZone(userZone)
                .withZoneSameInstant(ZoneOffset.UTC)
                .toLocalDateTime();

        // Окно «целый локальный день» в UTC — [startOfDayUtc; startOfNextDayUtc).
        LocalDateTime dayStartUtc = doneDate.atStartOfDay(userZone)
                .withZoneSameInstant(ZoneOffset.UTC)
                .toLocalDateTime();
        LocalDateTime nextDayStartUtc = doneDate.plusDays(1).atStartOfDay(userZone)
                .withZoneSameInstant(ZoneOffset.UTC)
                .toLocalDateTime();

        boolean dup = careHistoryRepository.existsActiveByPlantIdAndTaskTypeInRange(
                plant.getId(), taskType, dayStartUtc, nextDayStartUtc);
        if (dup) {
            return new BackdatedCareResult(Outcome.ALREADY_RECORDED, doneDate, null);
        }

        CareHistory history = CareHistory.builder()
                .plant(plant)
                .taskType(taskType)
                .doneAt(doneAtUtc)
                .onTime(true)
                .note("backdated")
                .build();
        careHistoryRepository.save(history);

        Optional<CareSchedule> scheduleOpt = careScheduleRepository
                .findByPlantIdAndTaskType(plant.getId(), taskType)
                .filter(CareSchedule::isActive);

        LocalDateTime nextDueAt = null;
        if (scheduleOpt.isPresent()) {
            CareSchedule schedule = scheduleOpt.get();
            int effective = seasonalIntervalService.effectiveIntervalDays(
                    plant, user, schedule.getIntervalDays());
            schedule.rescheduleFrom(doneAtUtc, effective);
            careScheduleRepository.save(schedule);
            nextDueAt = schedule.getNextDueAt();
        }

        log.info("Backdated care recorded: plant={} taskType={} doneDate={} nextDueAt={}",
                plant.getId(), taskType, doneDate, nextDueAt);

        return new BackdatedCareResult(Outcome.CREATED, doneDate, nextDueAt);
    }

    /**
     * Результат {@link #recordBackdatedCare}. Содержит как исход, так и
     * {@code nextDueAt} следующего напоминания (для {@link Outcome#CREATED})
     * — чтобы вызывающий слой мог сразу сформировать текст подтверждения
     * без повторного запроса в БД.
     *
     * <p>{@code nextDueAt} может быть {@code null}, если у растения нет
     * активного расписания этого типа (юзер мог его отключить).
     */
    public record BackdatedCareResult(Outcome outcome, LocalDate doneDate, LocalDateTime nextDueAt) {

        public boolean isCreated() {
            return outcome == Outcome.CREATED;
        }
    }

    public enum Outcome {
        /** Запись создана, расписание сдвинуто. */
        CREATED,
        /** За тот же локальный день уже есть активная запись по этому taskType. */
        ALREADY_RECORDED,
        /** Дата старше {@link #MAX_BACKDATE_DAYS} дней назад. */
        TOO_OLD,
        /** Дата в будущем относительно сегодняшнего локального дня юзера. */
        IN_FUTURE
    }
}
