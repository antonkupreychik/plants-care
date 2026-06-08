package com.plantcare.core.domain;

import com.plantcare.core.domain.base.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Запись таймлайна фото-прогресса (issue #72).
 *
 * <p>Не путать с {@code plants.photo_file_id} — там одна «обложка» растения,
 * здесь — история фото для сравнения «было/стало».
 *
 * <p>FK на {@link Plant} и {@link User} — ON DELETE CASCADE (см. миграцию V16),
 * поэтому при удалении растения или юзера записи уйдут автоматически.
 *
 * <p>Источник бинаря — один из двух (инвариант {@code chk_progress_photo_source},
 * миграция V54):
 * <ul>
 *   <li>{@code telegramFileId} — фото загружено через бота (Telegram file_id);</li>
 *   <li>{@code photo} — фото загружено через REST в S3-бакет (issue #90).</li>
 * </ul>
 */
@Entity
@Table(name = "plant_progress_photos")
@Getter
@Setter
@NoArgsConstructor
public class PlantProgressPhoto extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plant_id", nullable = false)
    private Plant plant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * Telegram file_id (бот-источник). {@code null}, когда фото загружено через
     * REST в S3 — тогда заполнен {@link #photo}. Инвариант — в миграции V54.
     */
    @Column(name = "telegram_file_id", length = 255)
    private String telegramFileId;

    /**
     * S3-источник фото ({@link Photo} из issue #90). {@code null} для бот-фото
     * (тогда заполнен {@link #telegramFileId}).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "photo_id")
    private Photo photo;

    @Column(name = "taken_at", nullable = false)
    private LocalDateTime takenAt;

    @Column(length = 500)
    private String caption;
}
