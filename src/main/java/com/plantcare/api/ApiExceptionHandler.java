package com.plantcare.api;

import com.plantcare.api.auth.exception.AuthTokenException;
import com.plantcare.api.auth.exception.GuestConvertException;
import com.plantcare.api.auth.exception.RateLimitExceededException;
import com.plantcare.api.dto.ApiErrorResponse;
import com.plantcare.api.dto.FieldError;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Глобальный обработчик ошибок REST API ({@code /api/**}).
 * Возвращает ответы в едином формате {@link ApiErrorResponse}.
 */
@Slf4j
@RestControllerAdvice(basePackages = "com.plantcare.api")
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        List<FieldError> details = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("VALIDATION_ERROR", "Validation failed", details));
    }

    @ExceptionHandler({HandlerMethodValidationException.class, ConstraintViolationException.class})
    public ResponseEntity<ApiErrorResponse> handleMethodValidation(Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("VALIDATION_ERROR", "Validation failed"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("BAD_REQUEST", "Invalid parameter value: " + e.getValue()));
    }

    /**
     * Тело не читается. Если причина — недопустимое значение enum/семантически
     * невалидное поле (Jackson оборачивает {@link IllegalArgumentException} из
     * {@code @JsonCreator}), это 422 (issue #220: неизвестный {@code eventType}).
     * Прочий синтаксический мусор остаётся 400.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleNotReadable(HttpMessageNotReadableException e) {
        if (hasCause(e, IllegalArgumentException.class)) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(ApiErrorResponse.of("VALIDATION_ERROR", "Validation failed"));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("BAD_REQUEST", "Malformed request body"));
    }

    private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return true;
            }
        }
        return false;
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(EntityNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiErrorResponse.of("NOT_FOUND", "Resource not found"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiErrorResponse.of("ACCESS_DENIED", "Access denied"));
    }

    @ExceptionHandler(AuthTokenException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthToken(AuthTokenException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiErrorResponse.of(e.getCode().name(), e.getMessage()));
    }

    @ExceptionHandler(GuestConvertException.class)
    public ResponseEntity<ApiErrorResponse> handleGuestConvert(GuestConvertException e) {
        HttpStatus status = switch (e.getCode()) {
            case NOT_A_GUEST -> HttpStatus.FORBIDDEN;
            case IDENTITY_ALREADY_TAKEN -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status)
                .body(ApiErrorResponse.of(e.getCode().name(), e.getMessage()));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleRateLimit(RateLimitExceededException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(e.getRetryAfterSeconds()))
                .body(ApiErrorResponse.of("RATE_LIMITED", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("BAD_REQUEST", e.getMessage()));
    }

    @ExceptionHandler(LocationNotEmptyException.class)
    public ResponseEntity<ApiErrorResponse> handleLocationNotEmpty(LocationNotEmptyException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of("LOCATION_NOT_EMPTY", e.getMessage()));
    }

    @ExceptionHandler(RoomNotEmptyException.class)
    public ResponseEntity<ApiErrorResponse> handleRoomNotEmpty(RoomNotEmptyException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of("ROOM_NOT_EMPTY", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiErrorResponse> handleConflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of("CONFLICT", e.getMessage()));
    }

    /**
     * {@link ResponseStatusException} — используется для HTTP-статусов без стандартного
     * исключения (например, 410 Gone для просроченного/использованного инвайта).
     * Пробрасывает статус из исключения, формат ответа — единый ApiErrorResponse.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> handleResponseStatus(ResponseStatusException e) {
        String code = e.getStatusCode().value() == 410 ? "GONE" : e.getStatusCode().toString();
        return ResponseEntity.status(e.getStatusCode())
                .body(ApiErrorResponse.of(code, e.getReason() != null ? e.getReason() : e.getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiErrorResponse> handleRuntime(RuntimeException e) {
        log.error("Unhandled API error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiErrorResponse.of("INTERNAL_ERROR", "Internal server error"));
    }
}
