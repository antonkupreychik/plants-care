package com.plantcare.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Запись чёрного списка отозванных/ротированных refresh-JWT по jti
 * (issue #88 / V33). Живёт до момента истечения самого токена.
 */
@Entity
@Table(name = "revoked_refresh_tokens")
@Getter
@Setter
@NoArgsConstructor
public class RevokedRefreshToken {

    @Id
    @Column(name = "jti")
    private UUID jti;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public RevokedRefreshToken(UUID jti, Instant expiresAt) {
        this.jti = jti;
        this.expiresAt = expiresAt;
    }
}
