-- V55__create_notification_delivery_events.sql
-- Issue #95: журнал попыток доставки уведомлений по каналам (health-дашборд).
--
-- Каждая попытка доставки (одно сообщение в один канал) пишет ровно одну строку.
-- Дашборд /admin/notifications/health считает по этой таблице success/error rate
-- за окно, топ error_code и юзеров с подряд идущими фейлами.
--
-- Каналы: TELEGRAM и PUSH. Отдельного APNS-канала нет — по ADR-016 push единый
-- (FCM сам маршрутизирует на APNs для iOS-токенов), см. FcmPushSender.
--
-- Backward-compat note:
--   * Новая таблица — для старого кода невидима (аддитивно, один релиз).
--   * id маппится JPA как GenerationType.IDENTITY → BIGSERIAL.
--   * created_at — TIMESTAMPTZ (UTC), как везде; маппится в Instant.
--   * user_id NULLABLE: chat_id не всегда резолвится в пользователя (например,
--     copy-уведомление caretaker'у, чат которого уже удалён). ON DELETE CASCADE —
--     удаление юзера убирает его историю доставок.
--   * Ретенция: строки старше 30 дней чистит NotificationDeliveryCleanupScheduler.
--   * Только DDL, без сидинга. Безопасно для rolling deploy.

CREATE TABLE notification_delivery_events (
    id         BIGSERIAL   PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    channel    VARCHAR(16) NOT NULL,
    user_id    BIGINT      REFERENCES users(id) ON DELETE CASCADE,
    status     VARCHAR(16) NOT NULL,
    error_code VARCHAR(64),
    latency_ms INTEGER
);

COMMENT ON TABLE  notification_delivery_events            IS 'Журнал попыток доставки уведомлений по каналам (issue #95). Одна строка = одна попытка доставки одного сообщения.';
COMMENT ON COLUMN notification_delivery_events.channel    IS 'Канал доставки: TELEGRAM | PUSH (push = FCM, он же маршрутизирует на APNs, ADR-016).';
COMMENT ON COLUMN notification_delivery_events.user_id    IS 'Получатель (users.id); NULL — чат не удалось сопоставить с пользователем.';
COMMENT ON COLUMN notification_delivery_events.status     IS 'Исход попытки: SENT | FAILED | RATE_LIMITED.';
COMMENT ON COLUMN notification_delivery_events.error_code IS 'Код ошибки в формате <канал>:<код>, например telegram:403 или fcm:UnregisteredDevice. NULL для SENT.';
COMMENT ON COLUMN notification_delivery_events.latency_ms IS 'Длительность вызова внешнего API в миллисекундах.';

-- Карточки/топ ошибок за окно (WHERE created_at >= now() - interval, GROUP BY channel).
CREATE INDEX idx_nde_created_at ON notification_delivery_events (created_at DESC);
-- Почасовой график и разрезы по каналу за окно.
CREATE INDEX idx_nde_channel_created_at ON notification_delivery_events (channel, created_at DESC);
-- «Юзеры с проблемами»: серия фейлов подряд по паре (user_id, channel).
CREATE INDEX idx_nde_user_channel_created_at
    ON notification_delivery_events (user_id, channel, created_at DESC)
    WHERE user_id IS NOT NULL;
