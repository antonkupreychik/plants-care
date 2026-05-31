package com.plantcare.core.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Unit-тесты SchedulerHealthTracker")
class SchedulerHealthTrackerTest {

    private MeterRegistry meterRegistry;
    private SchedulerHealthTracker tracker;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        tracker = new SchedulerHealthTracker(meterRegistry);
    }

    @Test
    @DisplayName("До @PostConstruct getLastTick возвращает пусто")
    void beforeInit_emptyOptional() {
        assertThat(tracker.getLastTick()).isEmpty();
    }

    @Test
    @DisplayName("initializeFromAppStart выставляет lastTick в текущее время")
    void initialize_setsCurrentTime() {
        Instant before = Instant.now();
        tracker.initializeFromAppStart();
        Instant after = Instant.now();

        Optional<Instant> lastTick = tracker.getLastTick();
        assertThat(lastTick).isPresent();
        assertThat(lastTick.get()).isBetween(before, after);
    }

    @Test
    @DisplayName("initializeFromAppStart регистрирует Micrometer-gauge с epoch seconds")
    void initialize_registersMicrometerGauge() {
        tracker.initializeFromAppStart();

        Gauge gauge = meterRegistry.find(SchedulerHealthTracker.METRIC_NAME).gauge();
        assertThat(gauge).as("gauge должен быть зарегистрирован для админ-дашборда").isNotNull();

        long expectedEpoch = tracker.getLastTick().orElseThrow().getEpochSecond();
        assertThat((long) gauge.value()).isEqualTo(expectedEpoch);
    }

    @Test
    @DisplayName("Gauge отдаёт актуальное значение после recordTick")
    void gauge_reflectsRecordedTick() {
        tracker.initializeFromAppStart();
        Gauge gauge = meterRegistry.find(SchedulerHealthTracker.METRIC_NAME).gauge();

        try { Thread.sleep(1100); } catch (InterruptedException ignored) {}  // следующая секунда
        tracker.recordTick();

        long expectedEpoch = tracker.getLastTick().orElseThrow().getEpochSecond();
        assertThat((long) gauge.value()).isEqualTo(expectedEpoch);
    }

    @Test
    @DisplayName("recordTick перезаписывает значение")
    void recordTick_overwritesPreviousValue() {
        tracker.initializeFromAppStart();
        Instant initial = tracker.getLastTick().orElseThrow();

        try { Thread.sleep(5); } catch (InterruptedException ignored) {}
        tracker.recordTick();
        Instant later = tracker.getLastTick().orElseThrow();

        assertThat(later).isAfter(initial);
    }

    @Test
    @DisplayName("Конкурентные recordTick не теряются (atomic update)")
    void concurrentRecordTicks_areAtomic() throws InterruptedException {
        Instant start = Instant.now();
        Thread[] threads = new Thread[10];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 100; j++) {
                    tracker.recordTick();
                }
            });
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();

        Optional<Instant> last = tracker.getLastTick();
        assertThat(last).isPresent();
        assertThat(last.get())
                .isAfterOrEqualTo(start)
                .isBeforeOrEqualTo(Instant.now().plus(Duration.ofSeconds(1)));
    }
}
