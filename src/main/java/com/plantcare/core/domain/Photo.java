package com.plantcare.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Фото, хранящееся в S3-совместимом object storage (issue #90, Slice A).
 *
 * <p>Запись хранит метаданные объекта в бакете: namespaced {@code storageKey}
 * (например, {@code photos/<uuid>}), MIME-тип, размер и владельца. Сам бинарь
 * лежит в S3, не в БД.
 *
 * <p>Удаление — soft-delete: проставляем {@code deletedAt}, физический объект в
 * бакете в этом срезе не трогаем (чистку делает отдельная batch-задача, Slice B+).
 *
 * <p>Не наследуем {@code BaseEntity}: там {@code created_at} — {@code LocalDateTime}
 * через {@code @CreationTimestamp}. Здесь нужен {@code Instant} (UTC) для
 * {@code created_at} и {@code deleted_at}, поэтому управляем id и временем вручную
 * (как {@link UserDevice}).
 */
@Entity
@Table(name = "photos")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Photo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Namespaced-ключ объекта в бакете, например {@code photos/<uuid>}. */
    @Column(name = "storage_key", nullable = false, length = 512, unique = true)
    private String storageKey;

    /** MIME-тип загруженного файла ({@code image/...}). */
    @Column(name = "content_type", nullable = false, length = 128)
    private String contentType;

    /** Размер объекта в байтах. */
    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /** Владелец фото ({@code users.id}). FK + ON DELETE CASCADE в миграции. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Момент soft-delete; {@code null} — запись активна. */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    /**
     * Момент физического удаления объекта из бакета (issue #101).
     * {@code null} — бинарь ещё лежит в S3.
     *
     * <p>Сама строка {@code photos} не удаляется никогда: на неё ссылается
     * {@code plant_progress_photos.photo_id} с {@code ON DELETE SET NULL}, а
     * CHECK {@code chk_progress_photo_source} требует ровно один непустой
     * источник — удаление строки уронило бы инвариант таймлайна.
     */
    @Column(name = "purged_at")
    private Instant purgedAt;

    public Photo(String storageKey, String contentType, long sizeBytes, Long userId, Instant now) {
        this.storageKey = storageKey;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.userId = userId;
        this.createdAt = now;
    }

    /** Помечает запись удалённой (soft-delete). Идемпотентно — первый момент удаления сохраняется. */
    public void markDeleted(Instant now) {
        if (this.deletedAt == null) {
            this.deletedAt = now;
        }
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /**
     * Отменяет soft-delete (issue #101) — возврат ошибочно удалённого фото в
     * пределах retention-окна.
     *
     * <p>После физической чистки возврат бессмысленен: бинаря в бакете нет,
     * запись осталась тумбстоуном. Поэтому purged-фото не восстанавливаем.
     */
    public void restore() {
        if (purgedAt == null) {
            this.deletedAt = null;
        }
    }

    /**
     * Помечает объект физически удалённым из бакета (issue #101).
     * Идемпотентно — первый момент чистки сохраняется.
     */
    public void markPurged(Instant now) {
        if (this.purgedAt == null) {
            this.purgedAt = now;
        }
    }

    /** {@code true}, если бинаря в бакете больше нет (пресайн-ссылку выдавать нельзя). */
    public boolean isPurged() {
        return purgedAt != null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Photo that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
