package com.plantcare.api.auth.service;

import com.plantcare.api.auth.exception.AuthTokenException;
import com.plantcare.bot.support.IntegrationTestBase;
import com.plantcare.core.domain.MagicLinkToken;
import com.plantcare.core.domain.User;
import com.plantcare.core.repository.MagicLinkTokenRepository;
import com.plantcare.core.repository.RevokedRefreshTokenRepository;
import com.plantcare.core.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Интеграционный тест Redis-кэша токенов аутентификации (issue #282, эпик #277 фаза 4).
 *
 * <p>AC:
 * <ul>
 *   <li>Проверка отзыва корректна при недоступном Redis — fallback на Postgres.</li>
 *   <li>Redis-кэш ускоряет повторный отзыв/verify (cache hit проверяется напрямую).</li>
 *   <li>Redis down → отозванный refresh-токен всё равно отвергается через Postgres.</li>
 *   <li>Redis down → потреблённый magic-link всё равно отвергается через Postgres.</li>
 * </ul>
 *
 * <p>Время фиксируется через {@link MutableClock} в далёком будущем (2099),
 * чтобы exp выпущенных JWT проходил реальный декодер.
 */
@TestPropertySource(properties = "management.health.mail.enabled=false")
@Import(TokenRedisCacheIT.FixedClockConfig.class)
class TokenRedisCacheIT extends IntegrationTestBase {

    private static final Instant ANCHOR = Instant.parse("2099-06-01T12:00:00Z");

    // ── services ──
    @Autowired
    private RefreshTokenBlacklistService blacklistService;

    @Autowired
    private RefreshService refreshService;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private MagicLinkService magicLinkService;

    @Autowired
    private TokenRedisCache tokenRedisCache;

    // ── repositories ──
    @Autowired
    private RevokedRefreshTokenRepository revokedRepository;

    @Autowired
    private MagicLinkTokenRepository magicLinkTokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private MutableClock mutableClock;

    // mail не нужен реальным
    @MockitoBean
    private JavaMailSender mailSender;

    @AfterEach
    void cleanup() {
        revokedRepository.deleteAll();
        magicLinkTokenRepository.deleteAll();
        userRepository.deleteAll();
        // Чистим Redis-ключи этих тестов
        var keys = redisTemplate.keys("pc:revoked-refresh:*");
        if (keys != null && !keys.isEmpty()) redisTemplate.delete(keys);
        var mlKeys = redisTemplate.keys("pc:magic-link-invalid:*");
        if (mlKeys != null && !mlKeys.isEmpty()) redisTemplate.delete(mlKeys);
    }

    // ═══════════════ revoked-refresh / Redis hot path ═══════════════

    @Test
    void should_cache_revoked_jti_in_redis_after_revokeOrThrow() {
        // arrange
        UUID jti = UUID.randomUUID();
        Instant expiresAt = ANCHOR.plusSeconds(3600);

        // act
        blacklistService.revokeOrThrow(jti, expiresAt);

        // assert — Redis-кэш прогрет
        assertThat(tokenRedisCache.isRefreshRevoked(jti)).isPresent();
    }

    @Test
    void should_reject_revoked_refresh_via_redis_cache_on_replay() {
        // arrange — первый отзыв прогревает кэш
        UUID jti = UUID.randomUUID();
        Instant expiresAt = ANCHOR.plusSeconds(3600);
        blacklistService.revokeOrThrow(jti, expiresAt);

        // act + assert — повторный отзыв: Redis cache hit → TOKEN_REVOKED
        assertThatThrownBy(() -> blacklistService.revokeOrThrow(jti, expiresAt))
                .isInstanceOf(AuthTokenException.class)
                .satisfies(e -> assertThat(((AuthTokenException) e).getCode())
                        .isEqualTo(AuthTokenException.Code.TOKEN_REVOKED));
    }

    // ═══════════════ AC ключевой: Redis down → fallback на Postgres ═══════════════

    @Test
    void should_reject_revoked_refresh_via_postgres_when_redis_is_down() {
        // arrange — отзываем jti в Postgres (прямой вызов, Redis не прогреваем через blacklistService)
        UUID jti = UUID.randomUUID();
        Instant expiresAt = ANCHOR.plusSeconds(3600);
        revokedRepository.revokeIfAbsent(jti, expiresAt);

        // Симулируем Redis down: удаляем ключ из Redis (если он был), чтобы Redis вернул empty
        redisTemplate.delete("pc:revoked-refresh:" + jti);

        // act — blacklistService: Redis не знает про jti → Postgres говорит «уже есть» (0 строк)
        assertThatThrownBy(() -> blacklistService.revokeOrThrow(jti, expiresAt))
                .isInstanceOf(AuthTokenException.class)
                .satisfies(e -> assertThat(((AuthTokenException) e).getCode())
                        .isEqualTo(AuthTokenException.Code.TOKEN_REVOKED));
    }

    @Test
    void should_reject_revoked_refresh_via_rotate_when_redis_cache_is_cold() {
        // arrange — выдаём токен, прямо пишем jti в Postgres (минуя blacklistService)
        User user = persistUser();
        TokenPair pair = tokenService.issuePair(user);
        TokenService.RefreshToken parsed = tokenService.parseRefresh(pair.refreshToken());

        revokedRepository.revokeIfAbsent(parsed.jti(), parsed.expiresAt());
        // Убираем Redis-ключ, если вдруг появился
        redisTemplate.delete("pc:revoked-refresh:" + parsed.jti());

        // act + assert — ротация: Redis miss → Postgres говорит «0 вставлено» → TOKEN_REVOKED
        assertThatThrownBy(() -> refreshService.rotate(pair.refreshToken()))
                .isInstanceOf(AuthTokenException.class)
                .satisfies(e -> assertThat(((AuthTokenException) e).getCode())
                        .isEqualTo(AuthTokenException.Code.TOKEN_REVOKED));
    }

    // ═══════════════ magic-link / Redis hot path ═══════════════

    @Test
    void should_cache_magic_link_as_invalid_in_redis_after_verify() throws Exception {
        // arrange
        String rawToken = "magic-cache-test-token-" + UUID.randomUUID();
        String tokenHash = MagicLinkService.sha256Hex(rawToken);
        magicLinkTokenRepository.save(
                new MagicLinkToken("cache@example.com", tokenHash, ANCHOR.plusSeconds(900)));

        // act
        magicLinkService.verify(rawToken);

        // assert — Redis-кэш прогрет (токен помечен невалидным)
        assertThat(tokenRedisCache.isMagicLinkInvalid(tokenHash)).isPresent();
    }

    @Test
    void should_reject_magic_link_replay_via_redis_cache() throws Exception {
        // arrange — первый verify потребляет токен и прогревает Redis
        String rawToken = "magic-replay-token-" + UUID.randomUUID();
        String tokenHash = MagicLinkService.sha256Hex(rawToken);
        magicLinkTokenRepository.save(
                new MagicLinkToken("replay@example.com", tokenHash, ANCHOR.plusSeconds(900)));
        magicLinkService.verify(rawToken);

        // act + assert — повторный verify: Redis cache hit → TOKEN_INVALID
        assertThatThrownBy(() -> magicLinkService.verify(rawToken))
                .isInstanceOf(AuthTokenException.class)
                .satisfies(e -> assertThat(((AuthTokenException) e).getCode())
                        .isEqualTo(AuthTokenException.Code.TOKEN_INVALID));
    }

    // ═══════════════ AC ключевой: Redis down → fallback на Postgres для magic-link ═══════════════

    @Test
    void should_reject_consumed_magic_link_via_postgres_when_redis_cache_is_cold() throws Exception {
        // arrange — потребляем токен в Postgres напрямую (consumeIfValid),
        // а Redis-ключ не кладём (симуляция "Redis был недоступен при первом verify")
        String rawToken = "magic-fallback-token-" + UUID.randomUUID();
        String tokenHash = MagicLinkService.sha256Hex(rawToken);
        magicLinkTokenRepository.save(
                new MagicLinkToken("fallback@example.com", tokenHash, ANCHOR.plusSeconds(900)));

        // Потребляем через репозиторий напрямую, минуя сервис (Redis не прогревается)
        magicLinkTokenRepository.consumeIfValid(tokenHash, mutableClock.instant());
        // Убеждаемся, что Redis не содержит ключ
        redisTemplate.delete("pc:magic-link-invalid:" + tokenHash);

        // act + assert — magicLinkService.verify: Redis miss → Postgres: consumeIfValid = 0 → invalid
        assertThatThrownBy(() -> magicLinkService.verify(rawToken))
                .isInstanceOf(AuthTokenException.class)
                .satisfies(e -> assertThat(((AuthTokenException) e).getCode())
                        .isEqualTo(AuthTokenException.Code.TOKEN_INVALID));
    }

    // ═══════════════ helpers ═══════════════

    private User persistUser() {
        return userRepository.save(User.builder()
                .telegramChatId(null)
                .email("token-cache-" + UUID.randomUUID() + "@plantcare.test")
                .emailVerified(true)
                .build());
    }

    // ──────────────────────────── test infra ────────────────────────────

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(ANCHOR, ZoneOffset.UTC);
        }
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;
        private final java.time.ZoneId zone;

        MutableClock(Instant initial, java.time.ZoneId zone) {
            this.now = new AtomicReference<>(initial);
            this.zone = zone;
        }

        void set(Instant instant) {
            now.set(instant);
        }

        @Override
        public java.time.ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return new MutableClock(now.get(), zone);
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
