package com.plantcare.api.auth.scheduler;

import com.plantcare.core.domain.MagicLinkToken;
import com.plantcare.core.domain.RevokedRefreshToken;
import com.plantcare.core.repository.MagicLinkTokenRepository;
import com.plantcare.core.repository.RevokedRefreshTokenRepository;
import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционный тест чистки auth-токенов (issue #88, ADR-011).
 *
 * <p>Реальный Postgres. Проверяем: удаляются протухшие, свежие остаются, и —
 * по правилу шедулеров — повторный запуск идемпотентен (не падает, не удаляет лишнего).
 */
class AuthTokenCleanupSchedulerIT extends IntegrationTestBase {

    @Autowired
    private AuthTokenCleanupScheduler scheduler;

    @Autowired
    private RevokedRefreshTokenRepository revokedRepository;

    @Autowired
    private MagicLinkTokenRepository magicLinkRepository;

    @Autowired
    private Clock clock;

    @AfterEach
    void cleanup() {
        revokedRepository.deleteAll();
        magicLinkRepository.deleteAll();
    }

    // ===================== удаляет протухшее, не трогает свежее =====================

    @Test
    void should_delete_expired_and_keep_fresh_tokens() {
        // arrange
        Instant now = clock.instant();

        UUID expiredJti = UUID.randomUUID();
        UUID freshJti = UUID.randomUUID();
        revokedRepository.save(new RevokedRefreshToken(expiredJti, now.minus(Duration.ofHours(1))));
        revokedRepository.save(new RevokedRefreshToken(freshJti, now.plus(Duration.ofDays(1))));

        // magic-link: держим ещё ~1 день после expires_at, поэтому «протухший» = старше суток
        MagicLinkToken oldMagic = new MagicLinkToken(
                "old@example.com", "hash-old", now.minus(Duration.ofDays(2)));
        MagicLinkToken recentlyExpiredMagic = new MagicLinkToken(
                "recent@example.com", "hash-recent", now.minus(Duration.ofMinutes(5)));
        magicLinkRepository.save(oldMagic);
        magicLinkRepository.save(recentlyExpiredMagic);

        // act
        scheduler.cleanup();

        // assert — протухший revoked-jti удалён, свежий остался
        assertThat(revokedRepository.existsByJti(expiredJti)).isFalse();
        assertThat(revokedRepository.existsByJti(freshJti)).isTrue();

        // assert — старый magic-link (старше retention) удалён, недавно истёкший — пока остаётся
        assertThat(magicLinkRepository.findByTokenHash("hash-old")).isEmpty();
        assertThat(magicLinkRepository.findByTokenHash("hash-recent")).isPresent();
    }

    // ===================== идемпотентность (правило шедулеров) =====================

    @Test
    void should_be_idempotent_when_cleanup_runs_twice() {
        // arrange
        Instant now = clock.instant();
        UUID expiredJti = UUID.randomUUID();
        UUID freshJti = UUID.randomUUID();
        revokedRepository.save(new RevokedRefreshToken(expiredJti, now.minus(Duration.ofHours(1))));
        revokedRepository.save(new RevokedRefreshToken(freshJti, now.plus(Duration.ofDays(1))));

        // act — два прогона подряд
        scheduler.cleanup();
        long afterFirst = revokedRepository.count();
        scheduler.cleanup();
        long afterSecond = revokedRepository.count();

        // assert — второй прогон ничего не ломает и не удаляет лишнего
        assertThat(afterFirst).isEqualTo(1);
        assertThat(afterSecond).isEqualTo(1);
        assertThat(revokedRepository.existsByJti(freshJti)).isTrue();
    }

    @Test
    void should_not_fail_when_nothing_to_clean() {
        // act + assert — пустые таблицы, чистка не должна падать
        org.assertj.core.api.Assertions.assertThatNoException()
                .isThrownBy(() -> scheduler.cleanup());
    }
}
