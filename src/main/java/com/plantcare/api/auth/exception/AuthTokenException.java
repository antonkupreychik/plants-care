package com.plantcare.api.auth.exception;

import lombok.Getter;

/**
 * Бизнес-исключение слоя аутентификации (issue #88). Маппится в HTTP 401
 * с машиночитаемым кодом через {@code WebApiExceptionHandler}.
 *
 * <p>Покрывает как наши токены (access/refresh), так и внешние id-token
 * провайдеров (Apple/Google) и magic-link токены — для клиента это всё
 * «проблема с токеном», различаемая по {@link Code}.
 */
@Getter
public class AuthTokenException extends RuntimeException {

    /** Стабильные коды для контракта API (см. errors.yaml / responses.yaml). */
    public enum Code {
        TOKEN_EXPIRED,
        TOKEN_INVALID,
        TOKEN_REVOKED,
        /**
         * Провайдер (Apple) не дал пригодного для линковки идентификатора:
         * email отсутствует/не подтверждён И provider subject ранее не привязан.
         * Это первый вход, который мы не можем сопоставить с аккаунтом.
         */
        EMAIL_REQUIRED
    }

    private final Code code;

    public AuthTokenException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public AuthTokenException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public static AuthTokenException expired(String message) {
        return new AuthTokenException(Code.TOKEN_EXPIRED, message);
    }

    public static AuthTokenException invalid(String message) {
        return new AuthTokenException(Code.TOKEN_INVALID, message);
    }

    public static AuthTokenException invalid(String message, Throwable cause) {
        return new AuthTokenException(Code.TOKEN_INVALID, message, cause);
    }

    public static AuthTokenException revoked(String message) {
        return new AuthTokenException(Code.TOKEN_REVOKED, message);
    }

    public static AuthTokenException emailRequired(String message) {
        return new AuthTokenException(Code.EMAIL_REQUIRED, message);
    }
}
