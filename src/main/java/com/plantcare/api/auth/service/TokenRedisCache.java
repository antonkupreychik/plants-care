package com.plantcare.api.auth.service;

import com.plantcare.bot.config.RedisProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Redis-кэш для токенов аутентификации (issue #282, эпик #277 фаза 4).
 *
 * <p>Ускоряет «горячий путь» проверок: Redis-lookup в разы быстрее Postgres.
 * Postgres остаётся source of truth — Redis не единственное хранилище.
 *
 * <p>Ключи (все с префиксом {@code pc:}):
 * <ul>
 *   <li>{@code pc:revoked-refresh:<jti>} — маркер отозванного refresh-токена (value "1")</li>
 *   <li>{@code pc:magic-link:<tokenHash>} — маркер невалидного/потреблённого magic-link (value "1")</li>
 * </ul>
 *
 * <p>Деградация: при любом Redis-сбое методы перехватывают исключение, логируют
 * и возвращают {@link Optional#empty()} / {@code false}, чтобы вызывающая сторона
 * упала на Postgres-проверку. Это «fail-safe» для кэша, но не «fail-open» для
 * безопасности — отсутствие записи в Redis означает «не знаем», а не «валиден».
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenRedisCache {

    private static final String PREFIX_REVOKED_REFRESH = "revoked-refresh:";
    private static final String PREFIX_MAGIC_LINK_INVALID = "magic-link-invalid:";
    private static final String MARKER = "1";

    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisProperties redisProperties;

    // ──────────────── revoked-refresh-tokens ────────────────

    /**
     * Отмечает refresh-токен как отозванный в Redis с TTL = оставшееся время жизни токена.
     * При недоступном Redis — тихо игнорирует (fallback на Postgres).
     *
     * @param jti       идентификатор отзываемого токена
     * @param expiresAt момент истечения токена — граница TTL в Redis
     */
    public void markRefreshRevoked(UUID jti, Instant expiresAt) {
        try {
            Duration ttl = ttlFrom(expiresAt);
            if (ttl.isNegative() || ttl.isZero()) {
                return; // токен уже истёк — кэшировать незачем
            }
            String key = revokedRefreshKey(jti);
            redisTemplate.opsForValue().set(key, MARKER, ttl);
        } catch (Exception e) {
            log.warn("Redis unavailable: failed to mark refresh revoked for jti={}. "
                    + "Falling back to Postgres. Error: {}", jti, e.getMessage());
        }
    }

    /**
     * Проверяет, содержит ли Redis запись об отозванном refresh-токене.
     *
     * @return {@code Optional.of(true)} если отозван, {@code Optional.empty()} если
     *         ключ не найден или Redis недоступен (вызывающий должен упасть на Postgres)
     */
    public Optional<Boolean> isRefreshRevoked(UUID jti) {
        try {
            Boolean exists = redisTemplate.hasKey(revokedRefreshKey(jti));
            if (Boolean.TRUE.equals(exists)) {
                return Optional.of(Boolean.TRUE);
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Redis unavailable: isRefreshRevoked check failed for jti={}. "
                    + "Falling back to Postgres. Error: {}", jti, e.getMessage());
            return Optional.empty();
        }
    }

    // ──────────────── magic-link-tokens ────────────────

    /**
     * Отмечает magic-link токен как невалидный (потреблённый или истёкший) в Redis.
     * При недоступном Redis — тихо игнорирует.
     *
     * @param tokenHash SHA-256 хеш токена
     * @param expiresAt граница TTL (обычно = expiresAt исходного токена)
     */
    public void markMagicLinkInvalid(String tokenHash, Instant expiresAt) {
        try {
            Duration ttl = ttlFrom(expiresAt);
            if (ttl.isNegative() || ttl.isZero()) {
                return;
            }
            String key = magicLinkInvalidKey(tokenHash);
            redisTemplate.opsForValue().set(key, MARKER, ttl);
        } catch (Exception e) {
            log.warn("Redis unavailable: failed to mark magic-link invalid for hash={}. "
                    + "Falling back to Postgres. Error: {}", tokenHash, e.getMessage());
        }
    }

    /**
     * Проверяет, помечен ли magic-link токен невалидным в Redis.
     *
     * @return {@code Optional.of(true)} если невалиден, {@code Optional.empty()} если
     *         ключ не найден или Redis недоступен
     */
    public Optional<Boolean> isMagicLinkInvalid(String tokenHash) {
        try {
            Boolean exists = redisTemplate.hasKey(magicLinkInvalidKey(tokenHash));
            if (Boolean.TRUE.equals(exists)) {
                return Optional.of(Boolean.TRUE);
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Redis unavailable: isMagicLinkInvalid check failed for hash={}. "
                    + "Falling back to Postgres. Error: {}", tokenHash, e.getMessage());
            return Optional.empty();
        }
    }

    // ──────────────── helpers ────────────────

    private String revokedRefreshKey(UUID jti) {
        return redisProperties.keyPrefix() + PREFIX_REVOKED_REFRESH + jti;
    }

    private String magicLinkInvalidKey(String tokenHash) {
        return redisProperties.keyPrefix() + PREFIX_MAGIC_LINK_INVALID + tokenHash;
    }

    private Duration ttlFrom(Instant expiresAt) {
        return Duration.between(Instant.now(), expiresAt);
    }
}
