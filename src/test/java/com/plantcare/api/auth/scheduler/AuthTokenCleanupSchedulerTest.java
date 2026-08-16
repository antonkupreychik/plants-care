package com.plantcare.api.auth.scheduler;

import com.plantcare.core.repository.MagicLinkTokenRepository;
import com.plantcare.core.repository.RevokedRefreshTokenRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты {@link AuthTokenCleanupScheduler} (issue #88, ADR-011).
 *
 * <p>Только Mockito-юнит: единственный имеющийся тест на этот класс раньше был
 * {@code AuthTokenCleanupSchedulerIT.java}, который maven-failsafe тут не
 * запускает (модуль не сконфигурирован) — файл никогда не исполнялся и
 * покрытие не давал.
 *
 * <p>Clock фиксирован через {@link Clock#fixed}, часовой пояс — Europe/Moscow,
 * чтобы явно показать: cleanup работает от {@code clock.instant()} (абсолютный
 * момент), а не от wall-clock JVM/зоны — граница retention считается корректно
 * независимо от TZ бина Clock.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-тесты для AuthTokenCleanupScheduler (issue #88)")
class AuthTokenCleanupSchedulerTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-05-24T10:00:00Z");
    private static final Clock FIXED_CLOCK =
            Clock.fixed(FIXED_INSTANT, ZoneOffset.ofHours(3)); // Europe/Moscow смещение

    @Mock
    private RevokedRefreshTokenRepository revokedRefreshTokenRepository;

    @Mock
    private MagicLinkTokenRepository magicLinkTokenRepository;

    private AuthTokenCleanupScheduler scheduler;

    @Test
    @DisplayName("Ничего не удалено (revoked=0, magic=0): оба репозитория дёрнуты, лог-ветка не падает")
    void should_call_both_repositories_when_nothing_to_delete() {
        scheduler = new AuthTokenCleanupScheduler(
                revokedRefreshTokenRepository, magicLinkTokenRepository, FIXED_CLOCK);
        when(revokedRefreshTokenRepository.deleteExpired(any(Instant.class))).thenReturn(0);
        when(magicLinkTokenRepository.deleteExpiredBefore(any(Instant.class))).thenReturn(0);

        scheduler.cleanup();

        verify(revokedRefreshTokenRepository).deleteExpired(FIXED_INSTANT);
        verify(magicLinkTokenRepository).deleteExpiredBefore(FIXED_INSTANT.minus(Duration.ofDays(1)));
    }

    @Test
    @DisplayName("Только revoked-токены удалены (magic=0): условие revokedDeleted>0 срабатывает")
    void should_pass_when_only_revoked_tokens_deleted() {
        scheduler = new AuthTokenCleanupScheduler(
                revokedRefreshTokenRepository, magicLinkTokenRepository, FIXED_CLOCK);
        when(revokedRefreshTokenRepository.deleteExpired(any(Instant.class))).thenReturn(5);
        when(magicLinkTokenRepository.deleteExpiredBefore(any(Instant.class))).thenReturn(0);

        scheduler.cleanup();

        verify(revokedRefreshTokenRepository).deleteExpired(FIXED_INSTANT);
        verify(magicLinkTokenRepository).deleteExpiredBefore(any(Instant.class));
    }

    @Test
    @DisplayName("Только magic-link токены удалены (revoked=0): условие magicDeleted>0 срабатывает")
    void should_pass_when_only_magic_link_tokens_deleted() {
        scheduler = new AuthTokenCleanupScheduler(
                revokedRefreshTokenRepository, magicLinkTokenRepository, FIXED_CLOCK);
        when(revokedRefreshTokenRepository.deleteExpired(any(Instant.class))).thenReturn(0);
        when(magicLinkTokenRepository.deleteExpiredBefore(any(Instant.class))).thenReturn(3);

        scheduler.cleanup();

        verify(revokedRefreshTokenRepository).deleteExpired(any(Instant.class));
        verify(magicLinkTokenRepository).deleteExpiredBefore(FIXED_INSTANT.minus(Duration.ofDays(1)));
    }

    @Test
    @DisplayName("Оба удалены: magic-link retention-граница = now - 1 день (Duration.ofDays(1))")
    void should_use_one_day_retention_boundary_for_magic_link_when_both_deleted() {
        scheduler = new AuthTokenCleanupScheduler(
                revokedRefreshTokenRepository, magicLinkTokenRepository, FIXED_CLOCK);
        when(revokedRefreshTokenRepository.deleteExpired(any(Instant.class))).thenReturn(2);
        when(magicLinkTokenRepository.deleteExpiredBefore(any(Instant.class))).thenReturn(7);

        scheduler.cleanup();

        ArgumentCaptor<Instant> revokedCaptor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> magicCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(revokedRefreshTokenRepository).deleteExpired(revokedCaptor.capture());
        verify(magicLinkTokenRepository).deleteExpiredBefore(magicCaptor.capture());

        // Refresh-jti чистится строго по exp (сам момент), magic-link — с отставанием в сутки.
        assertThat(revokedCaptor.getValue()).isEqualTo(FIXED_INSTANT);
        assertThat(magicCaptor.getValue()).isEqualTo(FIXED_INSTANT.minus(Duration.ofDays(1)));
        assertThat(magicCaptor.getValue()).isBefore(revokedCaptor.getValue());
        verifyNoMoreInteractions(revokedRefreshTokenRepository, magicLinkTokenRepository);
    }
}
