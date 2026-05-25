package com.plantcare.core.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-тесты SchedulerHealthIndicator")
class SchedulerHealthIndicatorTest {

    private static final Duration THRESHOLD = Duration.ofMinutes(15);

    @Mock private SchedulerHealthTracker tracker;

    private SchedulerHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        indicator = new SchedulerHealthIndicator(tracker, THRESHOLD);
    }

    @Test
    @DisplayName("Пустой tracker → DOWN со специальным reason")
    void emptyTracker_returnsDown() {
        when(tracker.getLastTick()).thenReturn(Optional.empty());

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("reason");
        assertThat(health.getDetails().get("reason").toString())
                .contains("no tick recorded");
    }

    @Test
    @DisplayName("Свежий тик (10 секунд назад) → UP")
    void freshTick_returnsUp() {
        when(tracker.getLastTick()).thenReturn(Optional.of(Instant.now().minusSeconds(10)));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsKey("lastTickAt")
                .containsKey("ageSeconds")
                .containsEntry("thresholdSeconds", THRESHOLD.toSeconds());
        long age = (long) health.getDetails().get("ageSeconds");
        assertThat(age).isBetween(0L, 30L);
    }

    @Test
    @DisplayName("Старый тик (час назад) → DOWN")
    void staleTick_returnsDown() {
        when(tracker.getLastTick()).thenReturn(Optional.of(Instant.now().minusSeconds(3600)));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        long age = (long) health.getDetails().get("ageSeconds");
        assertThat(age).isGreaterThanOrEqualTo(3600);
    }

    @Test
    @DisplayName("Тик в пределах порога → UP (граница включена через <=)")
    void tickJustInsideThreshold_returnsUp() {
        // На 5 миллисекунд внутри порога — UP
        Instant inside = Instant.now().minus(THRESHOLD).plusMillis(5);
        when(tracker.getLastTick()).thenReturn(Optional.of(inside));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("Тик чуть-чуть за порогом → DOWN")
    void tickJustBeyondThreshold_returnsDown() {
        Instant beyondBoundary = Instant.now().minus(THRESHOLD).minusSeconds(1);
        when(tracker.getLastTick()).thenReturn(Optional.of(beyondBoundary));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("Details всегда содержат thresholdSeconds, чтобы оператор видел текущий порог")
    void detailsAlwaysIncludeThreshold() {
        when(tracker.getLastTick()).thenReturn(Optional.of(Instant.now().minusSeconds(10)));

        Health health = indicator.health();

        assertThat(health.getDetails())
                .containsEntry("thresholdSeconds", THRESHOLD.toSeconds());
    }

    @Test
    @DisplayName("Конфигурируемый порог: 3 минуты — тик 2 мин назад UP, 4 мин назад DOWN")
    void customThreshold_isRespected() {
        SchedulerHealthIndicator strict =
                new SchedulerHealthIndicator(tracker, Duration.ofMinutes(3));

        when(tracker.getLastTick()).thenReturn(Optional.of(Instant.now().minusSeconds(120)));
        assertThat(strict.health().getStatus()).isEqualTo(Status.UP);

        when(tracker.getLastTick()).thenReturn(Optional.of(Instant.now().minusSeconds(240)));
        assertThat(strict.health().getStatus()).isEqualTo(Status.DOWN);
    }
}
