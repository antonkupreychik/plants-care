package com.plantcare.core.domain.enums;

/**
 * Канал доставки уведомления (issue #95).
 *
 * <p>Умышленно два значения, а не три. Issue #95 писалась под «Telegram / APNs / FCM»,
 * но по ADR-016 push-канал в проекте ЕДИНЫЙ: {@code FcmPushSender} шлёт через FCM,
 * а FCM сам маршрутизирует на APNs для iOS-токенов. Отдельной APNs-интеграции нет,
 * поэтому и отдельного канала в журнале доставок нет — иначе дашборд показывал бы
 * вечно пустую колонку.
 *
 * <p>Значения совпадают с тэгами метрик {@code MetricsService.CHANNEL_*}
 * (в нижнем регистре), чтобы дашборд и Prometheus говорили об одном и том же.
 */
public enum DeliveryChannel {

    /** Telegram Bot API. */
    TELEGRAM,

    /** Push на мобильные устройства через FCM (включая iOS через APNs-маршрутизацию FCM). */
    PUSH
}
