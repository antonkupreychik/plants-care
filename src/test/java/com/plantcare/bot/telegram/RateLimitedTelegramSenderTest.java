package com.plantcare.bot.telegram;

import com.plantcare.bot.client.TelegramClientProvider;
import com.plantcare.bot.config.TelegramRateLimitProperties;
import com.plantcare.core.metrics.MetricsService;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты пути отправки {@link RateLimitedTelegramSender#sendWithRetry} (issue #29).
 *
 * <p>Метод дёргается напрямую (он package-private, тест в том же пакете), очередь
 * и воркер-поток не поднимаются. {@link Sleeper} застаблен записывающей реализацией —
 * никакого реального сна на ретраях 429. Внешний Telegram замокан через
 * {@link TelegramClientProvider}.
 */
@DisplayName("RateLimitedTelegramSender.sendWithRetry — отправка с ретраями 429 (issue #29)")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RateLimitedTelegramSenderTest {

    private static final int MAX_RETRIES = 3;
    private static final long DEFAULT_RETRY_AFTER = 2L;

    @Mock
    private TelegramClientProvider telegramClientProvider;

    @Mock
    private TelegramClient telegramClient;

    @Mock
    private TokenBucketRateLimiter rateLimiter;

    @Mock
    private MetricsService metricsService;

    private RecordingSleeper sleeper;
    private RateLimitedTelegramSender sender;

    @BeforeEach
    void setUp() {
        sleeper = new RecordingSleeper();
        TelegramRateLimitProperties properties = new TelegramRateLimitProperties(
                25, 100, MAX_RETRIES, DEFAULT_RETRY_AFTER, 60L);
        sender = new RateLimitedTelegramSender(
                telegramClientProvider, rateLimiter, sleeper, properties, metricsService);
        when(telegramClientProvider.getTelegramClient()).thenReturn(telegramClient);
    }

    @Test
    @DisplayName("should_call_execute_once_and_run_success_when_send_succeeds")
    void should_call_execute_once_and_run_success_when_send_succeeds() throws Exception {
        // arrange
        AtomicBoolean successRan = new AtomicBoolean(false);
        AtomicBoolean failureRan = new AtomicBoolean(false);
        SendCallbacks callbacks = new SendCallbacks(
                () -> successRan.set(true),
                e -> failureRan.set(true));

        // act
        sender.sendWithRetry(message("100"), callbacks);

        // assert
        verify(telegramClient, times(1)).execute(any(SendMessage.class));
        assertThat(successRan).isTrue();
        assertThat(failureRan).isFalse();
        assertThat(sleeper.sleeps).isEmpty();
    }

    @Test
    @DisplayName("should_retry_once_with_parsed_retry_after_when_429_then_succeeds")
    void should_retry_once_with_parsed_retry_after_when_429_then_succeeds() throws Exception {
        // arrange: первая попытка — 429 с «retry after 30», вторая — успех.
        when(telegramClient.execute(any(SendMessage.class)))
                .thenThrow(new TelegramApiException("[429] Too Many Requests: retry after 30"))
                .thenReturn(null);

        AtomicBoolean successRan = new AtomicBoolean(false);
        SendCallbacks callbacks = new SendCallbacks(() -> successRan.set(true), null);

        // act
        sender.sendWithRetry(message("100"), callbacks);

        // assert: ровно 2 вызова execute, пауза = распарсенные 30s в миллисекундах.
        verify(telegramClient, times(2)).execute(any(SendMessage.class));
        assertThat(sleeper.sleeps).containsExactly(30_000L);
        assertThat(successRan).isTrue();
        verify(metricsService).recordTelegramApiError(MetricsService.TelegramErrorCode.RATE_LIMITED);
    }

    @Test
    @DisplayName("should_fall_back_to_default_retry_after_when_429_has_no_parsable_value")
    void should_fall_back_to_default_retry_after_when_429_has_no_parsable_value() throws Exception {
        // arrange: 429 без числа retry-after → fallback из конфигурации.
        when(telegramClient.execute(any(SendMessage.class)))
                .thenThrow(new TelegramApiException("[429] Too Many Requests"))
                .thenReturn(null);

        // act
        sender.sendWithRetry(message("100"), SendCallbacks.none());

        // assert
        assertThat(sleeper.sleeps).containsExactly(DEFAULT_RETRY_AFTER * 1000L);
    }

    @Test
    @DisplayName("should_retry_exactly_max_retries_then_fail_when_429_persists")
    void should_retry_exactly_max_retries_then_fail_when_429_persists() throws Exception {
        // arrange: 429 на каждой попытке — ретраи ограничены, без бесконечного цикла.
        when(telegramClient.execute(any(SendMessage.class)))
                .thenThrow(new TelegramApiException("[429] Too Many Requests: retry after 5"));

        AtomicBoolean successRan = new AtomicBoolean(false);
        AtomicReference<TelegramApiException> failure = new AtomicReference<>();
        SendCallbacks callbacks = new SendCallbacks(
                () -> successRan.set(true),
                failure::set);

        // act
        sender.sendWithRetry(message("100"), callbacks);

        // assert: 1 первичная попытка + MAX_RETRIES ретраев = MAX_RETRIES + 1 вызовов.
        verify(telegramClient, times(MAX_RETRIES + 1)).execute(any(SendMessage.class));
        assertThat(sleeper.sleeps).hasSize(MAX_RETRIES);
        assertThat(successRan).isFalse();
        assertThat(failure.get())
                .isNotNull()
                .hasMessageContaining("429");
    }

    @Test
    @DisplayName("should_not_retry_and_fail_immediately_when_error_is_403")
    void should_not_retry_and_fail_immediately_when_error_is_403() throws Exception {
        // arrange: 403 — неретраябельная ошибка.
        TelegramApiException forbidden =
                new TelegramApiException("Forbidden: bot was blocked by the user [403]");
        when(telegramClient.execute(any(SendMessage.class))).thenThrow(forbidden);

        AtomicReference<TelegramApiException> failure = new AtomicReference<>();
        SendCallbacks callbacks = new SendCallbacks(null, failure::set);

        // act
        sender.sendWithRetry(message("100"), callbacks);

        // assert: ровно один вызов, никакого сна, onFailure получил то же исключение.
        verify(telegramClient, times(1)).execute(any(SendMessage.class));
        assertThat(sleeper.sleeps).isEmpty();
        assertThat(failure.get()).isSameAs(forbidden);
        verify(metricsService).recordTelegramApiError(MetricsService.TelegramErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("should_not_throw_when_callbacks_are_none_on_success")
    void should_not_throw_when_callbacks_are_none_on_success() {
        // arrange: успех с заглушечными колбэками не должен бросать NPE.

        // act + assert
        assertThatNoException().isThrownBy(() ->
                sender.sendWithRetry(message("100"), SendCallbacks.none()));
    }

    @Test
    @DisplayName("should_not_throw_when_callbacks_are_none_on_failure")
    void should_not_throw_when_callbacks_are_none_on_failure() throws Exception {
        // arrange: неретраябельная ошибка с заглушечными колбэками — без NPE.
        when(telegramClient.execute(any(SendMessage.class)))
                .thenThrow(new TelegramApiException("Bad Request: chat not found [400]"));

        // act + assert
        assertThatNoException().isThrownBy(() ->
                sender.sendWithRetry(message("100"), SendCallbacks.none()));
        verify(telegramClient, times(1)).execute(any(SendMessage.class));
    }

    private static SendMessage message(String chatId) {
        return SendMessage.builder()
                .chatId(chatId)
                .text("hi")
                .build();
    }

    /** Стаб-{@link Sleeper}: ничего не ждёт, лишь запоминает запрошенные паузы. */
    private static final class RecordingSleeper implements Sleeper {
        private final List<Long> sleeps = new ArrayList<>();

        @Override
        public void sleep(long millis) {
            sleeps.add(millis);
        }
    }
}
