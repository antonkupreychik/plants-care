package com.plantcare.bot.metrics;

import com.plantcare.bot.domain.enums.TaskType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Единая точка регистрации и инкремента бизнес-метрик (issue #115).
 *
 * <p>Все вызовы {@link MeterRegistry} проходят через этот компонент, чтобы:
 * <ul>
 *   <li>не размазывать имена/тэги метрик по 30 классам;</li>
 *   <li>дать единый «контракт» метрик — переименование/удаление видно сразу
 *       по списку методов класса;</li>
 *   <li>обеспечить кеширование {@link Counter}-инстансов: каждое обращение
 *       к {@code registry.counter(name, tags)} с одинаковыми параметрами вернёт
 *       тот же мьютер, но это всё равно лишний lookup в hot path шедулера.</li>
 * </ul>
 *
 * <p>Имена метрик — точечная нотация. Prometheus-registry автоматически
 * сконвертирует {@code notifications.sent} → {@code notifications_sent_total}.
 *
 * <p>Тэги — низкокардинальные значения (enum, фиксированные строки). Никаких
 * {@code userId}, {@code plantId} — взорвёт cardinality в Prometheus.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetricsService {

    // ---- Имена метрик (используются также в тестах) -----------------------
    public static final String NOTIFICATIONS_SENT          = "notifications.sent";
    public static final String NOTIFICATIONS_FAILED        = "notifications.failed";
    public static final String NOTIFICATIONS_DIGEST_SENT   = "notifications.digest.sent";
    public static final String TELEGRAM_API_ERRORS         = "telegram.api.errors";
    public static final String CALLBACKS_PROCESSED         = "callbacks.processed";
    public static final String USERS_REGISTERED_TOTAL      = "users.registered.total";
    public static final String USERS_ACTIVE_DAU            = "users.active.dau";
    public static final String SCHEDULER_TICK_DURATION     = "scheduler.tick.duration";

    public static final String CHANNEL_TELEGRAM = "telegram";

    /**
     * Whitelist разрешённых значений тэга {@code action} для метрики
     * {@link #CALLBACKS_PROCESSED}.
     *
     * <p>Тэг {@code action} приходит из {@code callback_data} Telegram-апдейта,
     * то есть формально подконтролен пользователю. Без валидации любой клиент
     * (включая злонамеренного) мог бы слать произвольные строки и взорвать
     * cardinality MeterRegistry — каждый уникальный {@code action} создаёт
     * отдельный counter в памяти JVM навсегда. На Railway это даёт OOM за
     * сутки атаки.
     *
     * <p>Содержит ровно те action'ы, которые реально использует
     * {@link #recordCallback(String, CallbackOutcome)}. При добавлении нового
     * action в хендлерах — обязательно добавить его сюда, иначе будет
     * подменяться на {@value #UNKNOWN_ACTION_TAG}.
     */
    static final Set<String> KNOWN_CALLBACK_ACTIONS = Set.of(
            // одиночные напоминания (NotificationCallbackService)
            "done", "snooze", "skip", "unknown",
            // массовый полив локации (#19)
            "bulk_done",
            // двухшаговый flow «полив с деталями» (#71)
            "wabund", "wsoil",
            // SOIL_CHECK (#74)
            "soil_dry", "soil_wet", "soil_unk", "soil_water",
            // акклиматизация (#75)
            "accl_soil", "accl_snooze", "accl_checkin",
            // дайджест (#50)
            "digest_done_all", "digest_expand", "digest_unknown"
    );

    /** Значение тэга {@code action}, если переданная строка не в whitelist'е. */
    static final String UNKNOWN_ACTION_TAG = "unknown";

    private final MeterRegistry meterRegistry;

    /**
     * DAU обновляется отдельным шедулером (см. {@code DauMetricsUpdater})
     * раз в час и складывается в этот {@link AtomicLong}. Gauge читает
     * текущее значение — без блокировок, без обращения к БД из callback'а
     * Micrometer scrape.
     */
    private final AtomicLong activeDau = new AtomicLong(0L);

    /**
     * Регистрируем gauge один раз при старте. Counter'ы регистрируются
     * лениво — на первом вызове соответствующего {@code recordXxx}.
     */
    @PostConstruct
    void registerGauges() {
        Gauge.builder(USERS_ACTIVE_DAU, activeDau, AtomicLong::doubleValue)
                .description("Unique users with at least one care event in the last 24h")
                .register(meterRegistry);
        log.info("Business metrics registered (DAU gauge='{}')", USERS_ACTIVE_DAU);
    }

    // ====================================================================
    // Notifications
    // ====================================================================

    /**
     * Инкрементировать счётчик успешно отправленных одиночных уведомлений.
     *
     * @param channel  канал доставки (сейчас всегда {@link #CHANNEL_TELEGRAM})
     * @param taskType тип задачи (нужен для срезов «сколько поливных пушей в день»)
     */
    public void recordNotificationSent(String channel, TaskType taskType) {
        meterRegistry.counter(
                NOTIFICATIONS_SENT,
                "channel", channel,
                "task_type", taskType.name()
        ).increment();
    }

    /**
     * Инкрементировать счётчик неудачных отправок уведомлений.
     *
     * @param channel канал доставки
     * @param reason  причина: {@code rate_limit | api_error | blocked | other}
     */
    public void recordNotificationFailed(String channel, FailureReason reason) {
        meterRegistry.counter(
                NOTIFICATIONS_FAILED,
                "channel", channel,
                "reason", reason.tagValue()
        ).increment();
    }

    /**
     * Инкрементировать счётчик отправленных дайджестов (issue #50 —
     * группированные пуши по нескольким задачам).
     */
    public void recordDigestSent() {
        meterRegistry.counter(NOTIFICATIONS_DIGEST_SENT).increment();
    }

    // ====================================================================
    // Telegram API errors
    // ====================================================================

    /**
     * Инкрементировать счётчик ошибок Telegram Bot API.
     *
     * @param code HTTP-код ответа Telegram ({@code 429 | 400 | 403 | other})
     */
    public void recordTelegramApiError(TelegramErrorCode code) {
        meterRegistry.counter(TELEGRAM_API_ERRORS, "code", code.tagValue()).increment();
    }

    // ====================================================================
    // Callbacks
    // ====================================================================

    /**
     * Инкрементировать счётчик обработанных callback-кнопок.
     *
     * <p>{@code action} приходит из {@code callback_data} Telegram-апдейта и
     * валидируется по {@link #KNOWN_CALLBACK_ACTIONS}. Любое значение вне
     * whitelist'а подменяется на {@value #UNKNOWN_ACTION_TAG}, чтобы
     * злонамеренный клиент не мог взорвать cardinality реестра уникальными
     * строками (см. doc к {@link #KNOWN_CALLBACK_ACTIONS}).
     *
     * @param action  название действия (done, snooze, skip, digest_done_all, ...)
     * @param outcome исход обработки ({@code ok | error | idempotent})
     */
    public void recordCallback(String action, CallbackOutcome outcome) {
        String safeAction = sanitizeCallbackAction(action);
        meterRegistry.counter(
                CALLBACKS_PROCESSED,
                "action", safeAction,
                "outcome", outcome.tagValue()
        ).increment();
    }

    private String sanitizeCallbackAction(String action) {
        if (action != null && KNOWN_CALLBACK_ACTIONS.contains(action)) {
            return action;
        }
        // debug — чтобы не дать атакующему флудить нам логи infо/warn.
        log.debug("Unknown callback action '{}', counted as '{}'", action, UNKNOWN_ACTION_TAG);
        return UNKNOWN_ACTION_TAG;
    }

    // ====================================================================
    // Users
    // ====================================================================

    /**
     * Инкрементировать счётчик зарегистрированных пользователей.
     * Вызывается ровно один раз на одно реальное создание {@code User} в БД.
     */
    public void recordUserRegistered() {
        meterRegistry.counter(USERS_REGISTERED_TOTAL).increment();
    }

    /**
     * Обновить текущее значение DAU. Вызывается из {@code DauMetricsUpdater}
     * раз в час; Micrometer-gauge читает {@link #activeDau} лениво при scrape.
     */
    public void updateActiveDau(long value) {
        activeDau.set(value);
    }

    /** Только для тестов / health-проверок. */
    public long getActiveDau() {
        return activeDau.get();
    }

    // ====================================================================
    // Scheduler
    // ====================================================================

    /**
     * Запустить замер длительности шедулерного тика. Использование:
     * <pre>{@code
     *   Timer.Sample sample = metricsService.startSchedulerTickTimer();
     *   try {
     *       executeTick();
     *   } finally {
     *       metricsService.stopSchedulerTickTimer(sample);
     *   }
     * }</pre>
     */
    public Timer.Sample startSchedulerTickTimer() {
        return Timer.start(meterRegistry);
    }

    /** Остановить таймер и записать длительность в {@code scheduler.tick.duration}. */
    public void stopSchedulerTickTimer(Timer.Sample sample) {
        sample.stop(timer(SCHEDULER_TICK_DURATION));
    }

    private Timer timer(String name) {
        return Timer.builder(name)
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    // ====================================================================
    // Tag values
    // ====================================================================

    /** Причина неудачи отправки уведомления (тэг {@code reason}). */
    public enum FailureReason {
        RATE_LIMIT("rate_limit"),
        API_ERROR("api_error"),
        BLOCKED("blocked"),
        OTHER("other");

        private final String tagValue;

        FailureReason(String tagValue) {
            this.tagValue = tagValue;
        }

        public String tagValue() {
            return tagValue;
        }
    }

    /** Код ответа Telegram API (тэг {@code code}). */
    public enum TelegramErrorCode {
        RATE_LIMITED("429"),
        BAD_REQUEST("400"),
        FORBIDDEN("403"),
        OTHER("other");

        private final String tagValue;

        TelegramErrorCode(String tagValue) {
            this.tagValue = tagValue;
        }

        public String tagValue() {
            return tagValue;
        }

        /**
         * Эвристически распарсить код из текста {@code TelegramApiException}.
         * Telegram Bot API возвращает текст вида {@code "[400] Bad Request: ..."},
         * либо для {@code TelegramApiRequestException} — числовое поле, но мы
         * парсим только строку, чтобы не зависеть от типа исключения.
         */
        public static TelegramErrorCode fromMessage(String message) {
            if (message == null) {
                return OTHER;
            }
            if (message.contains("429")) {
                return RATE_LIMITED;
            }
            if (message.contains("403")) {
                return FORBIDDEN;
            }
            if (message.contains("400")) {
                return BAD_REQUEST;
            }
            return OTHER;
        }
    }

    /** Исход обработки callback'а (тэг {@code outcome}). */
    public enum CallbackOutcome {
        OK("ok"),
        ERROR("error"),
        IDEMPOTENT("idempotent");

        private final String tagValue;

        CallbackOutcome(String tagValue) {
            this.tagValue = tagValue;
        }

        public String tagValue() {
            return tagValue;
        }
    }
}
