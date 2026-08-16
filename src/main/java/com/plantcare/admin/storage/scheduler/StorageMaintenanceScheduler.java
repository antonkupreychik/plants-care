package com.plantcare.admin.storage.scheduler;

import com.plantcare.admin.storage.service.PhotoPurgeService;
import com.plantcare.admin.storage.service.StorageMetricService;
import com.plantcare.core.observability.SentryTags;
import com.plantcare.core.observability.SentryTags.Layer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Суточное обслуживание фото-хранилища (issue #101): снапшот метрики и
 * физическая чистка просроченных soft-deleted объектов.
 *
 * <p>Обе задачи идемпотентны и обе под ShedLock — на нескольких репликах должна
 * отработать ровно одна: снапшот иначе перезапишется одинаковым значением
 * (безобидно), а чистка дважды сходит в S3 за тем же ключом (лишние вызовы).
 *
 * <p>Cron в UTC, как требует конвенция шедулеров проекта. Никаких транзакций на
 * этом уровне: чистка ходит в S3 и сама расставляет короткие транзакции внутри
 * (см. {@link PhotoPurgeService}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StorageMaintenanceScheduler {

    private final StorageMetricService storageMetricService;
    private final PhotoPurgeService photoPurgeService;

    /** Снапшот объёма — 03:05 UTC, до чистки: фиксируем «сколько было». */
    @Scheduled(cron = "0 5 3 * * *", zone = "UTC")
    @SchedulerLock(name = "StorageMaintenanceScheduler_metric",
            lockAtMostFor = "PT30M", lockAtLeastFor = "PT10M")
    public void captureStorageMetric() {
        SentryTags.runWithLayer(Layer.SCHEDULER, "StorageMaintenanceScheduler.metric",
                storageMetricService::captureDailySnapshot);
    }

    /**
     * Физическая чистка — 03:20 UTC, после снапшота.
     *
     * <p>{@code lockAtMostFor} с запасом: батч ходит в S3 по объекту, и на
     * большой очереди прогон длинный. Недоделанное подхватит следующий прогон —
     * операция идемпотентна.
     */
    @Scheduled(cron = "0 20 3 * * *", zone = "UTC")
    @SchedulerLock(name = "StorageMaintenanceScheduler_purge",
            lockAtMostFor = "PT2H", lockAtLeastFor = "PT10M")
    public void purgeExpiredPhotos() {
        SentryTags.runWithLayer(Layer.SCHEDULER, "StorageMaintenanceScheduler.purge",
                photoPurgeService::purgeExpired);
    }
}
