package com.plantcare.admin.storage.service;

import com.plantcare.admin.storage.service.AdminPhotoService.PurgeCandidate;
import com.plantcare.core.service.PhotoStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Физическая чистка бакета от soft-deleted фото (issue #101).
 *
 * <p><b>Почему это отдельный бин без {@code @Transactional}.</b> Правило
 * CLAUDE.md: внешние API не вызываются внутри открытой транзакции к БД. Здесь
 * внешний API — S3. Поэтому класс транзакций не держит вовсе, а дёргает
 * {@link AdminPhotoService} за короткими атомарными шагами:
 *
 * <pre>
 *   [tx] прочитать кандидатов   →  закрыть транзакцию
 *   для каждого:
 *        S3 DeleteObject        →  вне транзакции
 *        [tx] пометить purged   →  своя короткая транзакция
 * </pre>
 *
 * <p><b>Идемпотентность.</b> Прогон можно повторять сколько угодно:
 * <ul>
 *   <li>S3 {@code DeleteObject} сам по себе идемпотентен — удаление
 *       несуществующего ключа не ошибка;</li>
 *   <li>{@code markPurged} обновляет строку только при {@code purged_at IS NULL},
 *       так что момент первой чистки не перезаписывается;</li>
 *   <li>если S3 упал — {@code purged_at} НЕ ставится, и фото попадёт в
 *       следующий прогон. Порядок «сначала S3, потом отметка» выбран именно
 *       ради этого: потерять объект в бакете без отметки хуже, чем удалить
 *       дважды.</li>
 * </ul>
 *
 * <p>Каждое удаление логируется — это и есть аудит-след чистки.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PhotoPurgeService {

    private final AdminPhotoService adminPhotoService;
    private final PhotoStorageService photoStorageService;

    /**
     * Один прогон чистки: до {@code admin.storage.purge-batch-size} объектов,
     * у которых soft-delete старше retention.
     *
     * @return сколько объектов вычищено и сколько не удалось
     */
    public PurgeResult purgeExpired() {
        List<PurgeCandidate> candidates = adminPhotoService.findPurgeCandidates();
        if (candidates.isEmpty()) {
            return PurgeResult.empty();
        }

        int purged = 0;
        int failed = 0;
        for (PurgeCandidate candidate : candidates) {
            if (purgeOne(candidate)) {
                purged++;
            } else {
                failed++;
            }
        }

        log.info("Photo purge run finished: candidates={}, purged={}, failed={}",
                candidates.size(), purged, failed);
        return new PurgeResult(purged, failed);
    }

    /**
     * Удаляет один объект и отмечает запись. Исключение S3 не роняет прогон:
     * фото остаётся неотмеченным и будет повторено в следующий раз.
     */
    private boolean purgeOne(PurgeCandidate candidate) {
        try {
            // Вне транзакции — см. javadoc класса.
            photoStorageService.delete(candidate.storageKey());
        } catch (Exception e) {
            log.warn("Photo purge failed for photo_id={} key={}: {}",
                    candidate.photoId(), candidate.storageKey(), e.getMessage());
            return false;
        }

        boolean marked = adminPhotoService.markPurged(candidate.photoId());
        log.info("Photo purged from bucket: photo_id={}, user_id={}, key={}, marked={}",
                candidate.photoId(), candidate.userId(), candidate.storageKey(), marked);
        return true;
    }

    /**
     * Итог прогона чистки.
     *
     * @param purged успешно удалено из бакета
     * @param failed не удалось — повторится в следующем прогоне
     */
    public record PurgeResult(int purged, int failed) {

        public static PurgeResult empty() {
            return new PurgeResult(0, 0);
        }

        public int total() {
            return purged + failed;
        }
    }
}
