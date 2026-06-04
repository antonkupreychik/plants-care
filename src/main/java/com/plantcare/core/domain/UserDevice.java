package com.plantcare.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Устройство пользователя с push-токеном (issue #175, ADR-014).
 *
 * <p>Пара {@code (user_id, push_token)} уникальна (UNIQUE-констрейнт в V38).
 * Регистрация идемпотентна: повторный вызов обновляет {@code lastSeenAt}.
 * Удаление user → каскадное удаление записей (ON DELETE CASCADE в V38).
 *
 * <p>Не наследуем {@code BaseEntity}: там {@code created_at} — {@code TIMESTAMPTZ},
 * но через {@code @CreationTimestamp} в LocalDateTime. Здесь нам нужен {@code Instant}
 * (UTC) для обоих временных полей, поэтому управляем id и временем вручную.
 */
@Entity
@Table(name = "user_devices")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class UserDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 16)
    private DevicePlatform platform;

    @Column(name = "push_token", nullable = false, length = 512)
    private String pushToken;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    public UserDevice(User user, DevicePlatform platform, String pushToken, Instant now) {
        this.user = user;
        this.platform = platform;
        this.pushToken = pushToken;
        this.createdAt = now;
        this.lastSeenAt = now;
    }

    /** Обновляет момент последней активности устройства (idempotent upsert). */
    public void touch(Instant now) {
        this.lastSeenAt = now;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserDevice that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
