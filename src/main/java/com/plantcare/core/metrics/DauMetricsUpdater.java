package com.plantcare.core.metrics;

import com.plantcare.core.observability.SentryTags;
import com.plantcare.core.observability.SentryTags.Layer;
import com.plantcare.core.repository.CareHistoryRepository;
import io.sentry.Sentry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Раз в час пересчитывает DAU (уникальные пользователи с care_event
 * за последние 24h) и пишет в {@link MetricsService}. Micrometer-gauge
 * затем читает значение лениво при scrape Prometheus.
 *
 * <p>Сделано отдельным шедулером, а не gauge-функцией с прямым обращением
 * к БД, чтобы:
 * <ul>
 *   <li>не висеть на DB во время каждого scrape Prometheus (по дефолту
 *       раз в 15 сек) — это бесполезная нагрузка;</li>
 *   <li>значение было устойчивым: между двумя scrape'ами DAU не должен
 *       прыгать, иначе графики «дёргает».</li>
 * </ul>
 *
 * <p>Cron в UTC. Идемпотентность не требуется — повторный пересчёт
 * перезатирает то же значение тем же значением.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DauMetricsUpdater {

    private static final int WINDOW_HOURS = 24;

    private final CareHistoryRepository careHistoryRepository;
    private final MetricsService metricsService;
    private final Clock clock;

    /**
     * Прогреваем значение сразу при старте, чтобы gauge не отдавал 0
     * первые 60 минут после деплоя. Безопасно — это readOnly запрос.
     */
    @PostConstruct
    void warmUp() {
        try {
            refresh();
        } catch (Exception e) {
            // Не валим контекст из-за метрики. Просто оставляем 0,
            // следующий @Scheduled-тик переcчитает.
            log.warn("Initial DAU refresh failed, will retry on schedule: {}", e.getMessage());
            Sentry.captureException(e);
        }
    }

    /**
     * Cron «в начале каждого часа в UTC». Совпадает с шедулером напоминаний
     * по таймзоне, чтобы Railway-логи легче коррелировать.
     */
    @Scheduled(cron = "0 0 * * * *", zone = "UTC")
    @Transactional(readOnly = true)
    public void refresh() {
        // Issue #114: изолированный scope на тик — тег layer не течёт на соседние
        // задачи shared-потока @Scheduled.
        SentryTags.runWithLayer(Layer.SCHEDULER, "DauMetricsUpdater", () -> {
            LocalDateTime cutoff = LocalDateTime.now(clock).minusHours(WINDOW_HOURS);
            long dau = careHistoryRepository.countDistinctActiveUsersSince(cutoff);
            metricsService.updateActiveDau(dau);
            log.info("DAU updated: {} active users in last {}h (cutoff={})", dau, WINDOW_HOURS, cutoff);
        });
    }
}
