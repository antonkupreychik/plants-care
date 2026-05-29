package com.plantcare.core.domain;

import com.plantcare.core.domain.base.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Уведомление в персистентном inbox пользователя (issue #183).
 *
 * <p>id и created_at наследуются из {@link BaseEntity}. {@code createdAt} — UTC
 * wall-clock ({@code LocalDateTime}, как принято в этом проекте). {@code readAt}
 * тоже {@code LocalDateTime} в UTC: {@code null} означает «непрочитано».
 *
 * <p>{@code type} хранится в БД строкой в верхнем регистре (EnumType.STRING);
 * API отдаёт нижний регистр — конвертация на REST-слое. {@code plant} опционален
 * (deep-link на растение), при удалении растения FK обнуляется (ON DELETE SET NULL),
 * а уведомление остаётся как исторический факт.
 */
@Entity
@Table(name = "notifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private NotificationType type;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "body", nullable = false)
    private String body;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plant_id")
    private Plant plant;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    public Notification(User user, NotificationType type, String title, String body, Plant plant) {
        this.user = user;
        this.type = type;
        this.title = title;
        this.body = body;
        this.plant = plant;
    }

    /**
     * Идемпотентно помечает уведомление прочитанным. Если {@code readAt} уже
     * выставлен — момент прочтения не перезаписывается (повторный POST не «сбивает»
     * время первого прочтения).
     */
    public void markRead(LocalDateTime readAt) {
        if (this.readAt == null) {
            this.readAt = readAt;
        }
    }
}
