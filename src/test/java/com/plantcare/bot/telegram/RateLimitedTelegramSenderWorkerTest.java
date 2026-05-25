package com.plantcare.bot.telegram;

import com.plantcare.bot.client.TelegramClientProvider;
import com.plantcare.bot.config.TelegramRateLimitProperties;
import com.plantcare.bot.metrics.MetricsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Тесты РЕАЛЬНОГО воркер-потока очереди {@link RateLimitedTelegramSender} (issue #29).
 *
 * <p>В отличие от {@link RateLimitedTelegramSenderTest}, который дёргает
 * {@code sendWithRetry} синхронно, здесь поднимается настоящий daemon-поток через
 * {@code @PostConstruct start()} (вызываем вручную — Spring-контекста нет), и
 * сообщения проходят весь путь {@code enqueue → drainLoop → sendWithRetry → callbacks}.
 *
 * <p>Ключевой регрессионный кейс: исключение из колбэка НЕ должно убивать
 * единственный воркер-поток (иначе очередь встаёт навсегда). Воркер обязан
 * изолировать сбой и продолжить дренаж.
 *
 * <p>Ожидания синхронизируются через {@link CountDownLatch} (Awaitility в проекте
 * не подключён) — без unbounded {@code Thread.sleep}. {@link Sleeper} застаблен,
 * rate limiter не тормозит (0 мс ожидания).
 */
@DisplayName("RateLimitedTelegramSender — воркер очереди и изоляция сбоев (issue #29)")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RateLimitedTelegramSenderWorkerTest {

    private static final long AWAIT_TIMEOUT_SECONDS = 5L;

    @Mock
    private TelegramClientProvider telegramClientProvider;

    @Mock
    private TelegramClient telegramClient;

    @Mock
    private TokenBucketRateLimiter rateLimiter;

    @Mock
    private MetricsService metricsService;

    private RateLimitedTelegramSender sender;

    @AfterEach
    void tearDown() {
        if (sender != null) {
            sender.stop();
        }
    }

    private void startSender(int queueCapacity, Sleeper sleeper) {
        startSender(queueCapacity, sleeper, 0L);
    }

    private void startSender(int queueCapacity, Sleeper sleeper, long reservedMillis) {
        TelegramRateLimitProperties properties = new TelegramRateLimitProperties(
                1000, queueCapacity, 3, 2L, 60L);
        sender = new RateLimitedTelegramSender(
                telegramClientProvider, rateLimiter, sleeper, properties, metricsService);
        when(telegramClientProvider.getTelegramClient()).thenReturn(telegramClient);
        when(rateLimiter.reserveNextPermitMillis()).thenReturn(reservedMillis);
        sender.start();
    }

    @Test
    @DisplayName("should_execute_message_on_worker_thread_when_enqueued")
    void should_execute_message_on_worker_thread_when_enqueued() throws Exception {
        // arrange
        CountDownLatch executed = new CountDownLatch(1);
        doAnswer(inv -> {
            executed.countDown();
            return null;
        }).when(telegramClient).execute(any(SendMessage.class));
        startSender(100, noOpSleeper());

        // act
        sender.enqueue(message("100"));

        // assert
        assertThat(executed.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("worker thread should have executed the enqueued message")
                .isTrue();
    }

    @Test
    @DisplayName("should_keep_draining_when_a_success_callback_throws")
    void should_keep_draining_when_a_success_callback_throws() throws Exception {
        // arrange: A and B both succeed at the Telegram layer; A's onSuccess throws.
        // The worker must NOT die after A — B must still be delivered and its onSuccess run.
        List<String> executedChatIds = new CopyOnWriteArrayList<>();
        doAnswer(inv -> {
            SendMessage sent = inv.getArgument(0);
            executedChatIds.add(sent.getChatId());
            return null;
        }).when(telegramClient).execute(any(SendMessage.class));
        startSender(100, noOpSleeper());

        AtomicBoolean aSuccessRan = new AtomicBoolean(false);
        CountDownLatch bSuccess = new CountDownLatch(1);

        SendCallbacks throwingOnSuccess = new SendCallbacks(
                () -> {
                    aSuccessRan.set(true);
                    throw new RuntimeException("boom from A's onSuccess");
                },
                null);
        SendCallbacks normalCallbacks = new SendCallbacks(bSuccess::countDown, null);

        // act
        sender.enqueue(message("A"), throwingOnSuccess);
        sender.enqueue(message("B"), normalCallbacks);

        // assert: B was delivered AND its success callback ran — worker survived A's throw.
        assertThat(bSuccess.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("B's onSuccess must run — worker must survive A's throwing callback")
                .isTrue();
        assertThat(aSuccessRan)
                .as("A's onSuccess must have been invoked (and thrown)")
                .isTrue();
        assertThat(executedChatIds).containsExactly("A", "B");
    }

    @Test
    @DisplayName("should_keep_draining_when_send_fails_and_failure_callback_throws")
    void should_keep_draining_when_send_fails_and_failure_callback_throws() throws Exception {
        // arrange: A's execute throws a non-429 error AND A's onFailure throws (DB-style).
        // B then succeeds normally and must still be delivered.
        List<String> executedChatIds = new CopyOnWriteArrayList<>();
        TelegramApiException nonRetryable =
                new TelegramApiException("Forbidden: bot was blocked by the user [403]");
        doAnswer(inv -> {
            SendMessage sent = inv.getArgument(0);
            executedChatIds.add(sent.getChatId());
            if ("A".equals(sent.getChatId())) {
                throw nonRetryable;
            }
            return null;
        }).when(telegramClient).execute(any(SendMessage.class));
        startSender(100, noOpSleeper());

        CountDownLatch bSuccess = new CountDownLatch(1);
        SendCallbacks throwingOnFailure = new SendCallbacks(
                null,
                e -> {
                    throw new RuntimeException("boom from A's onFailure", e);
                });
        SendCallbacks normalCallbacks = new SendCallbacks(bSuccess::countDown, null);

        // act
        sender.enqueue(message("A"), throwingOnFailure);
        sender.enqueue(message("B"), normalCallbacks);

        // assert: B delivered and acked despite A's failure callback throwing.
        assertThat(bSuccess.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("B must be delivered — worker must survive A's throwing failure callback")
                .isTrue();
        assertThat(executedChatIds).containsExactly("A", "B");
    }

    @Test
    @DisplayName("should_drop_overflow_messages_without_blocking_caller_when_queue_full")
    void should_drop_overflow_messages_without_blocking_caller_when_queue_full() throws Exception {
        // arrange: worker is parked inside the rate-limiter sleep on the first message,
        // so it cannot drain the queue. Capacity is 1, so beyond that messages are dropped.
        CountDownLatch workerParked = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        Sleeper blockingSleeper = millis -> {
            workerParked.countDown();
            try {
                releaseWorker.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        };
        startSender(1, blockingSleeper, 50L);

        // First message: worker pulls it and parks in the sleeper before executing.
        sender.enqueue(message("first"));
        assertThat(workerParked.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("worker should have pulled the first message and parked in the sleeper")
                .isTrue();

        // act: queue (capacity 1) takes "queued"; "dropped" overflows. enqueue must not block.
        sender.enqueue(message("queued"));
        sender.enqueue(message("dropped"));

        // assert: the overflowing message was dropped → one OTHER-failure metric recorded.
        verify(metricsService).recordNotificationFailed(
                MetricsService.CHANNEL_TELEGRAM, MetricsService.FailureReason.OTHER);

        // release the worker so @AfterEach shutdown is clean.
        releaseWorker.countDown();
    }

    private static SendMessage message(String chatId) {
        return SendMessage.builder()
                .chatId(chatId)
                .text("hi")
                .build();
    }

    private static Sleeper noOpSleeper() {
        return millis -> { /* no real sleep — tests must be fast */ };
    }
}
