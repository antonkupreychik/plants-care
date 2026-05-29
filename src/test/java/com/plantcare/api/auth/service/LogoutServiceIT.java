package com.plantcare.api.auth.service;

import com.plantcare.api.auth.exception.AuthTokenException;
import com.plantcare.bot.support.IntegrationTestBase;
import com.plantcare.core.domain.User;
import com.plantcare.core.repository.RevokedRefreshTokenRepository;
import com.plantcare.core.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Интеграционный тест logout / logout-all и эпохи {@code tokens_valid_from}
 * (issue #178, ADR-015). Реальный Postgres — отзыв jti в
 * {@code revoked_refresh_tokens} и UPDATE эпохи через {@code UserRepository}.
 *
 * <p>Время контролируется {@link MutableClock} (@Primary), которым пользуются и
 * {@link TokenService} (штамп {@code iat}/{@code tvf}), и {@link LogoutService}
 * (штамп {@code tokens_valid_from}). Якорь — в будущем (2099), чтобы exp
 * выпущенных токенов проходил валидацию реального системного декодера.
 */
@Import(LogoutServiceIT.FixedClockConfig.class)
class LogoutServiceIT extends IntegrationTestBase {

    // exp выпущенных токенов = anchor + TTL должен быть в будущем относительно
    // СИСТЕМНЫХ часов на момент decode, иначе appRefreshJwtDecoder отвергнет как expired.
    private static final Instant ANCHOR = Instant.parse("2099-05-22T10:00:00Z");

    @Autowired
    private LogoutService logoutService;

    @Autowired
    private RefreshService refreshService;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private RevokedRefreshTokenRepository revokedRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void resetClock() {
        clock.set(ANCHOR);
    }

    @AfterEach
    void cleanup() {
        revokedRepository.deleteAll();
        userRepository.deleteAll();
    }

    private User persistUser() {
        return userRepository.save(User.builder()
                .telegramChatId(null)
                .email("logout-" + UUID.randomUUID() + "@plantcare.test")
                .emailVerified(true)
                .build());
    }

    // ===================== AC1: logout отзывает предъявленный refresh =====================

    @Test
    void should_revoke_presented_refresh_when_logout_then_rotate_is_rejected() {
        // arrange
        User user = persistUser();
        TokenPair pair = tokenService.issuePair(user);
        UUID jti = tokenService.parseRefresh(pair.refreshToken()).jti();

        // act
        logoutService.logout(user.getId(), pair.refreshToken());

        // assert — jti записан как отозванный
        assertThat(revokedRepository.existsByJti(jti)).isTrue();

        // assert — последующая ротация этим токеном → TOKEN_REVOKED
        assertThatThrownBy(() -> refreshService.rotate(pair.refreshToken()))
                .isInstanceOf(AuthTokenException.class)
                .satisfies(e -> assertThat(((AuthTokenException) e).getCode())
                        .isEqualTo(AuthTokenException.Code.TOKEN_REVOKED));
    }

    // ===================== AC2: повторный logout идемпотентен =====================

    @Test
    void should_stay_idempotent_when_logout_called_twice_with_same_token() {
        // arrange
        User user = persistUser();
        TokenPair pair = tokenService.issuePair(user);
        UUID jti = tokenService.parseRefresh(pair.refreshToken()).jti();

        // act — дважды отзываем один и тот же токен
        logoutService.logout(user.getId(), pair.refreshToken());

        // assert — второй вызов не бросает (rowcount игнорируется)
        assertThatCode(() -> logoutService.logout(user.getId(), pair.refreshToken()))
                .doesNotThrowAnyException();

        // assert — токен ровно один раз в таблице (PK по jti, дублей нет)
        assertThat(revokedRepository.existsByJti(jti)).isTrue();
        assertThat(revokedRepository.count()).isEqualTo(1);
    }

    // ===================== AC3: толерантность к мусору/просрочке =====================

    @Test
    void should_swallow_error_when_logout_with_garbage_token() {
        // arrange
        User user = persistUser();

        // act + assert — невалидный токен → без исключения, ничего не отозвано
        assertThatCode(() -> logoutService.logout(user.getId(), "not-a-jwt"))
                .doesNotThrowAnyException();
        assertThat(revokedRepository.count()).isEqualTo(0);
    }

    @Test
    void should_swallow_error_when_logout_with_expired_token() {
        // arrange — токен выпущен далеко в прошлом → exp в прошлом относительно decode
        User user = persistUser();
        clock.set(Instant.parse("2020-01-01T00:00:00Z"));
        TokenPair expiredPair = tokenService.issuePair(user);
        clock.set(ANCHOR);

        // act + assert — просроченный refresh → 204-семантика, без исключения и без отзыва
        assertThatCode(() -> logoutService.logout(user.getId(), expiredPair.refreshToken()))
                .doesNotThrowAnyException();
        assertThat(revokedRepository.count()).isEqualTo(0);
    }

    // ===================== AC4: чужой токен не отзывается =====================

    @Test
    void should_not_revoke_token_when_it_belongs_to_another_user() {
        // arrange — токен принадлежит userB, logout делает userA
        User userA = persistUser();
        User userB = persistUser();
        TokenPair bPair = tokenService.issuePair(userB);
        UUID bJti = tokenService.parseRefresh(bPair.refreshToken()).jti();

        // act — userA пытается отозвать чужой токен
        logoutService.logout(userA.getId(), bPair.refreshToken());

        // assert — токен userB НЕ отозван
        assertThat(revokedRepository.existsByJti(bJti)).isFalse();

        // assert — токен userB всё ещё ротируется штатно
        TokenPair rotated = refreshService.rotate(bPair.refreshToken());
        assertThat(rotated.refreshToken()).isNotBlank();
    }

    // ===================== AC5: logout-all выставляет эпоху =====================

    @Test
    void should_set_epoch_and_reject_old_refresh_when_logout_all() {
        // arrange — токен выпущен в ANCHOR
        User user = persistUser();
        TokenPair oldPair = tokenService.issuePair(user);

        // act — спустя час делаем logout-all (эпоха = ANCHOR + 1h > iat токена)
        clock.set(ANCHOR.plusSeconds(3600));
        logoutService.logoutAll(user.getId());

        // assert — эпоха записана в users.tokens_valid_from
        Instant storedEpoch = userRepository.findById(user.getId()).orElseThrow().getTokensValidFrom();
        assertThat(storedEpoch).isEqualTo(ANCHOR.plusSeconds(3600));

        // assert — старый refresh (iat < эпоха) → TOKEN_REVOKED при ротации
        assertThatThrownBy(() -> refreshService.rotate(oldPair.refreshToken()))
                .isInstanceOf(AuthTokenException.class)
                .satisfies(e -> assertThat(((AuthTokenException) e).getCode())
                        .isEqualTo(AuthTokenException.Code.TOKEN_REVOKED));
    }

    // ===================== AC5 (regression #178): суб-секундная точность эпохи =====================

    @Test
    void should_allow_rotation_for_token_issued_same_second_as_logout_all() {
        // arrange — часы на момент С суб-секундной компонентой (.300):
        // iat токена флорится до целой секунды (...:05.000) при кодировании в JWT NumericDate.
        Instant subSecond = Instant.parse("2099-05-22T10:00:05.300Z");
        User user = persistUser();
        clock.set(subSecond);
        TokenPair samePair = tokenService.issuePair(user);

        // act — logout-all В ТОТ ЖЕ instant: tokens_valid_from = ...:05 (truncatedTo SECONDS).
        logoutService.logoutAll(user.getId());

        // assert — эпоха усечена до секунды (.300 отброшено), а не сохранена как микросекунды
        Instant storedEpoch = userRepository.findById(user.getId()).orElseThrow().getTokensValidFrom();
        assertThat(storedEpoch).isEqualTo(Instant.parse("2099-05-22T10:00:05Z"));

        // assert — токен той же секунды (iat ...:05 == эпоха ...:05) валиден, ротация проходит.
        // На дореформенном коде (микросекундный tvf + isBefore) ротация была бы ошибочно отклонена.
        TokenPair rotated = refreshService.rotate(samePair.refreshToken());
        assertThat(rotated.refreshToken()).isNotBlank();
        assertThat(rotated.refreshToken()).isNotEqualTo(samePair.refreshToken());
    }

    // ===================== AC6: backward-compat (tokens_valid_from = NULL) =====================

    @Test
    void should_allow_rotation_when_tokens_valid_from_is_null() {
        // arrange — юзер без эпохи (NULL), токен без tvf claim
        User user = persistUser();
        assertThat(user.getTokensValidFrom()).isNull();
        TokenPair pair = tokenService.issuePair(user);

        // act — ротация при NULL-эпохе: проверка пропускается
        TokenPair rotated = refreshService.rotate(pair.refreshToken());

        // assert — новая пара выдана штатно (критичный edge backward-compat)
        assertThat(rotated.refreshToken()).isNotBlank();
        assertThat(rotated.refreshToken()).isNotEqualTo(pair.refreshToken());
    }

    // ===================== AC8: новый токен после logout-all валиден =====================

    @Test
    void should_rotate_fine_for_pair_issued_after_logout_all() {
        // arrange — logout-all выставил эпоху в ANCHOR
        User user = persistUser();
        logoutService.logoutAll(user.getId());
        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getTokensValidFrom()).isEqualTo(ANCHOR);

        // act — пара выпущена ПОСЛЕ эпохи (iat = ANCHOR+1мин >= tokens_valid_from)
        clock.set(ANCHOR.plusSeconds(60));
        TokenPair freshPair = tokenService.issuePair(reloaded);

        // assert — свежая пара ротируется без TOKEN_REVOKED
        TokenPair rotated = refreshService.rotate(freshPair.refreshToken());
        assertThat(rotated.refreshToken()).isNotBlank();
    }

    // ====================================================================
    // test infra: подменяемый Clock, общий для TokenService и LogoutService
    // ====================================================================

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            // UTC как в проде (ClockConfig = systemUTC).
            return new MutableClock(ANCHOR, ZoneOffset.UTC);
        }
    }

    /** Clock с изменяемым instant — позволяет «двигать» время между шагами теста. */
    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;
        private final ZoneId zone;

        MutableClock(Instant initial, ZoneId zone) {
            this.now = new AtomicReference<>(initial);
            this.zone = zone;
        }

        void set(Instant instant) {
            now.set(instant);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(now.get(), zone);
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
