package com.plantcare.core.errorlog;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Юнит-тесты аппендера (issue #97): что именно снимается с логового события.
 * Рекордер замокан — здесь проверяется только преобразование, без БД.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ErrorLogDbAppender — перехват WARN/ERROR из логов (#97)")
class ErrorLogDbAppenderTest {

    @Mock
    private ErrorLogRecorder recorder;

    private ErrorLogDbAppender appender;
    private LoggerContext loggerContext;

    @BeforeEach
    void setUp() {
        appender = new ErrorLogDbAppender(recorder, ErrorLogProperties.defaults());
        loggerContext = new LoggerContext();
        appender.setContext(loggerContext);
        appender.start();
    }

    @Test
    @DisplayName("should_capture_event_with_mdc_context_when_level_is_error")
    void should_capture_event_with_mdc_context_when_level_is_error() {
        LoggingEvent event = event(Level.ERROR, "com.plantcare.api.Handler", "Unhandled API error",
                new IllegalStateException("boom"),
                Map.of(ErrorLogMdc.USER_ID, "42",
                        ErrorLogMdc.REQUEST_PATH, "/api/v1/plants",
                        ErrorLogMdc.CORRELATION_ID, "corr-1"));

        appender.doAppend(event);

        ArgumentCaptor<ErrorLogEntry> captor = ArgumentCaptor.forClass(ErrorLogEntry.class);
        verify(recorder).record(captor.capture());
        ErrorLogEntry entry = captor.getValue();

        assertThat(entry.level()).isEqualTo("ERROR");
        assertThat(entry.loggerName()).isEqualTo("com.plantcare.api.Handler");
        assertThat(entry.message()).isEqualTo("Unhandled API error");
        assertThat(entry.exceptionClass()).isEqualTo("java.lang.IllegalStateException");
        assertThat(entry.userId()).isEqualTo(42L);
        assertThat(entry.requestPath()).isEqualTo("/api/v1/plants");
        assertThat(entry.correlationId()).isEqualTo("corr-1");
        assertThat(entry.stackTrace()).contains("java.lang.IllegalStateException: boom");
        assertThat(entry.fingerprint()).startsWith("java.lang.IllegalStateException at ");
    }

    @Test
    @DisplayName("should_skip_event_when_level_is_below_warn")
    void should_skip_event_when_level_is_below_warn() {
        appender.doAppend(event(Level.INFO, "com.plantcare.bot.Svc", "just info", null, Map.of()));

        verify(recorder, never()).record(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("should_capture_event_when_level_is_warn")
    void should_capture_event_when_level_is_warn() {
        appender.doAppend(event(Level.WARN, "com.plantcare.bot.Svc", "slow", null, Map.of()));

        verify(recorder).record(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("should_skip_event_when_it_comes_from_error_log_itself")
    void should_skip_event_when_it_comes_from_error_log_itself() {
        appender.doAppend(event(Level.ERROR, "com.plantcare.core.errorlog.ErrorLogRecorder",
                "flush failed", null, Map.of()));

        verify(recorder, never()).record(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("should_leave_user_id_null_when_mdc_holds_non_numeric_principal")
    void should_leave_user_id_null_when_mdc_holds_non_numeric_principal() {
        appender.doAppend(event(Level.ERROR, "com.plantcare.admin.X", "oops", null,
                Map.of(ErrorLogMdc.USER_ID, "admin")));

        ArgumentCaptor<ErrorLogEntry> captor = ArgumentCaptor.forClass(ErrorLogEntry.class);
        verify(recorder).record(captor.capture());
        assertThat(captor.getValue().userId()).isNull();
    }

    @Test
    @DisplayName("should_render_causes_when_exception_is_nested")
    void should_render_causes_when_exception_is_nested() {
        Exception nested = new IllegalArgumentException("root cause");
        Exception outer = new RuntimeException("wrapper", nested);

        appender.doAppend(event(Level.ERROR, "com.plantcare.bot.Svc", "failed", outer, Map.of()));

        ArgumentCaptor<ErrorLogEntry> captor = ArgumentCaptor.forClass(ErrorLogEntry.class);
        verify(recorder).record(captor.capture());
        assertThat(captor.getValue().stackTrace())
                .contains("java.lang.RuntimeException: wrapper")
                .contains("Caused by: java.lang.IllegalArgumentException: root cause");
    }

    @Test
    @DisplayName("should_truncate_message_when_it_exceeds_limit")
    void should_truncate_message_when_it_exceeds_limit() {
        ErrorLogProperties tight = new ErrorLogProperties(null, null, null, null, null, 100, null);
        ErrorLogDbAppender tightAppender = new ErrorLogDbAppender(recorder, tight);
        tightAppender.setContext(loggerContext);
        tightAppender.start();

        tightAppender.doAppend(event(Level.ERROR, "l", "x".repeat(500), null, Map.of()));

        ArgumentCaptor<ErrorLogEntry> captor = ArgumentCaptor.forClass(ErrorLogEntry.class);
        verify(recorder).record(captor.capture());
        assertThat(captor.getValue().message()).hasSize(100);
    }

    @Test
    @DisplayName("should_leave_context_null_when_mdc_is_empty")
    void should_leave_context_null_when_mdc_is_empty() {
        appender.doAppend(event(Level.ERROR, "com.plantcare.bot.Scheduler", "cron failed",
                new RuntimeException("x"), Map.of()));

        ArgumentCaptor<ErrorLogEntry> captor = ArgumentCaptor.forClass(ErrorLogEntry.class);
        verify(recorder).record(captor.capture());
        ErrorLogEntry entry = captor.getValue();

        assertThat(entry.userId()).isNull();
        assertThat(entry.requestPath()).isNull();
        assertThat(entry.correlationId()).isNull();
        assertThat(entry.threadName()).isNotBlank();
    }

    private LoggingEvent event(Level level, String logger, String message,
                               Throwable throwable, Map<String, String> mdc) {
        LoggingEvent event = new LoggingEvent();
        event.setLoggerContext(loggerContext);
        event.setLevel(level);
        event.setLoggerName(logger);
        event.setMessage(message);
        event.setThreadName(Thread.currentThread().getName());
        event.setTimeStamp(System.currentTimeMillis());
        event.setMDCPropertyMap(mdc);
        if (throwable != null) {
            event.setThrowableProxy(new ThrowableProxy(throwable));
        }
        return event;
    }
}
