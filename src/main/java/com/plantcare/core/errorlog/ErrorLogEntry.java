package com.plantcare.core.errorlog;

import java.time.Instant;

/**
 * Одна запись журнала ошибок (issue #97) — то, что аппендер снял с логового события
 * и что уйдёт батчем в {@code error_logs}.
 *
 * <p>Иммутабельный снимок: логовое событие ({@code ILoggingEvent}) дальше аппендера
 * не живёт, поэтому всё нужное (MDC, стек, тред) вынимается сразу и складывается сюда.
 *
 * @param occurredAt    момент события (UTC), берётся из timestamp логового события
 * @param level         уровень (WARN/ERROR)
 * @param loggerName    имя логгера
 * @param message       отформатированное сообщение, уже обрезанное до лимита
 * @param exceptionClass FQCN исключения либо {@code null}, если события без throwable
 * @param fingerprint   ключ группировки одинаковых ошибок, см. {@link ErrorFingerprint}
 * @param stackTrace    полный стек (с causes), уже обрезанный до лимита
 * @param userId        {@code users.id} из MDC либо {@code null} (фон/шедулер/аноним)
 * @param requestPath   URI запроса из MDC либо {@code null}
 * @param correlationId correlation id запроса из MDC либо {@code null}
 * @param threadName    поток, в котором произошла ошибка
 */
public record ErrorLogEntry(
        Instant occurredAt,
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
