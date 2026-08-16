package com.plantcare.core.service;

import com.plantcare.core.config.S3StorageProperties;
import com.plantcare.core.domain.Photo;
import com.plantcare.core.repository.PhotoRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URL;
import java.time.Clock;
import java.time.Instant;

/**
 * Бизнес-логика хранения фото (issue #90, Slice A).
 *
 * <p>Делегирует бинарь {@link PhotoStorageService} (S3), а метаданные пишет в
 * {@code photos}. Валидирует тип ({@code image/*}) и размер (≤ {@code maxUploadBytes}).
 * Удаление — soft-delete (проставляем {@code deleted_at}), физический объект в
 * бакете не трогаем (чистка — отдельная задача, Slice B+).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PhotoService {

    private final PhotoStorageService photoStorageService;
    private final PhotoRepository photoRepository;
    private final S3StorageProperties properties;
    private final Clock clock;

    /**
     * Валидирует и сохраняет загруженное фото: кладёт байты в S3, создаёт запись.
     *
     * @throws InvalidPhotoException  если контент не {@code image/*} или пустой (HTTP 400)
     * @throws PhotoTooLargeException если размер превышает лимит (HTTP 413)
     */
    @Transactional
    public Photo upload(Long userId, byte[] bytes, String contentType) {
        if (bytes == null || bytes.length == 0) {
            throw new InvalidPhotoException("Empty file");
        }
        if (contentType == null || !contentType.toLowerCase().startsWith("image/")) {
            throw new InvalidPhotoException("Unsupported content type: " + contentType);
        }
        if (bytes.length > properties.getMaxUploadBytes()) {
            throw new PhotoTooLargeException(bytes.length, properties.getMaxUploadBytes());
        }

        StoredPhoto stored = photoStorageService.put(bytes, contentType);

        Photo photo = photoRepository.save(
                new Photo(stored.storageKey(), contentType, stored.sizeBytes(), userId, Instant.now(clock)));
        log.info("Created photo {} for user {} key={}", photo.getId(), userId, stored.storageKey());
        return photo;
    }

    /**
     * Пресайн-URL на скачивание активного фото пользователя.
     *
     * @throws EntityNotFoundException если фото нет, оно чужое или soft-deleted (HTTP 404)
     */
    @Transactional(readOnly = true)
    public URL presignedUrl(Long userId, Long photoId) {
        Photo photo = requireActive(userId, photoId);
        return photoStorageService.presignedGetUrl(photo.getStorageKey());
    }

    /**
     * Soft-delete фото пользователя. Идемпотентно для уже удалённого: повторный
     * вызов вернёт 404 (запись уже не активна).
     *
     * @throws EntityNotFoundException если фото нет, оно чужое или уже удалено (HTTP 404)
     */
    @Transactional
    public void softDelete(Long userId, Long photoId) {
        Photo photo = requireActive(userId, photoId);
        photo.markDeleted(Instant.now(clock));
        log.info("Soft-deleted photo {} for user {}", photoId, userId);
    }

    private Photo requireActive(Long userId, Long photoId) {
        return photoRepository.findByIdAndUserIdAndDeletedAtIsNull(photoId, userId)
                .orElseThrow(() -> new EntityNotFoundException("Photo not found: " + photoId));
    }
}
