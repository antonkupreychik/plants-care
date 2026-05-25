package com.plantcare.core.service;

import com.plantcare.core.config.HealthScoreProperties;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.enums.HealthZone;
import com.plantcare.core.repository.CareHistoryRepository;
import com.plantcare.core.util.TimeUtils;
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

/**
 * Issue #138: health-score растения — числовой балл 0–100 поверх уже имеющихся
 * данных ({@code care_history}, фото, заметки) без новой схемы и без материализации.
 *
 * <p>Формула (см. {@link HealthScoreProperties}): основной вес — % on-time за окно
 * 30 дней, плюс фиксированные бонусы за наличие фото и заметок, с клампом в [0, 100].
 *
 * <p><b>Источник on-time:</b> переиспользуется расчёт из #51 на уровне репозитория
 * ({@link CareHistoryRepository#countActiveByPlantIdSince} /
 * {@link CareHistoryRepository#countActiveOnTimeByPlantIdSince}). Формула on-time не
 * дублируется. Отличие от {@code CareHistoryService.getPlantStats}: там граница окна
 * берётся по {@code LocalDateTime.now()} (фактически UTC), а AC #138 требует окно в
 * TZ юзера — поэтому границу {@code since} считаем здесь сами (начало дня
 * «30 дней назад» в TZ юзера → UTC) и зовём repo-методы напрямую.
 *
 * <p>Порог «мало данных» — тот же {@link CareHistoryService#MIN_ACTIONS_FOR_STATS}
 * (&lt;3 активных записей → балл не считаем).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HealthScoreService {

    /** Окно on-time-% — как в #51 («за последние 30 дней»). */
    private static final int ON_TIME_WINDOW_DAYS = 30;

    private final CareHistoryRepository careHistoryRepository;
    private final HealthScoreProperties properties;
    private final Clock clock;

    /**
     * Health-score одного растения. Если активных записей &lt;
     * {@link CareHistoryService#MIN_ACTIONS_FOR_STATS} — возвращается
     * {@link HealthScore#insufficient()} (балл не считаем).
     *
     * <p>Требует, чтобы {@code plant.getUser()} был доступен (TZ юзера). Зовётся из
     * сервисов с открытой read-only транзакцией — lazy {@code user} инициализируется.
     */
    @Transactional(readOnly = true)
    public HealthScore computeForPlant(Plant plant) {
        Long plantId = plant.getId();
        long total = careHistoryRepository.countActiveByPlantId(plantId);

        if (total < CareHistoryService.MIN_ACTIONS_FOR_STATS) {
            log.debug("Health score for plant={}: insufficient data (total={})", plantId, total);
            return HealthScore.insufficient();
        }

        String timezone = plant.getUser() != null ? plant.getUser().getTimezone() : null;
        LocalDateTime since = windowStartUtc(timezone);

        long actionsInWindow = careHistoryRepository.countActiveByPlantIdSince(plantId, since);
        long onTimeInWindow = careHistoryRepository.countActiveOnTimeByPlantIdSince(plantId, since);

        int onTimePct = actionsInWindow == 0
                ? 0
                : (int) Math.round(100.0 * onTimeInWindow / actionsInWindow);

        int score = scoreFrom(onTimePct, plant);
        HealthZone zone = zoneFor(score);

        log.debug("Health score for plant={}: onTimePct={} score={} zone={}",
                plantId, onTimePct, score, zone);
        return HealthScore.of(score, zone);
    }

    /**
     * Прозрачная формула: вес on-time + бонусы, клампится в [0, 100].
     * Бонусы — за непустые фото ({@link Plant#getPhotoFileId()}) и заметки
     * ({@link Plant#getNotes()}).
     */
    private int scoreFrom(int onTimePct, Plant plant) {
        int raw = (int) Math.round(onTimePct * properties.onTimeWeight());

        if (plant.getPhotoFileId() != null && !plant.getPhotoFileId().isBlank()) {
            raw += properties.photoBonus();
        }
        if (plant.getNotes() != null && !plant.getNotes().isBlank()) {
            raw += properties.notesBonus();
        }

        return Math.clamp(raw, 0, 100);
    }

    /** Маппинг балла в зону по конфигурируемым порогам. */
    public HealthZone zoneFor(int score) {
        if (score >= properties.greenThreshold()) {
            return HealthZone.GREEN;
        }
        if (score >= properties.yellowThreshold()) {
            return HealthZone.YELLOW;
        }
        return HealthZone.RED;
    }

    /**
     * Граница окon-time-окна в UTC: начало дня «30 дней назад» в TZ юзера.
     * Repo-методы фильтруют {@code doneAt > since} (doneAt хранится в UTC), поэтому
     * вычисляем локальную полночь нужного дня и переводим в UTC.
     */
    private LocalDateTime windowStartUtc(String timezone) {
        ZoneId zone = TimeUtils.safeZone(timezone);
        LocalDate windowStartDay = clock.instant().atZone(zone).toLocalDate()
                .minusDays(ON_TIME_WINDOW_DAYS);
        return windowStartDay.atStartOfDay(zone)
                .withZoneSameInstant(ZoneOffset.UTC)
                .toLocalDateTime();
    }

    /**
     * Результат расчёта health-score (issue #138).
     *
     * @param insufficientData {@code true} → данных мало (&lt;3 действий), score/zone
     *                         не заполнены; UI показывает «Пока мало данных»
     * @param score            балл 0–100 (валиден только если {@code !insufficientData})
     * @param zone             зона (валидна только если {@code !insufficientData})
     */
    public record HealthScore(boolean insufficientData, int score, HealthZone zone) {

        private static final HealthScore INSUFFICIENT = new HealthScore(true, 0, null);

        public static HealthScore insufficient() {
            return INSUFFICIENT;
        }

        public static HealthScore of(int score, HealthZone zone) {
            return new HealthScore(false, score, zone);
        }
    }
}
