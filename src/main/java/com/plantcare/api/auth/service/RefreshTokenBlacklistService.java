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
 *
 * <p>Issue #282 (эпик #277 фаза 4): Redis как кэш-ускоритель проверок отзыва.
 * Горячий путь: Redis-lookup до INSERT в Postgres. Postgres остаётся source of truth.
 * При недоступном Redis — fallback на Postgres-проверку (НЕ fail-open:
 * принять отозванный токен = дыра в безопасности).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenBlacklistService {

    private final RevokedRefreshTokenRepository repository;
    private final TokenRedisCache tokenRedisCache;

    /**
     * Отзывает jti один раз. Бросает {@link AuthTokenException} с кодом
     * {@code TOKEN_REVOKED}, если jti уже был в blacklist'е.
     *
     * <p>Горячий путь при повторном использовании: сначала проверяем Redis
     * (дешевле, чем INSERT ... ON CONFLICT в Postgres). Если Redis говорит
     * «уже отозван» — бросаем исключение без обращения к Postgres.
     * Если Redis недоступен — пробиваемся к Postgres как обычно.
     * После успешного INSERT всегда прогреваем Redis-кэш.
     *
     * @param jti       идентификатор отзываемого refresh-токена
     * @param expiresAt срок жизни токена — до него запись держится в blacklist'е
     */
    @Transactional
    public void revokeOrThrow(UUID jti, Instant expiresAt) {
        // Горячий путь: проверка Redis перед INSERT в Postgres
        var cachedRevoked = tokenRedisCache.isRefreshRevoked(jti);
        if (cachedRevoked.isPresent()) {
            log.warn("Refresh token reuse detected (Redis cache hit): jti={}", jti);
            throw AuthTokenException.revoked("Refresh token has already been used");
        }

        // Postgres — source of truth: атомарный INSERT ON CONFLICT
        int inserted = repository.revokeIfAbsent(jti, expiresAt);
        if (inserted == 0) {
            log.warn("Refresh token reuse detected: jti already revoked");
            // Прогреваем Redis для быстрого отклонения последующих повторов
            tokenRedisCache.markRefreshRevoked(jti, expiresAt);
            throw AuthTokenException.revoked("Refresh token has already been used");
        }

        // Успешно отозван в Postgres — прогреваем Redis
        tokenRedisCache.markRefreshRevoked(jti, expiresAt);
    }
}
