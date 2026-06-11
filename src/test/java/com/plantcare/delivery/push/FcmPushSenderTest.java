package com.plantcare.delivery.push;

import com.google.firebase.ErrorCode;
import com.google.firebase.FirebaseException;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.plantcare.core.service.PushSender.PushResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты {@link FcmPushSender} (issue #176 / ADR-016): маппинг исхода Firebase Admin SDK
 * в {@link PushResult}. {@link FirebaseMessaging} замокан — реальный Firebase не дёргается.
 *
 * <p>Ключевой контракт порта: метод НЕ бросает исключений наружу — любая ошибка
 * кодируется через {@link PushResult}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-тесты для FcmPushSender (issue #176)")
class FcmPushSenderTest {

    @Mock
    private FirebaseMessaging firebaseMessaging;

    @Test
    @DisplayName("Успешная отправка → PushResult.SENT")
    void should_return_sent_when_message_delivered() throws Exception {
        when(firebaseMessaging.send(any(Message.class))).thenReturn("projects/x/messages/123");
        FcmPushSender sender = new FcmPushSender(firebaseMessaging);

        PushResult result = sender.send("token-abc", "Plants Care", "Пора полить: Алоэ");

        assertThat(result).isEqualTo(PushResult.SENT);
    }

    @Test
    @DisplayName("FirebaseMessagingException с UNREGISTERED → PushResult.STALE_TOKEN")
    void should_return_stale_token_when_token_unregistered() throws Exception {
        when(firebaseMessaging.send(any(Message.class)))
                .thenThrow(messagingException(MessagingErrorCode.UNREGISTERED));
        FcmPushSender sender = new FcmPushSender(firebaseMessaging);

        PushResult result = sender.send("dead-token", "Plants Care", "body");

        assertThat(result).isEqualTo(PushResult.STALE_TOKEN);
    }

    @Test
    @DisplayName("FirebaseMessagingException с INVALID_ARGUMENT (невалидный токен) → STALE_TOKEN")
    void should_return_stale_token_when_token_invalid() throws Exception {
        when(firebaseMessaging.send(any(Message.class)))
                .thenThrow(messagingException(MessagingErrorCode.INVALID_ARGUMENT));
        FcmPushSender sender = new FcmPushSender(firebaseMessaging);

        PushResult result = sender.send("garbage", "Plants Care", "body");

        assertThat(result).isEqualTo(PushResult.STALE_TOKEN);
    }

    @Test
    @DisplayName("Транзиентная ошибка (UNAVAILABLE/5xx) → PushResult.FAILED")
    void should_return_failed_when_provider_unavailable() throws Exception {
        when(firebaseMessaging.send(any(Message.class)))
                .thenThrow(messagingException(MessagingErrorCode.UNAVAILABLE));
        FcmPushSender sender = new FcmPushSender(firebaseMessaging);

        PushResult result = sender.send("token", "Plants Care", "body");

        assertThat(result).isEqualTo(PushResult.FAILED);
    }

    @Test
    @DisplayName("Непредвиденное исключение → PushResult.FAILED, метод НЕ бросает наружу")
    void should_return_failed_and_not_throw_on_unexpected_exception() throws Exception {
        when(firebaseMessaging.send(any(Message.class)))
                .thenThrow(new RuntimeException("network exploded"));
        FcmPushSender sender = new FcmPushSender(firebaseMessaging);

        assertThatCode(() -> {
            PushResult result = sender.send("token", "Plants Care", "body");
            assertThat(result).isEqualTo(PushResult.FAILED);
        }).doesNotThrowAnyException();
    }

    /**
     * Строит НАСТОЯЩИЙ {@link FirebaseMessagingException} с нужным {@link MessagingErrorCode}.
     *
     * <p>Конструктор и фабрики класса package-private, а {@code getMessagingErrorCode()} —
     * final-метод (мокать нельзя), поэтому через рефлексию дёргаем статическую фабрику
     * {@code FirebaseMessagingException.withMessagingErrorCode(FirebaseException, MessagingErrorCode)},
     * получая реальный инстанс — без хрупкого мока final-метода.
     */
    private static FirebaseMessagingException messagingException(MessagingErrorCode code) {
        try {
            FirebaseException base = new FirebaseException(
                    ErrorCode.UNKNOWN, "test-" + code, null);
            var factory = FirebaseMessagingException.class.getDeclaredMethod(
                    "withMessagingErrorCode", FirebaseException.class, MessagingErrorCode.class);
            factory.setAccessible(true);
            return (FirebaseMessagingException) factory.invoke(null, base, code);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to build FirebaseMessagingException for test", e);
        }
    }
}
