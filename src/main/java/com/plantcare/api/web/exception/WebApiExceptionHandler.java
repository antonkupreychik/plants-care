package com.plantcare.api.web.exception;

import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Обработчик ошибок spec-first контроллеров ({@code com.plantcare.api.web}) —
 * плоский формат {@code {"error","message"}}.
 *
 * <p>Issue #127: после переезда пакет {@code com.plantcare.api.web} стал вложен в
 * {@code com.plantcare.api}, который покрывает {@link com.plantcare.api.ApiExceptionHandler}
 * (вложенный формат {@code {"error":{...}}}). Чтобы для web-контроллеров выигрывал
 * именно этот, более специфичный обработчик, задаём высший приоритет — иначе два
 * {@code @RestControllerAdvice} с пересекающимися basePackages конфликтуют.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.plantcare.api.web")
public class WebApiExceptionHandler {

    record ApiError(String error, String message) {
    }

    @ExceptionHandler(EntityNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError handleNotFound(EntityNotFoundException e) {
        log.warn("Entity not found: {}", e.getMessage());
        return new ApiError("NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .findFirst()
                .orElse(e.getMessage());
        log.warn("Validation failed: {}", message);
        return new ApiError("VALIDATION_ERROR", message);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiError handleGeneral(Exception e) {
        log.error("Unexpected error", e);
        return new ApiError("INTERNAL_ERROR", "Internal server error");
    }
}
