package com.plantcare.delivery.push;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;
import com.plantcare.core.service.PushSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * FCM-реализация порта {@link PushSender} через Firebase Admin SDK (HTTP v1, issue #176 / ADR-016).
 *
 * <p>Единый шлюз push для iOS и Android: FCM сам маршрутизирует на APNs для iOS-токенов,
 * поэтому отдельной APNs-интеграции нет (см. ADR-016). Активируется бином из
 * {@link FcmPushConfig} только при {@code push.enabled=true}; иначе работает
 * {@link com.plantcare.core.config.NoopPushSender}.
 *
 * <p>Контракт порта соблюдён строго: метод НЕ бросает исключений наружу — любой исход
 * доставки кодируется через {@link PushResult}:
 * <ul>
 *   <li>успешная отправка → {@link PushResult#SENT};</li>
 *   <li>{@link MessagingErrorCode#UNREGISTERED} (токен протух/удалён, эквивалент
 *       {@code NOT_FOUND}/невалидный токен) → {@link PushResult#STALE_TOKEN};</li>
 *   <li>прочие ошибки (сеть, 5xx, {@code INTERNAL}, {@code UNAVAILABLE}, любое
 *       непредвиденное исключение) → {@link PushResult#FAILED}.</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class FcmPushSender implements PushSender {

    private final FirebaseMessaging firebaseMessaging;

    @Override
    public PushResult send(String pushToken, String title, String body) {
        Message message = Message.builder()
                .setToken(pushToken)
                .setNotification(Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .build())
                .build();

        try {
            String messageId = firebaseMessaging.send(message);
            log.debug("FCM push sent: messageId={}", messageId);
            return PushResult.SENT;
        } catch (FirebaseMessagingException e) {
            if (isStaleToken(e)) {
                log.info("FCM push stale token (errorCode={}): token will be pruned", e.getMessagingErrorCode());
                return PushResult.STALE_TOKEN;
            }
            log.warn("FCM push failed (errorCode={}): {}", e.getMessagingErrorCode(), e.getMessage());
            return PushResult.FAILED;
        } catch (Exception e) {
            // Контракт порта: наружу не бросаем — любая прочая ошибка трактуется как FAILED.
            log.warn("FCM push failed (unexpected): {}", e.getMessage());
            return PushResult.FAILED;
        }
    }

    /**
     * Токен считается протухшим, если FCM вернул {@code UNREGISTERED} (устройство отписалось /
     * приложение удалено) или {@code INVALID_ARGUMENT} (токен синтаксически невалиден). В обоих
     * случаях запись устройства подлежит удалению из {@code user_devices} (issue #177).
     */
    private boolean isStaleToken(FirebaseMessagingException e) {
        MessagingErrorCode code = e.getMessagingErrorCode();
        return code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.INVALID_ARGUMENT;
    }
}
