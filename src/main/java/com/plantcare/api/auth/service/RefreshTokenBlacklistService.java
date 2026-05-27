package com.plantcare.api.auth.service;

import com.plantcare.api.auth.exception.AuthTokenException;
import com.plantcare.core.repository.RevokedRefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Blacklist refresh-токенов по jti (issue #88). При ротации старый jti отзывается
 * атомарно через {@code INSERT ... ON CONFLICT DO NOTHING}: если вставилось
 * 0 строк — токен уже был отозван (replay / гонка), это {@code TOKEN_REVOKED}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenBlacklistService {

    private final RevokedRefreshTokenRepository repository;

    /**
     * Отзывает jti один раз. Бросает {@link AuthTokenException} с кодом
     * {@code TOKEN_REVOKED}, если jti уже был в blacklist'е.
     *
     * @param jti       идентификатор отзываемого refresh-токена
     * @param expiresAt срок жизни токена — до него запись держится в blacklist'е
     */
    @Transactional
    public void revokeOrThrow(UUID jti, Instant expiresAt) {
        int inserted = repository.revokeIfAbsent(jti, expiresAt);
        if (inserted == 0) {
            log.warn("Refresh token reuse detected: jti already revoked");
            throw AuthTokenException.revoked("Refresh token has already been used");
        }
    }
}
