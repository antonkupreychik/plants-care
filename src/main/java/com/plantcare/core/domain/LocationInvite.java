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
 * Одноразовое приглашение к совместному уходу за локацией (issue #77).
 *
 * <p>Генерирует deep link {@code t.me/<bot>?start=invite_<token>}. Второй пользователь
 * переходит по ссылке, бот ловит payload в {@code /start} и создаёт
 * {@link LocationAccess}. Приглашение одноразовое: {@code acceptedAt != null}
 * означает «уже использовано» и повторно не принимается; {@code expiresAt}
 * ограничивает срок жизни ссылки.
 */
@Entity
@Table(name = "location_invites")
@Getter
@Setter
@NoArgsConstructor
public class LocationInvite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token", nullable = false, unique = true, length = 64)
    private String token;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "location_id", nullable = false)
    private Location location;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inviter_user_id", nullable = false)
    private User inviter;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private AccessRole role;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "accepted_by_user_id")
    private User acceptedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public LocationInvite(String token, Location location, User inviter, AccessRole role, Instant expiresAt) {
        this.token = token;
        this.location = location;
        this.inviter = inviter;
        this.role = role;
        this.expiresAt = expiresAt;
    }

    /** Приглашение уже использовано (одноразовость). */
    public boolean isAccepted() {
        return acceptedAt != null;
    }

    /** Срок жизни ссылки истёк относительно переданного момента. */
    public boolean isExpired(Instant now) {
        return expiresAt.isBefore(now);
    }

    /** Можно ли принять приглашение сейчас (не использовано и не протухло). */
    public boolean isUsable(Instant now) {
        return !isAccepted() && !isExpired(now);
    }
}
