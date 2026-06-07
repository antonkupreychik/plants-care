package com.plantcare.api.auth.exception;

import lombok.Getter;

/**
 * Бросается при ошибках конвертации гостевого аккаунта (issue #227).
 * Маппится через {@link com.plantcare.api.ApiExceptionHandler}.
 */
@Getter
public class GuestConvertException extends RuntimeException {

    public enum Code {
        NOT_A_GUEST,
        IDENTITY_ALREADY_TAKEN
    }

    private final Code code;

    public GuestConvertException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public static GuestConvertException notAGuest(String message) {
        return new GuestConvertException(Code.NOT_A_GUEST, message);
    }

    public static GuestConvertException identityAlreadyTaken(String message) {
        return new GuestConvertException(Code.IDENTITY_ALREADY_TAKEN, message);
    }
}
