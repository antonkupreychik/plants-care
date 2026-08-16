package com.plantcare.core.errorlog;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

/**
 * Вешает {@link ErrorLogDbAppender} на root-логгер и снимает его при остановке
 * контекста (issue #97).
 *
 * <p>Снятие в {@link #destroy()} обязательно: root-логгер Logback — синглтон уровня JVM,
 * а Spring-контекстов за один прогон тестов поднимается много. Без detach остались бы
 * аппендеры мёртвых контекстов, пишущие в закрытый DataSource.
 *
 * <p>Если {@code plants.error-log.enabled=false} — аппендер не вешается вовсе.
 */
@Slf4j
@Component
public class ErrorLogAppenderRegistrar implements InitializingBean, DisposableBean {

    private final ErrorLogRecorder recorder;
    private final ErrorLogProperties properties;

    private ErrorLogDbAppender appender;

    public ErrorLogAppenderRegistrar(ErrorLogRecorder recorder, ErrorLogProperties properties) {
        this.recorder = recorder;
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        if (!Boolean.TRUE.equals(properties.enabled())) {
            log.info("Error log DB appender is disabled (plants.error-log.enabled=false)");
            return;
        }
        Logger root = rootLogger();
        if (root == null) {
            log.warn("SLF4J binding is not Logback — error log DB appender not installed");
            return;
        }
        appender = new ErrorLogDbAppender(recorder, properties);
        appender.setContext(root.getLoggerContext());
        appender.start();
        root.addAppender(appender);
        log.info("Error log DB appender installed (queueCapacity={}, retentionDays={})",
                properties.queueCapacity(), properties.retentionDays());
    }

    @Override
    public void destroy() {
        if (appender == null) {
            return;
        }
        Logger root = rootLogger();
        if (root != null) {
            root.detachAppender(appender);
        }
        appender.stop();
        appender = null;
    }

    private static Logger rootLogger() {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (!(factory instanceof LoggerContext context)) {
            return null;
        }
        return context.getLogger(Logger.ROOT_LOGGER_NAME);
    }
}
