package com.plantcare.core.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Хранит время последнего успешно завершённого тика {@link NotificationSchedulerService}
 * (issue #28). Единая «точка истины», которую читают:
 *   1. {@link SchedulerHealthIndicator} → /actuator/health (для UptimeRobot и алертинга);
 *   2. админ-дашборд через {@code SchedulerHealthProvider} → Micrometer-gauge
 *      {@code plantcare.scheduler.last_tick.epoch_seconds}.
 *
 * <p>Реализация in-memory ({@link AtomicReference}). Single-instance deployment делает
 * запись в БД/Redis избыточной: health читает только текущее значение, восстанавливать
 * его после рестарта не нужно — для этого есть init в {@link #initializeFromAppStart()}.
 *
 * <p>В будущем при переходе на multi-instance запись через AtomicReference нужно будет
 * заменить на shared storage (Redis). Контракт публичных методов останется тем же.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchedulerHealthTracker {

    /**
     * Имя метрики, которое уже ожидает админ-дашборд ({@code SchedulerHealthProvider}).
     * Изменение значения сломает дашборд — менять только синхронно.
     */
    static final String METRIC_NAME = "plantcare.scheduler.last_tick.epoch_seconds";

    private final MeterRegistry meterRegistry;

    private final AtomicReference<Instant> lastTick = new AtomicReference<>();

    /**
     * Инициализируем «псевдо-тик» временем старта приложения, чтобы первые минуты
     * после деплоя health не возвращал DOWN из-за того, что {@code @Scheduled} ещё
     * не успел запуститься. Реальный тик перепишет это значение в течение 60 сек.
     *
     * <p>Если шедулер действительно сломан с самого начала — health окрасится в DOWN
     * через max-tick-age после старта, как и в любом другом случае. Гонки между
     * @PostConstruct и первым тиком безопасны: оба пишут через {@code set()},
     * последний по времени побеждает.
     *
     * <p>Также регистрирует Micrometer-gauge с epoch seconds — единый источник
     * данных и для health-indicator'а, и для админ-дашборда.
     */
    @PostConstruct
    void initializeFromAppStart() {
        lastTick.set(Instant.now());

        Gauge.builder(METRIC_NAME, lastTick, ref -> {
                    Instant v = ref.get();
                    return v != null ? (double) v.getEpochSecond() : 0.0;
                })
                .description("Epoch seconds of the last successful notification scheduler tick")
                .register(meterRegistry);

        log.info("Scheduler health tracker initialized at {} (gauge '{}' registered)",
                lastTick.get(), METRIC_NAME);
    }

    /**
     * Зафиксировать факт успешного завершения тика. Вызывается шедулером в самом
     * конце итерации — так мы детектируем не только мёртвый поток, но и
     * перманентно-падающий тик.
     */
    public void recordTick() {
        lastTick.set(Instant.now());
    }

    /**
     * Текущее значение last-tick. Пусто только если по какой-то причине @PostConstruct
     * не отработал (в нормальном жизненном цикле Spring это не должно происходить).
     */
    public Optional<Instant> getLastTick() {
        return Optional.ofNullable(lastTick.get());
    }
}
