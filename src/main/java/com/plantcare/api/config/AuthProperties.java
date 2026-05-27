package com.plantcare.api.config;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Конфигурация мобильной аутентификации (issue #88, ADR-011).
 *
 * <p>Секреты — только из env (см. application.yml). Здесь дефолтов на секреты нет.
 */
@Getter
@RequiredArgsConstructor
@ConfigurationProperties(prefix = "plantcare.auth")
public class AuthProperties {

    /** Настройки наших JWT (access/refresh), подписанных нашим секретом. */
    private final Jwt jwt;

    /** Настройки верификации Sign in with Apple id-token. */
    private final Apple apple;

    /** Настройки верификации Google id-token. */
    private final Google google;

    /** Настройки magic link (email-вход). */
    private final MagicLink magicLink;

    @Getter
    @RequiredArgsConstructor
    public static class Jwt {
        /** HMAC-секрет для подписи/проверки наших токенов (HS256). */
        private final String secret;
        /** Значение claim {@code iss} в наших токенах. */
        private final String issuer;
        /** TTL access-токена. */
        private final Duration accessTtl;
        /** TTL refresh-токена. */
        private final Duration refreshTtl;
    }

    @Getter
    @RequiredArgsConstructor
    public static class Apple {
        /** App bundle id — ожидаемый {@code aud} в Apple id-token. */
        private final String bundleId;
        /** Ожидаемый {@code iss}. */
        private final String issuer;
        /** URI JWKS Apple для верификации подписи. */
        private final String jwksUri;
    }

    @Getter
    @RequiredArgsConstructor
    public static class Google {
        /** OAuth client id — ожидаемый {@code aud} в Google id-token. */
        private final String clientId;
        /** Допустимые значения {@code iss} (Google использует оба варианта). */
        private final List<String> issuers;
        /** URI JWKS Google для верификации подписи. */
        private final String jwksUri;
    }

    @Getter
    @RequiredArgsConstructor
    public static class MagicLink {
        /** Базовый URL ссылки в письме (фронт/диплинк), к которому добавляется токен. */
        private final String baseUrl;
        /** TTL magic-link токена. */
        private final Duration ttl;
    }
}
