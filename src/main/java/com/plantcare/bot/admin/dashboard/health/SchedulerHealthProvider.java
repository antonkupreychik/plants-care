package com.plantcare.bot.admin.dashboard.health;

import com.plantcare.bot.admin.dashboard.dto.SchedulerHealth;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Читает Micrometer gauge с timestamp последнего тика шедулера.
 * Перебирает несколько вероятных имён метрики — когда определимся с
 * конкретным именем в реальном шедулере, оставим только его.
 *
 * Метрика должна возвращать epoch seconds (long).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SchedulerHealthProvider {

    private static final List<String> CANDIDATE_METRIC_NAMES = List.of(
            "plantcare.scheduler.last_tick.epoch_seconds",
            "plantcare.scheduler.last.tick",
            "app.scheduler.last_tick",
            "scheduler.last_tick"
    );

    private final MeterRegistry meterRegistry;

    public SchedulerHealth currentHealth() {
        for (String name : CANDIDATE_METRIC_NAMES) {
            Gauge gauge = meterRegistry.find(name).gauge();
            if (gauge == null) continue;

            double value = gauge.value();
            if (value <= 0 || Double.isNaN(value)) continue;

            try {
                Instant lastTickAt = Instant.ofEpochSecond((long) value);
                return SchedulerHealth.from(lastTickAt);
            } catch (Exception e) {
                log.warn("Bad scheduler metric value: name={}, value={}", name, value);
                return SchedulerHealth.unknown();
            }
        }
        log.debug("No scheduler metric found among candidates: {}", CANDIDATE_METRIC_NAMES);
        return SchedulerHealth.unknown();
    }
}
