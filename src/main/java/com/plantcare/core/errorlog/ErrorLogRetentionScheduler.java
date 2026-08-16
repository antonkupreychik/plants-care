package com.plantcare.core.errorlog;

import com.plantcare.core.observability.SentryTags;
import com.plantcare.core.observability.SentryTags.Layer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Автоудаление старых записей журнала ошибок (issue #97, AC «Автоудаление старых записей»).
 *
 * <p>Раз в сутки удаляет всё старше {@code plants.error-log.retention-days} (по умолчанию
 * 30 дней). Идемпотентно: повторный запуск удалит 0 строк.
 *
 * <p>Партиционирование по месяцам, о котором говорит issue, не делается — при retention
 * в 30 дней DELETE по {@code idx_error_logs_created_at} дешевле, чем сопровождение
 * партиций. См. комментарий в {@code V56__create_error_logs.sql}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ErrorLogRetentionScheduler {

    private final ErrorLogRepository repository;
    private final ErrorLogProperties properties;
    private final Clock clock;

    /** Каждые сутки в 03:40 UTC — со смещением от прочих ночных задач. */
    @Scheduled(cron = "0 40 3 * * *", zone = "UTC")
    @SchedulerLock(name = "ErrorLogRetentionScheduler_purge",
            lockAtMostFor = "PT30M", lockAtLeastFor = "PT10M")
    public void purge() {
        SentryTags.runWithLayer(Layer.SCHEDULER, "ErrorLogRetentionScheduler", this::purgeOldEntries);
    }

    /** Тело задачи без Sentry-обвязки — точка вызова из тестов. */
    public int purgeOldEntries() {
        Instant threshold = clock.instant().minus(Duration.ofDays(properties.retentionDays()));
        int deleted = repository.deleteOlderThan(threshold);
        if (deleted > 0) {
            log.info("Error log retention: deleted {} entries older than {}", deleted, threshold);
        }
        return deleted;
    }
}
