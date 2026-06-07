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
 * проглатывает все вызовы без обращения к внешним сервисам.
 *
 * <p>Контракт: метод идемпотентен с точки зрения бизнес-логики — повторная
 * отправка на один и тот же токен не является ошибкой. Если токен просрочен
 * или недоступен, реализация логирует предупреждение и возвращает управление
 * без исключения (чтобы не сломать шедулер).
 */
public interface PushSender {

    /**
     * Отправить push-уведомление на устройство.
     *
     * @param pushToken push-токен устройства (FCM registration token / APNs device token)
     * @param title     заголовок уведомления
     * @param body      текст уведомления
     */
    void send(String pushToken, String title, String body);
}
