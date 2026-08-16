package com.plantcare.core.errorlog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Retention журнала ошибок (issue #97, AC «Автоудаление старых записей»).
 * Clock подменён — иначе граница 30 дней непроверяема.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ErrorLogRetentionScheduler — автоудаление старше retention (#97)")
class ErrorLogRetentionSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-05-20T03:40:00Z");

    @Mock
    private ErrorLogRepository repository;

    @Test
    @DisplayName("should_delete_entries_older_than_retention_when_purging")
    void should_delete_entries_older_than_retention_when_purging() {
        when(repository.deleteOlderThan(any())).thenReturn(7);
        ErrorLogRetentionScheduler scheduler = scheduler(30);

        int deleted = scheduler.purgeOldEntries();

        assertThat(deleted).isEqualTo(7);
        ArgumentCaptor<Instant> threshold = ArgumentCaptor.forClass(Instant.class);
        org.mockito.Mockito.verify(repository).deleteOlderThan(threshold.capture());
        assertThat(threshold.getValue()).isEqualTo(Instant.parse("2026-04-20T03:40:00Z"));
    }

    @Test
    @DisplayName("should_use_configured_window_when_retention_days_are_custom")
    void should_use_configured_window_when_retention_days_are_custom() {
        when(repository.deleteOlderThan(any())).thenReturn(0);
        ErrorLogRetentionScheduler scheduler = scheduler(7);

        scheduler.purgeOldEntries();

        ArgumentCaptor<Instant> threshold = ArgumentCaptor.forClass(Instant.class);
        org.mockito.Mockito.verify(repository).deleteOlderThan(threshold.capture());
        assertThat(threshold.getValue()).isEqualTo(Instant.parse("2026-05-13T03:40:00Z"));
    }

    /**
     * Задача идёт по cron в UTC, но {@code Clock} машины может быть каким угодно —
     * порог обязан считаться от instant'а, а не от локальной даты.
     */
    @Test
    @DisplayName("should_keep_threshold_in_utc_when_clock_zone_is_not_utc")
    void should_keep_threshold_in_utc_when_clock_zone_is_not_utc() {
        when(repository.deleteOlderThan(any())).thenReturn(0);
        Clock almaty = Clock.fixed(NOW, ZoneId.of("Asia/Almaty"));
        ErrorLogRetentionScheduler scheduler = new ErrorLogRetentionScheduler(
                repository, new ErrorLogProperties(null, null, null, null, 30, null, null), almaty);

        scheduler.purgeOldEntries();

        ArgumentCaptor<Instant> threshold = ArgumentCaptor.forClass(Instant.class);
        org.mockito.Mockito.verify(repository).deleteOlderThan(threshold.capture());
        assertThat(threshold.getValue()).isEqualTo(Instant.parse("2026-04-20T03:40:00Z"));
    }

    private ErrorLogRetentionScheduler scheduler(int retentionDays) {
        return new ErrorLogRetentionScheduler(
                repository,
                new ErrorLogProperties(null, null, null, null, retentionDays, null, null),
                Clock.fixed(NOW, ZoneId.of("UTC")));
    }
}
