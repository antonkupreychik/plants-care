package com.plantcare.core.errorlog;

/**
 * Ключ группировки «одинаковых» ошибок для топ-10 на {@code /admin/errors} (issue #97).
 *
 * <p>Issue формулирует группировку как «по first line of stack trace». Буквальная первая
 * строка ({@code java.lang.IllegalStateException: plant 42 not found}) содержит текст
 * сообщения с изменчивыми id — одинаковые по сути ошибки не схлопнулись бы. Поэтому
 * fingerprint собирается из двух стабильных частей первой строки стека:
 *
 * <pre>{@code <FQCN исключения> at <первый кадр стека>}</pre>
 *
 * <p>Хеш не используется намеренно: fingerprint читается глазами прямо в таблице и в
 * админке, а коллизий на объёмах пет-проекта не бывает.
 *
 * <p>События без throwable (обычный {@code log.warn("...")}) группируются по
 * {@code <logger> | <сообщение>} — там стека нет, а сообщение обычно параметризовано
 * шаблоном и достаточно стабильно.
 */
public final class ErrorFingerprint {

    /** Ограничение колонки {@code error_logs.fingerprint}. */
    public static final int MAX_LENGTH = 512;

    private static final String NO_STACK = "<no-stack>";

    private ErrorFingerprint() {
    }

    /**
     * Собирает fingerprint.
     *
     * @param exceptionClass FQCN исключения либо {@code null}, если throwable не было
     * @param firstFrame     первый кадр стека ({@code com.plantcare.x.Y.z(Y.java:42)})
     *                       либо {@code null}
     * @param loggerName     имя логгера — fallback, когда стека нет
     * @param message        сообщение — fallback, когда стека нет
     */
    public static String of(String exceptionClass, String firstFrame, String loggerName, String message) {
        String raw;
        if (exceptionClass != null && !exceptionClass.isBlank()) {
            raw = exceptionClass + " at " + (firstFrame == null || firstFrame.isBlank() ? NO_STACK : firstFrame);
        } else {
            raw = safe(loggerName) + " | " + safe(message);
        }
        return truncate(raw.replace('\n', ' ').replace('\r', ' ').trim(), MAX_LENGTH);
    }

    private static String safe(String s) {
        return s == null || s.isBlank() ? "unknown" : s;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
