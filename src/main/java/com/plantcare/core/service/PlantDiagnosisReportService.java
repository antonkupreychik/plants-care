package com.plantcare.core.service;

import com.plantcare.core.domain.CareSchedule;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.enums.HealthZone;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.repository.CareHistoryRepository;
import com.plantcare.core.repository.CareScheduleRepository;
import com.plantcare.core.util.TimeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Issue #193 (mobile screen 15 «Диагноз»): пассивная диагностика растения
 * поверх уже имеющихся данных — без ИИ и без интерактивного опросника.
 * Новой схемы БД не требует (по аналогии с health #138).
 *
 * <p><b>Не путать</b> с {@code com.plantcare.bot.diagnosis.PlantDiagnosisService}
 * и {@code com.plantcare.core.diagnosis.DiagnosisRuleEngine} — это движок
 * интерактивного опросника, здесь же пассивный отчёт.
 *
 * <p>Используются ровно два сигнала:
 * <ol>
 *   <li>Просроченные активные расписания ухода ({@code active = true},
 *       {@code nextDueAt <= now}). «На сколько дней просрочено» считается в
 *       <b>таймзоне пользователя</b> как разница календарных дней (зеркало
 *       {@code HealthScoreService.windowStartUtc} / {@link TimeUtils#safeZone}).
 *       {@code nextDueAt} хранится как UTC wall-clock {@code LocalDateTime}.</li>
 *   <li>Health-зона из {@link HealthScoreService#computeForPlant(Plant)}
 *       ({@link HealthZone#RED} = сигнал «уход нерегулярный»).</li>
 * </ol>
 *
 * <p>Порог «мало данных» — тот же {@link CareHistoryService#MIN_ACTIONS_FOR_STATS}
 * ({@code < 3} активных записей → диагноз не строим).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlantDiagnosisReportService {

    private static final String INSUFFICIENT_DATA_HINT =
            "Пока мало данных для диагноза — продолжай отмечать уход";

    private final Clock clock;
    private final CareScheduleRepository careScheduleRepository;
    private final CareHistoryRepository careHistoryRepository;
    private final HealthScoreService healthScoreService;

    /**
     * Серьёзность проблемы. Порядок констант = порядок убывания приоритета,
     * чтобы сортировать по {@link Enum#ordinal()}.
     */
    public enum Severity {
        HIGH, MEDIUM, LOW
    }

    /**
     * Отдельная выявленная проблема. {@code code} — семантический строковый код
     * (forward-совместимость с новыми кодами), {@code recommendations} — подсказки,
     * которые попадут в общий список рекомендаций отчёта.
     */
    public record Issue(String code, Severity severity, String title, List<String> recommendations) {
    }

    /**
     * Результат пассивной диагностики: список проблем и плоский список рекомендаций.
     */
    public record DiagnosisReport(List<Issue> issues, List<String> recommendations) {
    }

    /**
     * Строит диагноз для растения. Зовётся из read-only транзакции с уже
     * загруженным {@code plant} (нужен lazy {@code user} для таймзоны).
     */
    @Transactional(readOnly = true)
    public DiagnosisReport diagnose(Plant plant) {
        Long plantId = plant.getId();

        long activeHistory = careHistoryRepository.countActiveByPlantId(plantId);
        if (activeHistory < CareHistoryService.MIN_ACTIONS_FOR_STATS) {
            log.debug("Diagnosis for plant={}: insufficient data (active={})", plantId, activeHistory);
            return new DiagnosisReport(List.of(), List.of(INSUFFICIENT_DATA_HINT));
        }

        String timezone = plant.getUser() != null ? plant.getUser().getTimezone() : null;
        LocalDate today = today(timezone);

        List<Issue> issues = new ArrayList<>();

        for (CareSchedule schedule : careScheduleRepository.findAllByPlantId(plantId)) {
            if (!schedule.isActive() || schedule.getNextDueAt() == null) {
                continue;
            }
            long daysOverdue = daysOverdue(schedule, timezone, today);
            if (daysOverdue <= 0) {
                continue;
            }
            issues.add(issueForOverdue(schedule, daysOverdue));
        }

        HealthScoreService.HealthScore health = healthScoreService.computeForPlant(plant);
        if (!health.insufficientData() && health.zone() == HealthZone.RED) {
            issues.add(new Issue(
                    "NEGLECTED",
                    Severity.MEDIUM,
                    "Уход нерегулярный",
                    List.of("Вернись к регулярному графику ухода")));
        }

        issues.sort(Comparator
                .comparingInt((Issue i) -> i.severity().ordinal())
                .thenComparingInt(PlantDiagnosisReportService::taskOrderOf));

        LinkedHashSet<String> recommendations = new LinkedHashSet<>();
        for (Issue issue : issues) {
            recommendations.addAll(issue.recommendations());
        }

        log.debug("Diagnosis for plant={}: issues={} recommendations={}",
                plantId, issues.size(), recommendations.size());
        return new DiagnosisReport(List.copyOf(issues), List.copyOf(recommendations));
    }

    /**
     * Маппинг просроченного расписания → проблема. Для WATERING различаем
     * «пересушен» (просрочка > одного интервала) и «нужен полив» (≤ интервала).
     */
    private Issue issueForOverdue(CareSchedule schedule, long daysOverdue) {
        TaskType taskType = schedule.getTaskType();
        return switch (taskType) {
            case WATERING -> daysOverdue > schedule.getIntervalDays()
                    ? new Issue("UNDERWATERED", Severity.HIGH, "Пересушен",
                            List.of("Полей растение сегодня", "Проверь, не пересох ли грунт"))
                    : new Issue("UNDERWATERED", Severity.MEDIUM, "Нужен полив",
                            List.of("Полей растение сегодня"));
            case FERTILIZING -> new Issue("UNDERFED", Severity.LOW, "Не хватает подкормки",
                    List.of("Подкорми растение по графику"));
            case MISTING -> new Issue("LOW_HUMIDITY", Severity.LOW, "Низкая влажность",
                    List.of("Опрыскай растение"));
            case SOIL_CHECK -> new Issue("SOIL_CHECK_DUE", Severity.LOW, "Пора проверить грунт",
                    List.of("Проверь состояние грунта"));
        };
    }

    /**
     * На сколько календарных дней просрочено расписание в TZ юзера. {@code nextDueAt}
     * хранится как UTC wall-clock: переводим в Instant как UTC, затем в дату TZ юзера
     * и считаем разницу с «сегодня» в той же TZ. {@code <= 0} — не просрочено.
     */
    private long daysOverdue(CareSchedule schedule, String timezone, LocalDate today) {
        ZoneId zone = TimeUtils.safeZone(timezone);
        LocalDate dueDate = schedule.getNextDueAt()
                .toInstant(ZoneOffset.UTC)
                .atZone(zone)
                .toLocalDate();
        return today.toEpochDay() - dueDate.toEpochDay();
    }

    /** Сегодняшняя дата в TZ юзера (через инжектируемый {@link Clock}). */
    private LocalDate today(String timezone) {
        return clock.instant().atZone(TimeUtils.safeZone(timezone)).toLocalDate();
    }

    /** Стабильный tie-break внутри одной severity — по порядку типов ухода. */
    private static int taskOrderOf(Issue issue) {
        return switch (issue.code()) {
            case "UNDERWATERED" -> TaskType.WATERING.ordinal();
            case "LOW_HUMIDITY" -> TaskType.MISTING.ordinal();
            case "UNDERFED" -> TaskType.FERTILIZING.ordinal();
            case "SOIL_CHECK_DUE" -> TaskType.SOIL_CHECK.ordinal();
            default -> TaskType.values().length; // NEGLECTED и прочие — в конце
        };
    }
}
