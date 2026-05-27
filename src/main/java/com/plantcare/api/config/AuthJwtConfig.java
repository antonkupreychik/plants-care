package com.plantcare.api.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Encoder/decoder НАШИХ токенов (access/refresh), подписанных симметричным
 * HMAC-секретом (HS256), issue #88. Это НЕ про Apple/Google id-token —
 * у тех собственные JWKS-верификаторы в сервисах.
 *
 * <p>Бин {@link JwtDecoder} с именем {@code appJwtDecoder} используется как
 * ресурс-сервером ({@code ApiSecurityConfig}), так и {@code TokenService} для
 * разбора refresh-токенов — единый источник истины по подписи.
 */
@Configuration
@org.springframework.boot.context.properties.EnableConfigurationProperties(AuthProperties.class)
@RequiredArgsConstructor
public class AuthJwtConfig {

    private final AuthProperties authProperties;

    /**
     * Плейсхолдер-секрет на случай {@code plantcare.auth.enabled=false} (локальная
     * разработка): бины-подписанты всё равно создаются, но не используются —
     * {@code ApiSecurityConfig} открывает {@code /api/v1/**} без токена. В prod
     * сюда не попадаем: при {@code enabled=true} пустой секрет = ошибка старта.
     */
    private static final String AUTH_DISABLED_PLACEHOLDER_SECRET =
            "local-dev-auth-disabled-placeholder-secret-0000000000000000";

    private SecretKey hmacKey() {
        String secret = authProperties.getJwt().getSecret();
        if (secret == null || secret.isBlank()) {
            if (authProperties.isEnabled()) {
                throw new IllegalStateException(
                        "AUTH_JWT_SECRET must be set (>= 32 bytes) when plantcare.auth.enabled=true");
            }
            secret = AUTH_DISABLED_PLACEHOLDER_SECRET;
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    @Bean
    public java.security.SecureRandom authSecureRandom() {
        return new java.security.SecureRandom();
    }

    @Bean
    public JwtEncoder appJwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(hmacKey()));
    }

    /**
     * Декодер access-токенов для РЕСУРС-СЕРВЕРА ({@code ApiSecurityConfig}).
     * Помимо HS256-подписи и стандартных timestamp/exp-валидаторов требует
     * {@code iss == наш issuer} и {@code typ == access}. Это закрывает дыру,
     * когда refresh-JWT (тот же секрет/sub/iss, но {@code typ=refresh})
     * принимался ресурс-сервером и авторизовал {@code /api/v1/**} на refresh-TTL.
     * Отклонённый токен приводит к стандартному 401.
     */
    @Bean
    public JwtDecoder appJwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(hmacKey())
                .macAlgorithm(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256)
                .build();

        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(),
                new JwtIssuerValidator(authProperties.getJwt().getIssuer()),
                new RequiredTokenTypeValidator(RequiredTokenTypeValidator.TYPE_ACCESS));
        decoder.setJwtValidator(validator);
        return decoder;
    }

    /**
     * Декодер для разбора refresh-токенов в {@code TokenService} (ротация).
     * Проверяет HS256-подпись, стандартные timestamp/exp и {@code iss}; сам
     * {@code typ == refresh} проверяет {@code TokenService.parseRefresh}. Отдельный
     * бин от {@link #appJwtDecoder()}, который требует {@code typ == access} и
     * потому для refresh не годится.
     */
    @Bean
    public JwtDecoder appRefreshJwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(hmacKey())
                .macAlgorithm(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256)
                .build();

        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(),
                new JwtIssuerValidator(authProperties.getJwt().getIssuer()));
        decoder.setJwtValidator(validator);
        return decoder;
    }
}
