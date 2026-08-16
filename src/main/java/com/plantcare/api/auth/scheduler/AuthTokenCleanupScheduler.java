package com.plantcare.api.auth.scheduler;

import com.plantcare.core.observability.SentryTags;
import com.plantcare.core.observability.SentryTags.Layer;
import com.plantcare.core.repository.MagicLinkTokenRepository;
import com.plantcare.core.repository.RevokedRefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Чистка протухших auth-токенов (issue #88, ADR-011).
 *
 * <p>Удаляет отозванные refresh-jti после их {@code exp} (блокировать уже
 * невалидный токен незачем) и использованные/протухшие magic-link токены.
 * Идемпотентно: повторный запуск просто удалит 0 дополнительных строк.
 *
 * <p>Cron в UTC, как требует конвенция шедулеров.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthTokenCleanupScheduler {

    /**
     * Magic-link токены держим ещё немного после {@code expires_at} (для
     * наблюдаемости/повторов), затем удаляем. Refresh-jti чистим строго по exp.
     */
    private static final Duration MAGIC_LINK_RETENTION = Duration.ofDays(1);

    private final RevokedRefreshTokenRepository revokedRefreshTokenRepository;
    private final MagicLinkTokenRepository magicLinkTokenRepository;
    private final Clock clock;

    /** Каждый час в 10-ю минуту (UTC), со смещением от прочих hourly-задач. */
    // Issue #279: hourly-задача — cleanup идемпотентен, но дубли всё равно нежелательны.
    @Scheduled(cron = "0 10 * * * *", zone = "UTC")
    @SchedulerLock(name = "AuthTokenCleanupScheduler_cleanup",
            lockAtMostFor = "PT90M", lockAtLeastFor = "PT55M")
    @Transactional
    public void cleanup() {
        SentryTags.runWithLayer(Layer.SCHEDULER, "AuthTokenCleanupScheduler", () -> {
            Instant now = clock.instant();

            int revokedDeleted = revokedRefreshTokenRepository.deleteExpired(now);
            int magicDeleted = magicLinkTokenRepository.deleteExpiredBefore(now.minus(MAGIC_LINK_RETENTION));

            if (revokedDeleted > 0 || magicDeleted > 0) {
                log.info("Auth token cleanup: revoked={}, magicLink={}", revokedDeleted, magicDeleted);
            }
        });
    }
}
