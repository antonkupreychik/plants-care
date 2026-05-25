package com.plantcare.bot.telegram;

import com.plantcare.bot.client.TelegramClientProvider;
import com.plantcare.bot.config.TelegramRateLimitProperties;
import com.plantcare.bot.metrics.MetricsService;
import com.plantcare.bot.metrics.MetricsService.TelegramErrorCode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Медиатор отправки сообщений Telegram с rate-limiting (issue #29).
 *
 * <p>Используется только массовыми путями (шедулеры). Интерактивные хендлеры
 * команд/колбэков, а также {@code AdminBroadcastService} (со своим троттлингом
 * и синхронными результатами) сюда НЕ переводятся.
 *
 * <p>Модель: {@link #enqueue} кладёт сообщение в ограниченную очередь и сразу
 * возвращает управление. Один выделенный daemon-поток дренирует очередь со
 * скоростью ≤ {@code permitsPerSecond} (через {@link TokenBucketRateLimiter}),
 * а на 429 ждёт {@code retry-after} и повторяет до {@code maxRetries} раз.
 *
 * <p>Колбэки {@link SendCallbacks} выполняются НА ВОРКЕР-ПОТОКЕ после резолва
 * отправки — вне транзакции вызывающего шедулера. См. doc к {@link SendCallbacks}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RateLimitedTelegramSender {

    /**
     * Парсер retry-after из текста {@code TelegramApiException}. Совпадает с
     * подходом {@code AdminBroadcastService.extractRetryAfter}: библиотека
     * форматирует ошибку строкой, тянем число после «retry after».
     */
    private static final Pattern RETRY_AFTER_PATTERN =
            Pattern.compile("retry\\s*after\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    private final TelegramClientProvider telegramClientProvider;
    private final TokenBucketRateLimiter rateLimiter;
    private final Sleeper sleeper;
    private final TelegramRateLimitProperties properties;
    private final MetricsService metricsService;

    private LinkedBlockingQueue<QueuedMessage> queue;
    private Thread worker;
    private volatile boolean running;

    @PostConstruct
    void start() {
        this.queue = new LinkedBlockingQueue<>(Math.max(1, properties.getQueueCapacity()));
        this.running = true;
        this.worker = new Thread(this::drainLoop, "tg-send-queue");
        this.worker.setDaemon(true);
        this.worker.start();
        log.info("Telegram send queue started: permitsPerSecond={}, queueCapacity={}, maxRetries={}",
                properties.getPermitsPerSecond(), properties.getQueueCapacity(), properties.getMaxRetries());
    }

    @PreDestroy
    void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
        log.info("Telegram send queue stopping");
    }

    /** Fire-and-forget: ошибки только логируются + метрика, без колбэков. */
    public void enqueue(SendMessage message) {
        enqueue(message, SendCallbacks.none());
    }

    /**
     * Поставить сообщение в очередь с колбэками. Колбэки выполнятся на воркер-потоке
     * после резолва отправки. При переполнении очереди сообщение дропается с warn.
     */
    public void enqueue(SendMessage message, SendCallbacks callbacks) {
        SendCallbacks effective = callbacks != null ? callbacks : SendCallbacks.none();
        boolean accepted = queue.offer(new QueuedMessage(message, effective));
        if (!accepted) {
            log.warn("Telegram send queue is full (capacity={}), dropping message to chat {}",
                    properties.getQueueCapacity(), message.getChatId());
            metricsService.recordNotificationFailed(
                    MetricsService.CHANNEL_TELEGRAM, MetricsService.FailureReason.OTHER);
        }
    }

    private void drainLoop() {
        while (running) {
            QueuedMessage queued;
            try {
                queued = queue.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (queued == null) {
                continue;
            }
            try {
                long waitMillis = rateLimiter.reserveNextPermitMillis();
                if (waitMillis > 0) {
                    sleeper.sleep(waitMillis);
                }
                sendWithRetry(queued.message(), queued.callbacks());
            } catch (InterruptedException e) {
                // Прерывание = graceful shutdown: восстанавливаем флаг и выходим.
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                // Любая иная ошибка (rate limiter, send, или колбэк с DB-транзакцией:
                // DataAccessException / FK-нарушение из-за удалённой сущности и т.п.)
                // НЕ должна убить единственный воркер-поток — иначе вся очередь
                // навсегда встаёт. Логируем, метрика, идём к следующему сообщению.
                log.error("send-queue worker swallowed error for chatId={}",
                        queued.message().getChatId(), t);
                metricsService.recordNotificationFailed(
                        MetricsService.CHANNEL_TELEGRAM, MetricsService.FailureReason.OTHER);
            }
        }
        log.info("Telegram send queue worker exited (drained={} remaining)", queue.size());
    }

    /**
     * Отправка с обработкой 429. Package-private, чтобы юнит-тест мог дёргать
     * напрямую с замоканным {@link TelegramClientProvider} и стаб-{@link Sleeper}.
     */
    void sendWithRetry(SendMessage message, SendCallbacks callbacks) throws InterruptedException {
        int maxRetries = Math.max(0, properties.getMaxRetries());
        int attempt = 0;
        while (true) {
            try {
                telegramClientProvider.getTelegramClient().execute(message);
                callbacks.runSuccess();
                return;
            } catch (TelegramApiException e) {
                String msg = e.getMessage();
                boolean rateLimited = TelegramErrorCode.fromMessage(msg) == TelegramErrorCode.RATE_LIMITED;
                if (rateLimited && attempt < maxRetries) {
                    attempt++;
                    long retryAfter = extractRetryAfterSeconds(msg);
                    log.warn("Rate-limited (429) sending to chat {}: retry {}/{} after {}s",
                            message.getChatId(), attempt, maxRetries, retryAfter);
                    metricsService.recordTelegramApiError(TelegramErrorCode.RATE_LIMITED);
                    sleeper.sleep(retryAfter * 1000L);
                    continue;
                }
                // Либо неретраябельная ошибка (403/400/...), либо исчерпали ретраи 429.
                metricsService.recordTelegramApiError(TelegramErrorCode.fromMessage(msg));
                log.warn("Send failed for chat {} (attempts={}, rateLimited={}): {}",
                        message.getChatId(), attempt + 1, rateLimited, msg);
                callbacks.runFailure(e);
                return;
            }
        }
    }

    /**
     * Достаёт {@code retry_after} из текста исключения. Если число не нашлось —
     * fallback {@code defaultRetryAfterSeconds} из конфигурации.
     */
    private long extractRetryAfterSeconds(String errorMessage) {
        if (errorMessage != null) {
            Matcher m = RETRY_AFTER_PATTERN.matcher(errorMessage);
            if (m.find()) {
                try {
                    long parsed = Long.parseLong(m.group(1));
                    // Капаем, чтобы один 429 с огромным retry-after не усыпил
                    // единственный воркер на часы и не застопорил всю очередь.
                    return Math.min(parsed, properties.getMaxRetryAfterSeconds());
                } catch (NumberFormatException ignored) {
                    // упадём в fallback ниже
                }
            }
        }
        return Math.max(0, properties.getDefaultRetryAfterSeconds());
    }

    /** Элемент очереди: сообщение + его колбэки. */
    private record QueuedMessage(SendMessage message, SendCallbacks callbacks) {
    }
}
