package com.plantcare.core.service;

import com.plantcare.core.observability.SentryTags;
import com.plantcare.core.observability.SentryTags.Layer;
import com.plantcare.core.repository.NotificationDeliveryEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;

/**
 * Ретенция журнала доставок (issue #95).
 *
 * <p>Журнал пишется на КАЖДУЮ попытку доставки — без чистки таблица растёт
 * линейно по числу напоминаний и за год съест диск маленького Railway-инстанса.
 * Дашборд смотрит окно в часах (дефолт — 24), поэтому месяца истории заведомо
 * хватает и на ретроспективу «когда сломалось».
 *
 * <p>Идемпотентно: повторный запуск удалит 0 дополнительных строк. Cron в UTC,
 * как требует конвенция шедулеров.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationDeliveryCleanupScheduler {

    /** Сколько истории доставок держим. */
    public static final Duration RETENTION = Duration.ofDays(30);

    private final NotificationDeliveryEventRepository eventRepository;
    private final Clock clock;

    /** Раз в сутки в 03:20 UTC — со смещением от прочих задач, вне пика напоминаний. */
    @Scheduled(cron = "0 20 3 * * *", zone = "UTC")
    @SchedulerLock(name = "NotificationDeliveryCleanupScheduler_cleanup",
            lockAtMostFor = "PT30M", lockAtLeastFor = "PT10M")
    @Transactional
    public void cleanup() {
        SentryTags.runWithLayer(Layer.SCHEDULER, "NotificationDeliveryCleanupScheduler", () -> {
            int deleted = eventRepository.deleteOlderThan(clock.instant().minus(RETENTION));
            if (deleted > 0) {
                log.info("Notification delivery events cleanup: deleted={}, retention={}",
                        deleted, RETENTION);
            }
        });
    }
}
