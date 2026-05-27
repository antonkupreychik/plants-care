package com.plantcare.api.config;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Требует, чтобы claim {@code typ} наших HS256-токенов был равен заданному
 * значению (issue #88). Нужен ресурс-серверу, чтобы refresh-токен (тот же
 * секрет/sub/iss, но {@code typ=refresh}) НЕ авторизовал {@code /api/v1/**}.
 *
 * <p>Значения {@code typ} (access/refresh) задаёт {@code TokenService} при
 * выпуске; здесь сравнение строкой, чтобы не тянуть зависимость на service-слой.
 */
public class RequiredTokenTypeValidator implements OAuth2TokenValidator<Jwt> {

    static final String CLAIM_TYPE = "typ";

    /** Значение {@code typ} для access-токена (синхронно с {@code TokenService}). */
    public static final String TYPE_ACCESS = "access";

    private final String requiredType;

    public RequiredTokenTypeValidator(String requiredType) {
        this.requiredType = requiredType;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        if (requiredType.equals(token.getClaimAsString(CLAIM_TYPE))) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                "invalid_token",
                "Required token type '" + requiredType + "' is missing",
                null));
    }
}
