package com.plantcare.api.auth.service;

import com.plantcare.api.config.AuthProperties;
import com.plantcare.api.auth.exception.AuthTokenException;
import com.plantcare.core.domain.MagicLinkToken;
import com.plantcare.core.domain.User;
import com.plantcare.core.repository.MagicLinkTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Magic-link вход по email (issue #88, ADR-011).
 *
 * <p>В БД хранится только SHA-256 хеш opaque-токена. Само письмо отправляется
 * ВНЕ транзакции БД (см. {@link #request}). {@code verify} гасит токен атомарно
 * (one-time) и логинит/создаёт пользователя.
 *
 * <p>Issue #282 (эпик #277 фаза 4): Redis как кэш-ускоритель проверок.
 * При {@code verify} невалидный/потреблённый хеш помечается в Redis с TTL.
 * При повторном {@code verify} — Redis-lookup до Postgres (fail-safe при Redis down:
 * fallback на Postgres, НЕ fail-open).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MagicLinkService {

    private static final int TOKEN_BYTES = 32;

    private final MagicLinkTokenRepository tokenRepository;
    private final MagicLinkMailSender mailSender;
    private final AuthService authService;
    private final TokenService tokenService;
    private final AuthProperties authProperties;
    private final SecureRandom secureRandom;
    private final Clock clock;
    private final TokenRedisCache tokenRedisCache;

    /**
     * Создаёт magic-link токен и отправляет письмо. Сначала транзакционная
     * вставка, затем — отдельно — отправка письма (вне транзакции к БД).
     * Метод сам по себе НЕ транзакционный.
     */
    public void request(String email) {
        String normalizedEmail = email.trim().toLowerCase();

        String rawToken = generateToken();
        String tokenHash = sha256Hex(rawToken);
        Instant expiresAt = clock.instant().plus(authProperties.getMagicLink().getTtl());

        persist(normalizedEmail, tokenHash, expiresAt);

        String url = buildMagicLinkUrl(rawToken);
        mailSender.send(normalizedEmail, url);
    }

    /**
     * Вставка токена. Один {@code save()} коммитится собственной транзакцией
     * Spring Data — отдельная {@code @Transactional}-обёртка здесь не нужна и
     * всё равно не сработала бы при self-invocation. Главное — письмо
     * отправляется уже ПОСЛЕ возврата из этого метода, вне транзакции БД.
     */
    private void persist(String email, String tokenHash, Instant expiresAt) {
        tokenRepository.save(new MagicLinkToken(email, tokenHash, expiresAt));
    }

    /**
     * Гасит токен и выдаёт пару токенов. Атомарная пометка {@code consumed_at}
     * защищает от повторного использования (replay).
     *
     * <p>Горячий путь при replay: сначала проверяем Redis-кэш невалидных хешей.
     * Если Redis говорит «невалиден» — отклоняем без обращения к Postgres.
     * Если Redis недоступен — идём прямо в Postgres (fallback, не fail-open).
     * После успешного потребления токена прогреваем Redis-кэш.
     */
    @Transactional
    public TokenPair verify(String rawToken) {
        String tokenHash = sha256Hex(rawToken);
        Instant now = clock.instant();

        // Горячий путь: проверка Redis перед Postgres
        var cachedInvalid = tokenRedisCache.isMagicLinkInvalid(tokenHash);
        if (cachedInvalid.isPresent()) {
            log.debug("Magic link token rejected via Redis cache (already consumed/invalid)");
            throw AuthTokenException.invalid("Magic link is invalid, expired or already used");
        }

        int consumed = tokenRepository.consumeIfValid(tokenHash, now);
        if (consumed == 0) {
            // Прогреваем Redis — будущие replay будут отклонены быстро
            Instant cacheUntil = now.plus(authProperties.getMagicLink().getTtl());
            tokenRedisCache.markMagicLinkInvalid(tokenHash, cacheUntil);
            throw AuthTokenException.invalid("Magic link is invalid, expired or already used");
        }

        MagicLinkToken token = tokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> AuthTokenException.invalid("Magic link token disappeared"));

        // Прогреваем Redis-кэш: токен потреблён, повторный verify должен быть отклонён быстро
        tokenRedisCache.markMagicLinkInvalid(tokenHash, token.getExpiresAt());

        // Email подтверждён самим фактом получения письма по этому адресу.
        User user = authService.findOrCreateUser(
                token.getEmail(), true, AuthService.Provider.EMAIL, null);
        log.info("Magic link verified for userId={}", user.getId());
        return tokenService.issuePair(user);
    }

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String buildMagicLinkUrl(String rawToken) {
        String base = authProperties.getMagicLink().getBaseUrl();
        String separator = base.contains("?") ? "&" : "?";
        return base + separator + "token=" + rawToken;
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
