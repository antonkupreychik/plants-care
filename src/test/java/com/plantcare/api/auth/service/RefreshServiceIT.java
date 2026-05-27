package com.plantcare.api.auth.service;

import com.plantcare.api.auth.exception.AuthTokenException;
import com.plantcare.core.domain.User;
import com.plantcare.core.repository.RevokedRefreshTokenRepository;
import com.plantcare.core.repository.UserRepository;
import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Интеграционный тест ротации refresh-токенов (issue #88, ADR-011).
 *
 * <p>Реальный Postgres (blacklist через {@code ON CONFLICT}). Прямой AC:
 * старый refresh после ротации становится невалиден (replay → TOKEN_REVOKED).
 */
class RefreshServiceIT extends IntegrationTestBase {

    @Autowired
    private RefreshService refreshService;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private RefreshTokenBlacklistService blacklistService;

    @Autowired
    private RevokedRefreshTokenRepository revokedRepository;

    @Autowired
    private UserRepository userRepository;

    @AfterEach
    void cleanup() {
        revokedRepository.deleteAll();
        userRepository.deleteAll();
    }

    private User persistUser() {
        return userRepository.save(User.builder()
                .telegramChatId(null)
                .email("rotate-" + UUID.randomUUID() + "@plantcare.test")
                .emailVerified(true)
                .build());
    }

    // ===================== happy =====================

    @Test
    void should_issue_new_pair_when_rotating_valid_refresh() {
        // arrange
        User user = persistUser();
        TokenPair initial = tokenService.issuePair(user);

        // act
        TokenPair rotated = refreshService.rotate(initial.refreshToken());

        // assert — новая пара выдана, refresh отличается от исходного
        assertThat(rotated.accessToken()).isNotBlank();
        assertThat(rotated.refreshToken()).isNotBlank();
        assertThat(rotated.refreshToken()).isNotEqualTo(initial.refreshToken());

        // assert — исходный jti записан в blacklist
        UUID oldJti = tokenService.parseRefresh(initial.refreshToken()).jti();
        assertThat(revokedRepository.existsByJti(oldJti)).isTrue();
    }

    // ===================== AC: старый refresh после ротации невалиден =====================

    @Test
    void should_reject_old_refresh_when_reused_after_rotation() {
        // arrange
        User user = persistUser();
        TokenPair initial = tokenService.issuePair(user);
        refreshService.rotate(initial.refreshToken());

        // act + assert — повторный rotate тем же токеном → TOKEN_REVOKED (replay-защита)
        assertThatThrownBy(() -> refreshService.rotate(initial.refreshToken()))
                .isInstanceOf(AuthTokenException.class)
                .satisfies(e -> assertThat(((AuthTokenException) e).getCode())
                        .isEqualTo(AuthTokenException.Code.TOKEN_REVOKED));
    }

    @Test
    void should_allow_rotating_the_freshly_issued_refresh_only_once_in_a_chain() {
        // arrange — цепочка ротаций: каждый новый refresh валиден, предыдущий — нет
        User user = persistUser();
        TokenPair gen1 = tokenService.issuePair(user);

        // act
        TokenPair gen2 = refreshService.rotate(gen1.refreshToken());
        TokenPair gen3 = refreshService.rotate(gen2.refreshToken());

        // assert — самый свежий работает
        assertThat(gen3.refreshToken()).isNotBlank();

        // assert — промежуточный (gen2) уже отозван
        assertThatThrownBy(() -> refreshService.rotate(gen2.refreshToken()))
                .isInstanceOf(AuthTokenException.class)
                .satisfies(e -> assertThat(((AuthTokenException) e).getCode())
                        .isEqualTo(AuthTokenException.Code.TOKEN_REVOKED));
    }

    // ===================== AC: ON CONFLICT семантика (гонка) =====================

    @Test
    void should_throw_revoked_when_same_jti_revoked_twice() {
        // arrange
        UUID jti = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(3600);

        // act — первый отзыв проходит
        blacklistService.revokeOrThrow(jti, expiresAt);

        // assert — второй отзыв того же jti (гонка/повтор) → TOKEN_REVOKED
        assertThatThrownBy(() -> blacklistService.revokeOrThrow(jti, expiresAt))
                .isInstanceOf(AuthTokenException.class)
                .satisfies(e -> assertThat(((AuthTokenException) e).getCode())
                        .isEqualTo(AuthTokenException.Code.TOKEN_REVOKED));
    }
}
