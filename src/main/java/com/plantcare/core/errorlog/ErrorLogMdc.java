package com.plantcare.core.errorlog;

/**
 * Ключи MDC, из которых журнал ошибок (issue #97) берёт контекст запроса.
 *
 * <p>Заполняет их {@link com.plantcare.core.observability.MdcContextFilter} на каждый
 * HTTP-запрос; читает {@link ErrorLogDbAppender} в момент логового события. MDC выбран
 * как транспорт осознанно: аппендер не должен знать ни про Spring Security, ни про
 * сервлет-API, а логовое событие уже несёт снимок MDC вызывающего потока.
 *
 * <p>Вне HTTP-запроса (шедулеры, Telegram-поллинг) ключи пусты — соответствующие
 * колонки в {@code error_logs} будут {@code NULL}. Это ожидаемо, а не дефект.
 */
public final class ErrorLogMdc {

    /** {@code users.id} текущего пользователя, строкой. */
    public static final String USER_ID = "userId";

    /** URI текущего запроса ({@code /api/v1/plants}). Query-строка не пишется. */
    public static final String REQUEST_PATH = "requestPath";

    /** Сквозной id запроса: заголовок {@code X-Correlation-Id} либо сгенерированный UUID. */
    public static final String CORRELATION_ID = "correlationId";

    private ErrorLogMdc() {
    }
}
