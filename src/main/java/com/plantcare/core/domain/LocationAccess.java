package com.plantcare.core.domain;

import com.plantcare.core.domain.enums.AccessRole;
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
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Связь Subject (пользователь) → Object (локация) с ролью — ядро модели совместного
 * ухода (issue #77, SUBO). Строка означает, что {@code subject} имеет доступ к чужой
 * локации {@code object} (растения, задачи, уведомления).
 *
 * <p>Владелец локации сюда НЕ пишется — владение определяется {@code locations.user_id}.
 * Уникальность пары (subject, object) гарантирует БД-констрейнт
 * {@code uq_location_access_subject_object}.
 */
@Entity
@Table(name = "location_access")
@Getter
@Setter
@NoArgsConstructor
public class LocationAccess {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_user_id", nullable = false)
    private User subject;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "object_location_id", nullable = false)
    private Location object;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private AccessRole role;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public LocationAccess(User subject, Location object, AccessRole role) {
        this.subject = subject;
        this.object = object;
        this.role = role;
    }
}
