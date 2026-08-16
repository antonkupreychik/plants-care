package com.plantcare.api.config;

import com.plantcare.api.auth.service.TokenPair;
import com.plantcare.api.auth.service.TokenService;
import com.plantcare.core.domain.User;
import com.plantcare.core.repository.UserRepository;
import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Интеграционный тест ресурс-сервера через РЕАЛЬНЫЙ декодер (issue #88, BLOCKING-#1).
 *
 * <p>Reviewer особо отметил: существующий {@code ApiSecurityIT} использует
 * {@code .with(jwt())}, который ПОДМЕНЯЕТ {@code BearerTokenAuthenticationFilter}
 * и обходит реальный {@code appJwtDecoder} с его {@code typ==access}-валидатором.
 * Поэтому здесь мы НЕ используем {@code .with(jwt())}, а ставим настоящий заголовок
 * {@code Authorization: Bearer <token>} с токеном, выпущенным реальным
 * {@link TokenService} на том же секрете (application-test.yml). Так в дело
 * вступает {@code appJwtDecoder} + {@link RequiredTokenTypeValidator}.
 *
 * <p>Брешь, которую ловим: refresh-JWT (тот же секрет/sub/iss, но {@code typ=refresh})
 * НЕ должен авторизовать {@code /api/v1/**}.
 */
@AutoConfigureMockMvc
class RealBearerTokenSecurityIT extends IntegrationTestBase {

    private static final String PROTECTED_ENDPOINT = "/api/v1/plants";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private JwtEncoder appJwtEncoder;

    @Autowired
    private AuthProperties authProperties;

    private final Clock clock = Clock.systemUTC();

    @AfterEach
    void cleanup() {
        userRepository.deleteAll();
    }

    private User persistUser() {
        return userRepository.save(User.builder()
                .email("bearer@example.com")
                .emailVerified(true)
                .build());
    }

    @Test
    void should_reject_refresh_token_used_as_bearer_on_protected_endpoint() throws Exception {
        // arrange — выпускаем НАСТОЯЩУЮ пару и берём refresh-токен (typ=refresh)
        User user = persistUser();
        TokenPair pair = tokenService.issuePair(user);

        // act + assert — refresh как Bearer на защищённом /api/v1/plants → 401,
        // потому что appJwtDecoder требует typ=access. Заголовок настоящий,
        // декодер настоящий (НЕ .with(jwt())).
        mockMvc.perform(get(PROTECTED_ENDPOINT)
                        .header("Authorization", "Bearer " + pair.refreshToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void should_accept_access_token_used_as_bearer_on_protected_endpoint() throws Exception {
        // arrange — тот же пользователь, но используем access-токен
        User user = persistUser();
        TokenPair pair = tokenService.issuePair(user);

        // act + assert — access (typ=access) проходит security и доходит до контроллера (200)
        mockMvc.perform(get(PROTECTED_ENDPOINT)
                        .header("Authorization", "Bearer " + pair.accessToken()))
                .andExpect(status().isOk());
    }

    @Test
    void should_reject_token_without_typ_claim_used_as_bearer() throws Exception {
        // arrange — токен с верной подписью/iss/exp, но БЕЗ claim typ
        User user = persistUser();
        String tokenWithoutTyp = encodeWithoutTyp(user, authProperties.getJwt().getIssuer());

        // act + assert → 401: RequiredTokenTypeValidator валит отсутствие typ
        mockMvc.perform(get(PROTECTED_ENDPOINT)
                        .header("Authorization", "Bearer " + tokenWithoutTyp))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void should_reject_access_token_with_foreign_issuer_used_as_bearer() throws Exception {
        // arrange — правильная подпись и typ=access, но ЧУЖОЙ iss
        User user = persistUser();
        String foreignIssuerToken = encodeAccess(user, "https://evil.example.com");

        // act + assert → 401: JwtIssuerValidator отвергает чужой iss
        mockMvc.perform(get(PROTECTED_ENDPOINT)
                        .header("Authorization", "Bearer " + foreignIssuerToken))
                .andExpect(status().isUnauthorized());
    }

    /** Кодирует токен нашим encoder'ом БЕЗ claim {@code typ}, остальное валидно. */
    private String encodeWithoutTyp(User user, String issuer) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(String.valueOf(user.getId()))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return appJwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /** Кодирует валидный access-токен (typ=access), но с заданным {@code iss}. */
    private String encodeAccess(User user, String issuer) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(String.valueOf(user.getId()))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .claim("typ", "access")
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return appJwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
