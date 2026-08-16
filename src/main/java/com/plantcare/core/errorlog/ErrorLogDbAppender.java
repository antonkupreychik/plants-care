package com.plantcare.core.errorlog;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.UnsynchronizedAppenderBase;

import java.time.Instant;
import java.util.Map;

/**
 * Logback-аппендер, складывающий события уровня WARN и выше в журнал ошибок (issue #97).
 *
 * <p>Прикручивается к root-логгеру программно ({@link ErrorLogAppenderRegistrar}), а не
 * через {@code logback-spring.xml}: XML-конфига в проекте нет вообще, а аппендеру нужен
 * Spring-бин {@link ErrorLogRecorder} — из XML его не достать без статических костылей.
 *
 * <p>Метод {@link #append} выполняется в потоке, который логировал, поэтому он делает
 * минимум: снимает поля с события и отдаёт их в неблокирующую очередь. Вся работа с БД —
 * в фоновом потоке рекордера.
 *
 * <p>Что НЕ пишется намеренно (открытый вопрос issue про privacy): тело запроса,
 * параметры, заголовки и cookies. В журнал попадают только сообщение, стек, id юзера,
 * путь и correlation id — этого хватает для воспроизведения, а сырой ввод пользователя
 * в админку не утекает.
 */
public class ErrorLogDbAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    /**
     * Логгеры, чьи события в журнал не берём: это сам путь записи журнала. Даже с
     * ThreadLocal-защитой рекордера так надёжнее — исключает петлю на любом потоке.
     */
    private static final String OWN_PACKAGE = "com.plantcare.core.errorlog";

    private final ErrorLogRecorder recorder;
    private final ErrorLogProperties properties;

    public ErrorLogDbAppender(ErrorLogRecorder recorder, ErrorLogProperties properties) {
        this.recorder = recorder;
        this.properties = properties;
        setName("errorLogDbAppender");
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (!isCapturable(event)) {
            return;
        }
        try {
            recorder.record(toEntry(event));
        } catch (Exception e) {
            // Аппендер не имеет права ронять логирование. addError пишет в logback
            // status manager, а не в лог — петли не будет.
            addError("Failed to capture error log event", e);
        }
    }

    private boolean isCapturable(ILoggingEvent event) {
        return event != null
                && event.getLevel() != null
                && event.getLevel().isGreaterOrEqual(Level.WARN)
                && !event.getLoggerName().startsWith(OWN_PACKAGE)
                && !ErrorLogRecorder.isInsideFlush();
    }

    /** Преобразование логового события в запись журнала. Package-private ради тестов. */
    ErrorLogEntry toEntry(ILoggingEvent event) {
        Map<String, String> mdc = event.getMDCPropertyMap();
        IThrowableProxy throwable = event.getThrowableProxy();

        String exceptionClass = throwable == null ? null : truncate(throwable.getClassName(), 255);
        String firstFrame = firstFrame(throwable);

        return new ErrorLogEntry(
                Instant.ofEpochMilli(event.getTimeStamp()),
                event.getLevel().toString(),
                truncate(event.getLoggerName(), 255),
                truncate(nullToEmpty(event.getFormattedMessage()), properties.maxMessageLength()),
                exceptionClass,
                ErrorFingerprint.of(exceptionClass, firstFrame, event.getLoggerName(),
                        event.getFormattedMessage()),
                renderStackTrace(throwable),
                parseUserId(mdc.get(ErrorLogMdc.USER_ID)),
                truncate(mdc.get(ErrorLogMdc.REQUEST_PATH), 512),
                truncate(mdc.get(ErrorLogMdc.CORRELATION_ID), 64),
                truncate(event.getThreadName(), 128)
        );
    }

    private static String firstFrame(IThrowableProxy throwable) {
        if (throwable == null) {
            return null;
        }
        StackTraceElementProxy[] frames = throwable.getStackTraceElementProxyArray();
        if (frames == null || frames.length == 0) {
            return null;
        }
        return frames[0].getStackTraceElement().toString();
    }

    /**
     * Разворачивает исключение вместе со всей цепочкой {@code Caused by} — без причины
     * стек чаще всего бесполезен.
     */
    private String renderStackTrace(IThrowableProxy throwable) {
        if (throwable == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        IThrowableProxy current = throwable;
        int depth = 0;
        while (current != null && depth < 10 && sb.length() < properties.maxStackTraceLength()) {
            if (depth > 0) {
                sb.append("Caused by: ");
            }
            sb.append(current.getClassName()).append(": ").append(nullToEmpty(current.getMessage())).append('\n');
            StackTraceElementProxy[] frames = current.getStackTraceElementProxyArray();
            if (frames != null) {
                for (StackTraceElementProxy frame : frames) {
                    if (sb.length() >= properties.maxStackTraceLength()) {
                        break;
                    }
                    sb.append("\tat ").append(frame.getStackTraceElement()).append('\n');
                }
            }
            current = current.getCause();
            depth++;
        }
        return truncate(sb.toString(), properties.maxStackTraceLength());
    }

    private static Long parseUserId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            // В MDC мог оказаться не числовой principal (например, admin-логин).
            // Для журнала это просто «юзера нет».
            return null;
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
