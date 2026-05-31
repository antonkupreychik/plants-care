package com.plantcare.api.auth.service;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.plantcare.api.config.AuthProperties;
import com.plantcare.api.auth.exception.AuthTokenException;
import com.plantcare.core.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-тесты {@link TokenService} (issue #88, ADR-011).
 *
 * <p>Используем НАСТОЯЩИЕ Nimbus encoder/decoder на тестовом HMAC-секрете — это
 * не внешний API, а наша же подпись, мокать её бессмысленно. Время — только через
 * подменяемый фиксированный {@link Clock}.
 */
class TokenServiceTest {

    private static final String SECRET = "test-secret-please-change-this-is-32-bytes-minimum-for-hs256";
    // Якорь в будущем: NimbusJwtDecoder валидирует exp по СИСТЕМНЫМ часам, поэтому
    // токены с exp в прошлом он отверг бы как просроченные. Фиксируем «сейчас» в
    // будущем, чтобы exp (= now + TTL) гарантированно был валиден на момент decode.
    private static final Instant FIXED_NOW = Instant.parse("2099-05-22T10:00:00Z");
    private static final Duration ACCESS_TTL = Duration.ofHours(1);
    private static final Duration REFRESH_TTL = Duration.ofDays(30);

    private static SecretKey key() {
        return new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    private static JwtEncoder encoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key()));
    }

    private static JwtDecoder decoder() {
        return NimbusJwtDecoder.withSecretKey(key()).macAlgorithm(MacAlgorithm.HS256).build();
    }

    private static final String ISSUER = "https://plants-care.test";

    /**
     * Декодер как в проде {@code appRefreshJwtDecoder}: подпись + стандартные
     * timestamp/exp + проверка {@code iss}. Нужен, чтобы воспроизвести НЕ-exp
     * ошибку валидации (чужой issuer) и проверить, что она даёт TOKEN_INVALID,
     * а не TOKEN_EXPIRED (issue #88, SHOULD-FIX-#4).
     */
    private static JwtDecoder decoderWithIssuer() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key())
                .macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
                org.springframework.security.oauth2.jwt.JwtValidators.createDefault(),
                new org.springframework.security.oauth2.jwt.JwtIssuerValidator(ISSUER)));
        return decoder;
    }

    private static AuthProperties propsWithIssuer(String issuer) {
        AuthProperties.Jwt jwt = new AuthProperties.Jwt(SECRET, issuer, ACCESS_TTL, REFRESH_TTL);
        return new AuthProperties(true, 1L, jwt, null, null, null);
    }

    private static AuthProperties props() {
        AuthProperties.Jwt jwt = new AuthProperties.Jwt(
                SECRET, "https://plants-care.test", ACCESS_TTL, REFRESH_TTL);
        return new AuthProperties(true, 1L, jwt, null, null, null);
    }

    private TokenService tokenServiceAt(Clock clock) {
        return new TokenService(encoder(), decoder(), props(), clock);
    }

    private static User userWithId(long id) {
        // id назначается БД и не имеет сеттера (BaseEntity @Getter-only) — мок оправдан,
        // нам нужен предсказуемый id без поднятия контейнера. tokensValidFrom = null
        // (мок по умолчанию) — backward-compat ветка без tvf claim.
        User user = org.mockito.Mockito.mock(User.class);
        org.mockito.Mockito.when(user.getId()).thenReturn(id);
        return user;
    }

    private static User userWithTokensValidFrom(long id, Instant tokensValidFrom) {
        User user = org.mockito.Mockito.mock(User.class);
        org.mockito.Mockito.when(user.getId()).thenReturn(id);
        org.mockito.Mockito.when(user.getTokensValidFrom()).thenReturn(tokensValidFrom);
        return user;
    }

    private static Clock fixedUtc(Instant instant) {
        return Clock.fixed(instant, ZoneId.of("UTC"));
    }

    // ===================== issuePair =====================

    @Test
    void should_issue_access_and_refresh_with_correct_claims_when_user_given() {
        // arrange
        TokenService service = tokenServiceAt(fixedUtc(FIXED_NOW));
        JwtDecoder decoder = decoder();

        // act
        TokenPair pair = service.issuePair(userWithId(77L));

        // assert — access
        var access = decoder.decode(pair.accessToken());
        assertThat(access.getSubject()).isEqualTo("77");
        assertThat(access.getClaimAsString("typ")).isEqualTo("access");
        assertThat(access.getId()).isNull();
        assertThat(access.getExpiresAt()).isEqualTo(FIXED_NOW.plus(ACCESS_TTL));
        assertThat(pair.expiresIn()).isEqualTo(ACCESS_TTL.toSeconds());

        // assert — refresh
        var refresh = decoder.decode(pair.refreshToken());
        assertThat(refresh.getSubject()).isEqualTo("77");
        assertThat(refresh.getClaimAsString("typ")).isEqualTo("refresh");
        assertThat(refresh.getId()).isNotNull();
        assertThat(refresh.getExpiresAt()).isEqualTo(FIXED_NOW.plus(REFRESH_TTL));
    }

    @Test
    void should_issue_unique_jti_per_refresh_when_called_twice() {
        // arrange
        TokenService service = tokenServiceAt(fixedUtc(FIXED_NOW));
        JwtDecoder decoder = decoder();

        // act
        TokenPair first = service.issuePair(userWithId(1L));
        TokenPair second = service.issuePair(userWithId(1L));

        // assert
        String firstJti = decoder.decode(first.refreshToken()).getId();
        String secondJti = decoder.decode(second.refreshToken()).getId();
        assertThat(firstJti).isNotEqualTo(secondJti);
    }

    // ===================== tvf claim (issue #178) =====================

    @Test
    void should_carry_tvf_claim_as_epoch_seconds_when_tokens_valid_from_set() {
        // arrange — эпоха задана (logout-all уже был); ожидаем claim tvf = epoch-сек
        Instant epoch = Instant.parse("2099-05-22T09:00:00Z");
        TokenService service = tokenServiceAt(fixedUtc(FIXED_NOW));
        JwtDecoder decoder = decoder();

        // act
        TokenPair pair = service.issuePair(userWithTokensValidFrom(11L, epoch));

        // assert — tvf присутствует и равен epoch-секундам, и на access, и на refresh
        var access = decoder.decode(pair.accessToken());
        var refresh = decoder.decode(pair.refreshToken());
        Object accessTvf = access.getClaim("tvf");
        Object refreshTvf = refresh.getClaim("tvf");
        assertThat(accessTvf).isEqualTo(epoch.getEpochSecond());
        assertThat(refreshTvf).isEqualTo(epoch.getEpochSecond());
    }

    @Test
    void should_omit_tvf_claim_when_tokens_valid_from_null() {
        // arrange — старый юзер без эпохи (backward-compat) → claim tvf не должен попасть в токен
        TokenService service = tokenServiceAt(fixedUtc(FIXED_NOW));
        JwtDecoder decoder = decoder();

        // act
        TokenPair pair = service.issuePair(userWithId(12L));

        // assert
        Object accessTvf = decoder.decode(pair.accessToken()).getClaim("tvf");
        Object refreshTvf = decoder.decode(pair.refreshToken()).getClaim("tvf");
        assertThat(accessTvf).isNull();
        assertThat(refreshTvf).isNull();
    }

    // ===================== parseRefresh =====================

    @Test
    void should_parse_refresh_when_token_is_valid_refresh() {
        // arrange
        TokenService service = tokenServiceAt(fixedUtc(FIXED_NOW));
        TokenPair pair = service.issuePair(userWithId(42L));

        // act
        TokenService.RefreshToken parsed = service.parseRefresh(pair.refreshToken());

        // assert
        assertThat(parsed.userId()).isEqualTo(42L);
        assertThat(parsed.jti()).isNotNull();
        assertThat(parsed.expiresAt()).isEqualTo(FIXED_NOW.plus(REFRESH_TTL));
    }

    @Test
    void should_reject_access_token_when_used_as_refresh() {
        // arrange
        TokenService service = tokenServiceAt(fixedUtc(FIXED_NOW));
        TokenPair pair = service.issuePair(userWithId(5L));

        // act + assert — access нельзя подсунуть как refresh
        assertThatThrownBy(() -> service.parseRefresh(pair.accessToken()))
                .isInstanceOf(AuthTokenException.class)
                .satisfies(e -> assertThat(((AuthTokenException) e).getCode())
                        .isEqualTo(AuthTokenException.Code.TOKEN_INVALID));
    }

    @Test
    void should_reject_garbage_token_when_parsing_refresh() {
        // arrange
        TokenService service = tokenServiceAt(fixedUtc(FIXED_NOW));

        // act + assert
        assertThatThrownBy(() -> service.parseRefresh("not-a-jwt"))
                .isInstanceOf(AuthTokenException.class)
                .satisfies(e -> assertThat(((AuthTokenException) e).getCode())
                        .isEqualTo(AuthTokenException.Code.TOKEN_INVALID));
    }

    @Test
    void should_reject_expired_refresh_when_exp_is_in_the_past() {
        // arrange — выдаём токен с Clock далеко в прошлом, чтобы exp = (прошлое + TTL)
        // тоже оказался в прошлом относительно реального времени декодера.
        // NimbusJwtDecoder валидирует exp по системным часам, поэтому Clock сдвигаем
        // именно на выпуске: refresh с exp в 2020 году заведомо просрочен сейчас.
        Instant longAgo = Instant.parse("2020-01-01T00:00:00Z");
        TokenService issuer = tokenServiceAt(fixedUtc(longAgo));
        TokenPair pair = issuer.issuePair(userWithId(9L));

        TokenService service = tokenServiceAt(fixedUtc(FIXED_NOW));

        // act + assert — просроченный refresh не должен приниматься
        assertThatThrownBy(() -> service.parseRefresh(pair.refreshToken()))
                .isInstanceOf(AuthTokenException.class)
                .satisfies(e -> assertThat(((AuthTokenException) e).getCode())
                        .isEqualTo(AuthTokenException.Code.TOKEN_EXPIRED));
    }

    @Test
    void should_classify_foreign_issuer_as_invalid_not_expired() {
        // arrange — refresh выпущен с ЧУЖИМ issuer, но НЕ просрочен (валидный exp).
        // Декодер с JwtIssuerValidator отвергнет его JwtValidationException'ом, где
        // ошибка относится к iss, а не exp. isExpired(...) обязан вернуть false →
        // TOKEN_INVALID, а не TOKEN_EXPIRED.
        TokenService foreignIssuer = new TokenService(
                encoder(), decoderWithIssuer(), propsWithIssuer("https://evil.example.com"),
                fixedUtc(FIXED_NOW));
        String foreignIssRefresh = foreignIssuer.issuePair(userWithId(7L)).refreshToken();

        TokenService service = new TokenService(
                encoder(), decoderWithIssuer(), propsWithIssuer(ISSUER), fixedUtc(FIXED_NOW));

        // act + assert
        assertThatThrownBy(() -> service.parseRefresh(foreignIssRefresh))
                .isInstanceOf(AuthTokenException.class)
                .satisfies(e -> assertThat(((AuthTokenException) e).getCode())
                        .isEqualTo(AuthTokenException.Code.TOKEN_INVALID));
    }

    @Test
    void should_classify_expired_as_expired_when_issuer_decoder_used() {
        // arrange — тот же issuer (валиден), но exp в прошлом → именно TOKEN_EXPIRED.
        // Зеркало предыдущего теста: убеждаемся, что exp-ветка не схлопнулась в INVALID.
        Instant longAgo = Instant.parse("2020-01-01T00:00:00Z");
        TokenService issuer = new TokenService(
                encoder(), decoderWithIssuer(), propsWithIssuer(ISSUER), fixedUtc(longAgo));
        String expiredRefresh = issuer.issuePair(userWithId(8L)).refreshToken();

        TokenService service = new TokenService(
                encoder(), decoderWithIssuer(), propsWithIssuer(ISSUER), fixedUtc(FIXED_NOW));

        // act + assert
        assertThatThrownBy(() -> service.parseRefresh(expiredRefresh))
                .isInstanceOf(AuthTokenException.class)
                .satisfies(e -> assertThat(((AuthTokenException) e).getCode())
                        .isEqualTo(AuthTokenException.Code.TOKEN_EXPIRED));
    }

    // ===================== timezone =====================

    @Test
    void should_compute_exp_as_utc_instant_regardless_of_clock_zone() {
        // arrange — один и тот же момент времени, но Clock в зоне Asia/Almaty (UTC+5).
        // exp обязан быть тем же UTC-Instant, что и в UTC-зоне: Instant не зависит от зоны.
        Clock almatyClock = Clock.fixed(FIXED_NOW, ZoneId.of("Asia/Almaty"));
        TokenService service = new TokenService(encoder(), decoder(), props(), almatyClock);
        JwtDecoder decoder = decoder();

        // act
        TokenPair pair = service.issuePair(userWithId(3L));

        // assert — exp = FIXED_NOW + TTL в абсолютном UTC, без сдвига на +5ч
        var access = decoder.decode(pair.accessToken());
        assertThat(access.getExpiresAt()).isEqualTo(FIXED_NOW.plus(ACCESS_TTL));
        assertThat(access.getIssuedAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    void should_match_utc_and_moscow_clocks_for_same_instant() {
        // arrange — Europe/Moscow (UTC+3) и UTC на один и тот же Instant
        TokenService utc = new TokenService(encoder(), decoder(), props(), fixedUtc(FIXED_NOW));
        TokenService moscow = new TokenService(encoder(), decoder(), props(),
                Clock.fixed(FIXED_NOW, ZoneId.of("Europe/Moscow")));
        JwtDecoder decoder = decoder();

        // act
        Instant utcExp = decoder.decode(utc.issuePair(userWithId(1L)).accessToken()).getExpiresAt();
        Instant moscowExp = decoder.decode(moscow.issuePair(userWithId(1L)).accessToken()).getExpiresAt();

        // assert
        assertThat(moscowExp).isEqualTo(utcExp);
    }

    // ===================== guard: тест действительно проверяет подпись =====================

    @Test
    void should_reject_token_signed_with_different_secret() {
        // arrange — токен, подписанный ЧУЖИМ секретом, не должен парситься нашим декодером
        SecretKey otherKey = new SecretKeySpec(
                "completely-different-secret-key-also-32-bytes-long-xx".getBytes(StandardCharsets.UTF_8),
                "HmacSHA256");
        JwtEncoder foreignEncoder = new NimbusJwtEncoder(new ImmutableSecret<>(otherKey));
        TokenService foreignIssuer = new TokenService(
                foreignEncoder, decoder(), props(), fixedUtc(FIXED_NOW));
        String foreignRefresh = foreignIssuer.issuePair(userWithId(1L)).refreshToken();

        TokenService service = tokenServiceAt(fixedUtc(FIXED_NOW));

        // act + assert
        assertThatThrownBy(() -> service.parseRefresh(foreignRefresh))
                .isInstanceOf(AuthTokenException.class);
    }

    @Test
    void should_keep_issuers_list_unused_here_but_props_constructible() {
        // sanity: AuthProperties конструируется в тестовой форме (защищает от дрейфа конструктора)
        AuthProperties p = props();
        assertThat(p.getJwt().getIssuer()).isEqualTo("https://plants-care.test");
        assertThat(List.of(p.getJwt().getAccessTtl(), p.getJwt().getRefreshTtl()))
                .containsExactly(ACCESS_TTL, REFRESH_TTL);
    }
}
