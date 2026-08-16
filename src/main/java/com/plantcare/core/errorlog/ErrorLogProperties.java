package com.plantcare.core.errorlog;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Настройки журнала ошибок (issue #97), префикс {@code plants.error-log}.
 *
 * <p>Дефолты подобраны под объёмы пет-проекта: очередь на 2000 событий переживает
 * короткий всплеск, флаш раз в секунду батчем до 200 строк.
 *
 * @param enabled             включён ли перехват логов в БД; {@code false} снимает
 *                            аппендер с root-логгера
 * @param queueCapacity       ёмкость очереди перед батч-инсертом. При переполнении события
 *                            ОТБРАСЫВАЮТСЯ, а не блокируют вызывающий поток — журнал ошибок
 *                            не имеет права тормозить API
 * @param batchSize           максимум строк в одном батч-инсерте
 * @param flushInterval       период фонового флаша очереди в БД
 * @param retentionDays       сколько дней храним записи; старее удаляет
 *                            {@link ErrorLogRetentionScheduler}
 * @param maxMessageLength    лимит длины сообщения (символов), хвост обрезается
 * @param maxStackTraceLength лимит длины стек-трейса (символов), хвост обрезается
 */
@ConfigurationProperties(prefix = "plants.error-log")
public record ErrorLogProperties(
        Boolean enabled,
        Integer queueCapacity,
        Integer batchSize,
        Duration flushInterval,
        Integer retentionDays,
        Integer maxMessageLength,
        Integer maxStackTraceLength
) {

    public ErrorLogProperties {
        if (enabled == null) enabled = Boolean.TRUE;
        if (queueCapacity == null || queueCapacity <= 0) queueCapacity = 2000;
        if (batchSize == null || batchSize <= 0) batchSize = 200;
        if (flushInterval == null || flushInterval.isZero() || flushInterval.isNegative()) {
            flushInterval = Duration.ofSeconds(1);
        }
        if (retentionDays == null || retentionDays <= 0) retentionDays = 30;
        if (maxMessageLength == null || maxMessageLength < 100) maxMessageLength = 4000;
        if (maxStackTraceLength == null || maxStackTraceLength < 500) maxStackTraceLength = 20_000;
    }

    /** Дефолтный набор — удобен в тестах и как fallback. */
    public static ErrorLogProperties defaults() {
        return new ErrorLogProperties(null, null, null, null, null, null, null);
    }
}
