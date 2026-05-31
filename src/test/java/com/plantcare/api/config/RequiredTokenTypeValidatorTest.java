package com.plantcare.api.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-тесты {@link RequiredTokenTypeValidator} (issue #88).
 *
 * <p>Это сердце фикса BLOCKING-#1: ресурс-сервер должен принимать только
 * {@code typ == access}. Тестируем валидатор напрямую — быстро и без контекста —
 * для всех трёх веток: access (ok), refresh (fail), отсутствие claim (fail).
 */
class RequiredTokenTypeValidatorTest {

    private final RequiredTokenTypeValidator validator =
            new RequiredTokenTypeValidator(RequiredTokenTypeValidator.TYPE_ACCESS);

    private static Jwt jwtWithTyp(String typ) {
        Jwt.Builder builder = Jwt.withTokenValue("token-value")
                .header("alg", "HS256")
                .subject("42")
                .issuedAt(Instant.parse("2099-01-01T00:00:00Z"))
                .expiresAt(Instant.parse("2099-01-01T01:00:00Z"));
        if (typ != null) {
            builder.claim("typ", typ);
        } else {
            // claim, чтобы билдер не падал на пустом claim-set, но без typ
            builder.claim("scope", "read");
        }
        return builder.build();
    }

    @Test
    void should_succeed_when_typ_is_access() {
        // arrange
        Jwt accessJwt = jwtWithTyp("access");

        // act
        OAuth2TokenValidatorResult result = validator.validate(accessJwt);

        // assert
        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void should_fail_when_typ_is_refresh() {
        // arrange
        Jwt refreshJwt = jwtWithTyp("refresh");

        // act
        OAuth2TokenValidatorResult result = validator.validate(refreshJwt);

        // assert
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors())
                .extracting(err -> err.getErrorCode())
                .containsExactly("invalid_token");
    }

    @Test
    void should_fail_when_typ_claim_is_absent() {
        // arrange — токен без claim typ (старый/чужой формат)
        Jwt noTypJwt = jwtWithTyp(null);

        // act
        OAuth2TokenValidatorResult result = validator.validate(noTypJwt);

        // assert
        assertThat(result.hasErrors()).isTrue();
    }

    @Test
    void should_fail_when_typ_is_unexpected_value() {
        // arrange — произвольное значение typ тоже не должно проходить
        Jwt weirdJwt = Jwt.withTokenValue("token-value")
                .header("alg", "HS256")
                .subject("42")
                .issuedAt(Instant.parse("2099-01-01T00:00:00Z"))
                .expiresAt(Instant.parse("2099-01-01T01:00:00Z"))
                .claims(c -> c.putAll(Map.of("typ", "id")))
                .build();

        // act
        OAuth2TokenValidatorResult result = validator.validate(weirdJwt);

        // assert
        assertThat(result.hasErrors()).isTrue();
    }
}
