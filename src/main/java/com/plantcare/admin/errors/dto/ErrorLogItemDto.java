package com.plantcare.admin.errors.dto;

import java.time.Instant;

/**
 * Строка списка ошибок на {@code /admin/errors} (issue #97).
 * Стек-трейс сюда не тянем — он только в деталке.
 */
public record ErrorLogItemDto(
        long id,
        Instant createdAt,
        String level,
        String loggerName,
        String message,
        String exceptionClass,
        Long userId,
        String requestPath,
        String correlationId
) {

    /** Короткое имя логгера ({@code AdminErrorsService}) — полное не влезает в колонку. */
    public String shortLogger() {
        if (loggerName == null) {
            return "";
        }
        int dot = loggerName.lastIndexOf('.');
        return dot < 0 ? loggerName : loggerName.substring(dot + 1);
    }
}
