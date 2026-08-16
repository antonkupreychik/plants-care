package com.plantcare.admin.errors.dto;

import java.time.Instant;

/**
 * Деталка одной ошибки на {@code /admin/errors/{id}} (issue #97): полный стек плюс
 * контекст (юзер, URL, correlation id).
 */
public record ErrorLogDetailDto(
        long id,
        Instant createdAt,
        String level,
        String loggerName,
        String message,
        String exceptionClass,
        String fingerprint,
        String stackTrace,
        Long userId,
        String requestPath,
        String correlationId,
        String threadName
) {
}
