package com.plantcare.core.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Spring Boot {@link HealthIndicator}, который определяет работоспособность
 * шедулера уведомлений по возрасту последнего тика (issue #28).
 *
 * <p>Регистрируется в {@code /actuator/health} автоматически — Spring подхватывает
 * любой бин типа HealthIndicator и публикует его под именем компонента (имя класса
 * без суффикса HealthIndicator, в нижнем регистре → «scheduler»).
 *
 * <pre>
 * GET /actuator/health
 * → 200 / 503 в зависимости от агрегированного статуса всех indicator'ов
 * → JSON:
 *   {
 *     "status": "UP|DOWN",
 *     "components": {
 *       "scheduler": {
 *         "status": "UP|DOWN",
 *         "details": { "lastTickAt": "...", "ageSeconds": 30, "thresholdSeconds": 900 }
 *       },
 *       "db":         {...},
 *       "diskSpace":  {...}
 *     }
 *   }
 * </pre>
 *
 * Подробности (details) видны только аутентифицированным клиентам — управляется
 * через {@code management.endpoint.health.show-details=when-authorized} в
 * application.yml. UptimeRobot и подобные мониторы используют HTTP-статус
 * (200 = UP, 503 = DOWN), деталей им не нужно.
 */
@Slf4j
@Component
public class SchedulerHealthIndicator implements HealthIndicator {

    private final SchedulerHealthTracker tracker;
    private final Duration threshold;

    public SchedulerHealthIndicator(
            SchedulerHealthTracker tracker,
            @Value("${plants.scheduler.health.max-tick-age:PT15M}") Duration threshold
    ) {
        this.tracker = tracker;
        this.threshold = threshold;
        log.info("Scheduler health indicator configured with max-tick-age = {}", threshold);
    }

    @Override
    public Health health() {
        Optional<Instant> last = tracker.getLastTick();
        if (last.isEmpty()) {
            // Не должно случаться в нормальной жизни — SchedulerHealthTracker
            // выставляет значение в @PostConstruct. Но если каким-то чудом пусто,
            // сигналим DOWN — это явный сбой инициализации.
            return Health.down()
                    .withDetail("reason", "no tick recorded yet (tracker not initialized?)")
                    .withDetail("thresholdSeconds", threshold.toSeconds())
                    .build();
        }

        Instant lastInstant = last.get();
        Duration age = Duration.between(lastInstant, Instant.now());

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("lastTickAt", lastInstant.toString());
        details.put("ageSeconds", age.toSeconds());
        details.put("thresholdSeconds", threshold.toSeconds());

        if (age.compareTo(threshold) <= 0) {
            return Health.up().withDetails(details).build();
        }

        log.warn("Scheduler appears stuck: last tick was {} (age = {}s, threshold = {}s)",
                lastInstant, age.toSeconds(), threshold.toSeconds());
        return Health.down().withDetails(details).build();
    }
}
