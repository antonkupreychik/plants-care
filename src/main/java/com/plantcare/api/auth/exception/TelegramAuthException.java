package com.plantcare.api.auth.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Issue #318: бизнес-исключение входа существующих Telegram-юзеров.
 *
 * <p>Каждый код несёт собственный HTTP-статус и стабильный машиночитаемый код
 * для контракта (отдаётся в {@code ApiErrorResponse.error.code}). Маппится в
 * {@code ApiExceptionHandler}.
 */
@Getter
public class TelegramAuthException extends RuntimeException {

    /** Стабильные коды контракта (см. issue #318). */
    public enum Code {
        /** По {@code telegram_chat_id} из сессии пользователя нет. */
        TELEGRAM_USER_NOT_FOUND("telegram_user_not_found", HttpStatus.NOT_FOUND),
        /** Код не совпал (счётчик попыток увеличен). */
        INVALID_CODE("invalid_code", HttpStatus.UNAUTHORIZED),
        /** Сессия истекла или не существует. */
        SESSION_EXPIRED("session_expired", HttpStatus.GONE),
        /** Превышен лимит попыток — сессия погашена. */
        TOO_MANY_ATTEMPTS("too_many_attempts", HttpStatus.TOO_MANY_REQUESTS);

        private final String machineCode;
        private final HttpStatus status;

        Code(String machineCode, HttpStatus status) {
            this.machineCode = machineCode;
            this.status = status;
        }

        public String machineCode() {
            return machineCode;
        }

        public HttpStatus status() {
            return status;
        }
    }

    private final Code code;

    public TelegramAuthException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public static TelegramAuthException userNotFound() {
        return new TelegramAuthException(Code.TELEGRAM_USER_NOT_FOUND,
                "No user is linked to this Telegram account");
    }

    public static TelegramAuthException invalidCode() {
        return new TelegramAuthException(Code.INVALID_CODE, "Verification code is invalid");
    }

    public static TelegramAuthException sessionExpired() {
        return new TelegramAuthException(Code.SESSION_EXPIRED, "Login session expired or not found");
    }

    public static TelegramAuthException tooManyAttempts() {
        return new TelegramAuthException(Code.TOO_MANY_ATTEMPTS, "Too many verification attempts");
    }
}
