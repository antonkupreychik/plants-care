package com.plantcare.admin.storage.service;

import com.plantcare.admin.config.AdminProperties;
import com.plantcare.admin.storage.dto.PhotoRowDto;
import com.plantcare.admin.storage.dto.StorageDailyPointDto;
import com.plantcare.admin.storage.dto.StorageOverviewDto;
import com.plantcare.admin.storage.dto.StoragePageDto;
import com.plantcare.admin.storage.dto.TopUserStorageDto;
import com.plantcare.admin.storage.repository.AdminStorageRepository;
import com.plantcare.core.config.S3StorageProperties;
import com.plantcare.core.service.PhotoStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Читающая часть /admin/storage и секции «Фото» на странице юзера (issue #101).
 *
 * <p><b>Про расхождение со спекой.</b> Issue написана под Cloudflare R2, но
 * проект уехал на S3-совместимое хранилище Railway через AWS SDK v2 (issue #90).
 * Поэтому объём считается по реестру {@code photos}, а не листингом бакета:
 * листинг стоит API-вызовов, требует живого S3 в тестах и на S3-совместимых
 * провайдерах ведёт себя по-разному. {@code photos} — это и есть реестр всего,
 * что мы туда положили, а {@code purged_at} отмечает то, что оттуда убрали.
 *
 * <p>Не транзакционен: только читающие агрегаты через {@code JdbcTemplate}.
 */
@Slf4j
@Service
public class AdminStorageService {

    /** Глубина графика роста — полгода, как в issue. */
    private static final int GROWTH_MONTHS = 6;

    /** Сколько строк топа показываем. */
    private static final int TOP_USERS_LIMIT = 20;

    /** Верхняя граница грида фото на странице юзера — чтобы не рендерить тысячи плиток. */
    private static final int USER_PHOTOS_LIMIT = 200;

    private final AdminStorageRepository repository;
    private final PhotoStorageService photoStorageService;
    private final S3StorageProperties s3Properties;
    private final AdminProperties.Storage storageProperties;
    private final Clock clock;

    public AdminStorageService(
            AdminStorageRepository repository,
            PhotoStorageService photoStorageService,
            S3StorageProperties s3Properties,
            AdminProperties adminProperties,
            Clock clock
    ) {
        this.repository = repository;
        this.photoStorageService = photoStorageService;
        this.s3Properties = s3Properties;
        this.storageProperties = adminProperties.getStorage();
        this.clock = clock;
    }

    /** Всё, что нужно странице /admin/storage, за один вызов. */
    public StoragePageDto loadPage(int page) {
        long start = System.currentTimeMillis();

        int pageSize = Math.max(1, storageProperties.getRecentPageSize());
        int safePage = Math.max(0, page);

        StorageOverviewDto overview = repository.loadOverview(
                storageProperties.getPricePerGbMonth(), storageProperties.getRetentionDays());

        LocalDate from = LocalDate.now(clock.withZone(ZoneOffset.UTC)).minusMonths(GROWTH_MONTHS);
        List<StorageDailyPointDto> growth = repository.findGrowthSince(from);
        List<TopUserStorageDto> topUsers = repository.findTopUsersByVolume(TOP_USERS_LIMIT);

        long total = repository.countAllPhotos();
        List<PhotoRowDto> recent = withPreviews(
                repository.findRecentUploads(pageSize, (long) safePage * pageSize));

        long elapsed = System.currentTimeMillis() - start;
        return new StoragePageDto(overview, growth, topUsers, recent, safePage, pageSize, total, elapsed);
    }

    /** Грид фото юзера для /admin/users/{id}. Показываем и удалённые — админу видно всё. */
    public List<PhotoRowDto> loadUserPhotos(long userId) {
        return withPreviews(repository.findPhotosByUser(userId, USER_PHOTOS_LIMIT));
    }

    /** Текущий объём в бакете — вход суточного снапшота метрики. */
    public StorageOverviewDto currentOverview() {
        return repository.loadOverview(
                storageProperties.getPricePerGbMonth(), storageProperties.getRetentionDays());
    }

    private List<PhotoRowDto> withPreviews(List<PhotoRowDto> rows) {
        return rows.stream().map(row -> row.withPreviewUrl(previewUrl(row))).toList();
    }

    /**
     * Пресайн-ссылка на превью или {@code null}.
     *
     * <p>Подпись считается локально (сетевого вызова нет), но требует
     * сконфигурированного бакета и кредов. На dev и в тестах бакет пустой —
     * тогда даже не пытаемся: страница должна рендериться без S3. Исключение
     * тоже гасим — админка не обязана падать из-за недоступного хранилища,
     * шаблон нарисует плейсхолдер.
     *
     * <p>Для физически вычищенного фото ссылки нет по определению.
     */
    private String previewUrl(PhotoRowDto row) {
        if (row.purged()) {
            return null;
        }
        String bucket = s3Properties.getBucket();
        if (bucket == null || bucket.isBlank()) {
            return null;
        }
        try {
            return photoStorageService.presignedGetUrl(row.storageKey()).toString();
        } catch (Exception e) {
            log.debug("Presign failed for photo_id={}: {}", row.photoId(), e.getMessage());
            return null;
        }
    }
}
