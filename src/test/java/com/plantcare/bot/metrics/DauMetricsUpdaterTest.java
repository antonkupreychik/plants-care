package com.plantcare.bot.metrics;

import com.plantcare.bot.repository.CareHistoryRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты {@link DauMetricsUpdater}. Используем фиксированный {@link Clock},
 * чтобы проверить, что cutoff считается ровно как «сейчас − 24h»,
 * и реальный {@link MetricsService} с {@link SimpleMeterRegistry} — это даёт
 * сквозную проверку, что значение действительно доходит до Micrometer-gauge.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DauMetricsUpdater — unit-тесты")
class DauMetricsUpdaterTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-05-22T10:00:00Z");

    @Mock
    private CareHistoryRepository careHistoryRepository;

    private SimpleMeterRegistry registry;
    private MetricsService metricsService;
    private DauMetricsUpdater updater;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metricsService = new MetricsService(registry);
        ReflectionTestUtils.invokeMethod(metricsService, "registerGauges");

        Clock fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        updater = new DauMetricsUpdater(careHistoryRepository, metricsService, fixedClock);
    }

    @Test
    void should_query_repository_with_cutoff_24h_before_now_when_refresh_called() {
        when(careHistoryRepository.countDistinctActiveUsersSince(any())).thenReturn(0L);

        updater.refresh();

        ArgumentCaptor<LocalDateTime> cutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(careHistoryRepository).countDistinctActiveUsersSince(cutoffCaptor.capture());

        LocalDateTime expectedCutoff = LocalDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC).minusHours(24);
        assertThat(cutoffCaptor.getValue()).isEqualTo(expectedCutoff);
    }

    @Test
    void should_push_repository_result_into_metrics_service_when_refresh_called() {
        when(careHistoryRepository.countDistinctActiveUsersSince(any())).thenReturn(137L);

        updater.refresh();

        assertThat(metricsService.getActiveDau()).isEqualTo(137L);
        assertThat(registry.find(MetricsService.USERS_ACTIVE_DAU).gauge().value()).isEqualTo(137.0);
    }

    @Test
    void should_set_dau_to_zero_when_repository_returns_zero() {
        when(careHistoryRepository.countDistinctActiveUsersSince(any())).thenReturn(0L);

        updater.refresh();

        assertThat(metricsService.getActiveDau()).isZero();
        assertThat(registry.find(MetricsService.USERS_ACTIVE_DAU).gauge().value()).isEqualTo(0.0);
    }

    @Test
    void should_overwrite_previous_value_on_subsequent_refresh() {
        when(careHistoryRepository.countDistinctActiveUsersSince(any()))
                .thenReturn(50L)
                .thenReturn(10L);

        updater.refresh();
        assertThat(metricsService.getActiveDau()).isEqualTo(50L);

        updater.refresh();
        assertThat(metricsService.getActiveDau()).isEqualTo(10L);
    }

    @Test
    void should_not_throw_and_leave_gauge_at_zero_when_warm_up_repository_fails() {
        when(careHistoryRepository.countDistinctActiveUsersSince(any()))
                .thenThrow(new RuntimeException("DB unavailable on boot"));

        // warmUp ловит исключения и просто пишет в log — контекст не должен падать.
        ReflectionTestUtils.invokeMethod(updater, "warmUp");

        assertThat(metricsService.getActiveDau()).isZero();
    }

    @Test
    void should_propagate_repository_exception_from_scheduled_refresh() {
        when(careHistoryRepository.countDistinctActiveUsersSince(any()))
                .thenThrow(new RuntimeException("DB unavailable"));

        // refresh() сам по себе не глушит исключения — это работа @Scheduled-инфраструктуры
        // (она логнёт и продолжит). Поэтому здесь ожидаем, что throw пройдёт наверх.
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> updater.refresh())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DB unavailable");

        // Метрика осталась нетронутой (значит updateActiveDau не вызывался).
        assertThat(metricsService.getActiveDau()).isZero();
    }

    @Test
    void should_not_call_update_when_repository_throws() {
        // Sanity-check к предыдущему: убеждаемся, что updateActiveDau не дёрнут,
        // когда репозиторий упал — иначе мы могли бы записать «мусор» в gauge.
        MetricsService spy = org.mockito.Mockito.spy(new MetricsService(registry));
        ReflectionTestUtils.invokeMethod(spy, "registerGauges");
        DauMetricsUpdater localUpdater = new DauMetricsUpdater(
                careHistoryRepository, spy, Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

        when(careHistoryRepository.countDistinctActiveUsersSince(any()))
                .thenThrow(new RuntimeException("boom"));

        org.assertj.core.api.Assertions
                .assertThatThrownBy(localUpdater::refresh)
                .isInstanceOf(RuntimeException.class);

        verify(spy, never()).updateActiveDau(org.mockito.ArgumentMatchers.anyLong());
    }
}
