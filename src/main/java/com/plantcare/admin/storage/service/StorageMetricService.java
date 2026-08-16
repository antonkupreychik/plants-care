package com.plantcare.admin.storage.service;

import com.plantcare.admin.storage.dto.StorageOverviewDto;
import com.plantcare.admin.storage.repository.AdminStorageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Суточный снапшот объёма хранилища для графика роста на /admin/storage (issue #101).
 *
 * <p>Issue предлагала считать объём листингом бакета ({@code aws s3 ls --summarize}
 * / R2 API). Здесь считаем по реестру {@code photos} с предикатом
 * {@code purged_at IS NULL} — то есть по тем объектам, которые мы положили в
 * бакет и ещё не удаляли. Причины: листинг стоит API-вызовов на каждый прогон,
 * требует живого S3 в тестах и по-разному пагинируется у S3-совместимых
 * провайдеров. Ценой является дрейф, если объект удалят мимо приложения —
 * приемлемо, в бакет никто, кроме сервиса, не пишет.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageMetricService {

    private final AdminStorageRepository repository;
    private final AdminStorageService storageService;
    private final Clock clock;

    /**
     * Пишет снапшот за сегодняшнюю дату (UTC).
     *
     * <p>Идемпотентно: {@code UPSERT} по {@code metric_date}, повторный прогон в
     * те же сутки просто перезапишет строку свежим значением, а не упадёт на PK.
     *
     * @return записанный снапшот
     */
    @Transactional
    public StorageOverviewDto captureDailySnapshot() {
        StorageOverviewDto overview = storageService.currentOverview();
        Instant now = Instant.now(clock);
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();

        repository.upsertMetric(today, overview.bucketBytes(), overview.bucketCount(), now);

        log.info("Storage metric snapshot: date={}, bytes={}, count={}",
                today, overview.bucketBytes(), overview.bucketCount());
        return overview;
    }
}
