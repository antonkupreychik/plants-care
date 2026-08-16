package com.plantcare.admin.storage.service;

import com.plantcare.admin.config.AdminProperties;
import com.plantcare.core.domain.Photo;
import com.plantcare.core.repository.PhotoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Транзакционные операции админки над фото (issue #101).
 *
 * <p>Здесь и только здесь открываются транзакции к БД. Вызовов к S3 в этом
 * классе НЕТ — намеренно: правило CLAUDE.md запрещает внешние API внутри
 * открытой транзакции. Последовательность «удалить объект в S3 → отметить
 * запись» собирает {@link PhotoPurgeService}, который транзакций не держит и
 * дёргает отсюда короткие атомарные шаги.
 *
 * <p>Модель удаления двухфазная:
 * <ol>
 *   <li>админ жмёт «удалить» → soft-delete ({@code deleted_at}), бинарь в
 *       бакете остаётся, фото можно вернуть;</li>
 *   <li>спустя {@code admin.storage.retention-days} отложенная задача физически
 *       удаляет объект и ставит {@code purged_at} — с этого момента возврата нет.</li>
 * </ol>
 *
 * <p>Строка {@code photos} не удаляется никогда: на неё ссылается
 * {@code plant_progress_photos.photo_id}, см. комментарий в миграции V58.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminPhotoService {

    private final PhotoRepository photoRepository;
    private final AdminProperties adminProperties;
    private final Clock clock;

    /**
     * Soft-delete одного фото по запросу админа.
     *
     * <p>Идемпотентно: уже удалённое фото не трогаем и момент удаления не
     * перезаписываем (иначе отсчёт retention сбрасывался бы на каждый повторный
     * клик, и объект жил бы в бакете вечно).
     *
     * @return {@code true}, если фото переведено в soft-deleted этим вызовом
     * @throws ResponseStatusException 404, если фото нет
     */
    @Transactional
    public boolean softDelete(long photoId, String adminName) {
        Photo photo = photoRepository.findById(photoId).orElseThrow(() -> notFound(photoId));

        if (photo.isDeleted()) {
            log.info("Admin action PHOTO_SOFT_DELETE (no-op, already deleted): photo_id={}, admin={}",
                    photoId, adminName);
            return false;
        }

        photo.markDeleted(Instant.now(clock));
        log.info("Admin action PHOTO_SOFT_DELETE: photo_id={}, user_id={}, key={}, bytes={}, admin={}",
                photoId, photo.getUserId(), photo.getStorageKey(), photo.getSizeBytes(), adminName);
        return true;
    }

    /**
     * Возврат ошибочно удалённого фото — смысл retention-окна в том, чтобы это
     * было возможно. После физической чистки ({@code purged_at}) возврат
     * невозможен: бинаря в бакете уже нет.
     *
     * @return {@code true}, если фото восстановлено этим вызовом
     * @throws ResponseStatusException 404, если фото нет
     * @throws IllegalStateException   если объект уже вычищен из бакета
     */
    @Transactional
    public boolean restore(long photoId, String adminName) {
        Photo photo = photoRepository.findById(photoId).orElseThrow(() -> notFound(photoId));

        if (photo.isPurged()) {
            throw new IllegalStateException(
                    "Фото уже физически удалено из хранилища — восстановить нельзя");
        }
        if (!photo.isDeleted()) {
            return false;
        }

        photo.restore();
        log.info("Admin action PHOTO_RESTORE: photo_id={}, user_id={}, admin={}",
                photoId, photo.getUserId(), adminName);
        return true;
    }

    /**
     * GDPR-запрос «удалить все фото юзера X»: массовый soft-delete. Физическая
     * чистка из бакета — отложенная, по общему retention.
     *
     * @return сколько фото затронуто (0 — у юзера не было активных фото)
     */
    @Transactional
    public int softDeleteAllForUser(long userId, String adminName) {
        int affected = photoRepository.softDeleteAllByUserId(userId, Instant.now(clock));
        log.info("Admin action PHOTO_BULK_DELETE (GDPR): user_id={}, affected={}, admin={}",
                userId, affected, adminName);
        return affected;
    }

    /**
     * Фото, у которых истёк retention и которые пора физически удалить из бакета.
     * Читается отдельной короткой транзакцией — S3-вызовы идут уже после её закрытия.
     */
    @Transactional(readOnly = true)
    public List<PurgeCandidate> findPurgeCandidates() {
        int retentionDays = adminProperties.getStorage().getRetentionDays();
        int batchSize = adminProperties.getStorage().getPurgeBatchSize();
        Instant cutoff = Instant.now(clock).minus(Duration.ofDays(retentionDays));

        return photoRepository.findPurgeCandidates(cutoff, PageRequest.of(0, batchSize)).stream()
                .map(p -> new PurgeCandidate(p.getId(), p.getStorageKey(), p.getUserId()))
                .toList();
    }

    /**
     * Отмечает объект вычищенным — вызывается ПОСЛЕ успешного удаления в S3.
     * Своя короткая транзакция на каждое фото: падение на одном не откатывает
     * уже вычищенные.
     *
     * @return {@code true}, если отметка проставлена этим вызовом
     */
    @Transactional
    public boolean markPurged(long photoId) {
        return photoRepository.markPurged(photoId, Instant.now(clock)) > 0;
    }

    private static ResponseStatusException notFound(long photoId) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Photo not found: " + photoId);
    }

    /**
     * Кандидат на физическую чистку — ровно то, что нужно для S3-вызова, без
     * JPA-entity: entity вне транзакции был бы detached, а таскать его через
     * сетевой вызов незачем.
     *
     * @param photoId    photos.id
     * @param storageKey ключ объекта в бакете
     * @param userId     владелец — только для лога
     */
    public record PurgeCandidate(long photoId, String storageKey, long userId) {
    }
}
