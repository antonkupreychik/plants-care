package com.plantcare.core.service;

/**
 * Порт отправки push-уведомлений на мобильные устройства (issue #175, ADR-014).
 *
 * <p>Реализации выбираются по платформе токена:
 * <ul>
 *   <li>{@code FCM} — Firebase Cloud Messaging (Android)</li>
 *   <li>{@code APNs} — Apple Push Notification service (iOS)</li>
 * </ul>
 *
 * <p>Когда {@code push.enabled=false} (дефолт), {@link com.plantcare.core.config.NoopPushSender}
 * проглатывает все вызовы без обращения к внешним сервисам и возвращает
 * {@link PushResult#SENT}.
 *
 * <p>Контракт: метод идемпотентен с точки зрения бизнес-логики — повторная
 * отправка на один и тот же токен не является ошибкой. Реализация НЕ бросает
 * исключение на ошибки доставки (чтобы не сломать шедулер), а сообщает исход
 * через {@link PushResult}:
 * <ul>
 *   <li>{@link PushResult#SENT} — доставлено провайдеру;</li>
 *   <li>{@link PushResult#STALE_TOKEN} — токен протух/неизвестен
 *       (FCM {@code UNREGISTERED} / {@code NOT_FOUND}); вызывающий обязан удалить
 *       запись устройства из {@code user_devices} (issue #177);</li>
 *   <li>{@link PushResult#FAILED} — иная ошибка доставки (сеть, 5xx провайдера);
 *       токен остаётся, повтор возможен на следующем тике.</li>
 * </ul>
 */
public interface PushSender {

    /**
     * Отправить push-уведомление на устройство.
     *
     * @param pushToken push-токен устройства (FCM registration token / APNs device token)
     * @param title     заголовок уведомления
     * @param body      текст уведомления
     * @return исход доставки (см. {@link PushResult})
     */
    PushResult send(String pushToken, String title, String body);

    /**
     * Исход доставки push-уведомления (issue #177). Мультиканальный fan-out
     * принимает решения по нему, не зная деталей конкретного провайдера.
     */
    enum PushResult {
        /** Доставлено провайдеру (или проглочено no-op-реализацией). */
        SENT,
        /**
         * Токен протух/неизвестен (FCM {@code UNREGISTERED} / {@code NOT_FOUND}).
         * Запись устройства подлежит удалению из {@code user_devices}.
         */
        STALE_TOKEN,
        /** Иная ошибка доставки — токен остаётся, повтор на следующем тике. */
        FAILED
    }
}
