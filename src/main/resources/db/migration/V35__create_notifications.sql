-- V35__create_notifications.sql
-- Issue #183: персистентный inbox нотификаций для мобильного приложения.
--
-- Лента уведомлений пользователя: уход, алерты, награды, отчёты, системные
-- сообщения. Раньше уведомления были эфемерны (ушли в Telegram и забылись),
-- мобильному клиенту нужен постоянный список, который можно листать и помечать
-- прочитанным. Каждая строка — одно уведомление; read_at IS NULL означает
-- «непрочитано».
--
-- type хранится как строка enum (care|alert|award|report|system). CHECK-констрейнт
-- на список значений НАМЕРЕННО не ставится: новые типы уведомлений должны
-- добавляться кодом без отдельной миграции (forward-compat). VARCHAR(16) — лимит
-- на длину, не на словарь.
--
-- plant_id опционален и нужен для deep-link на растение из ленты. При удалении
-- растения ставится NULL (ON DELETE SET NULL): само уведомление — исторический
-- факт, оно остаётся в ленте, просто теряет ссылку на уже несуществующее растение.
-- При удалении пользователя его уведомления уезжают целиком (ON DELETE CASCADE).
--
-- Backward-compat note:
--   * Новая таблица — для старого кода невидима (аддитивно, один релиз).
--     Полностью безопасно для rolling deploy: старая версия приложения её просто
--     не знает, новая — пишет и читает.
--   * id маппится JPA как GenerationType.IDENTITY → BIGSERIAL (BaseEntity).
--   * created_at маппится @CreationTimestamp → TIMESTAMPTZ (UTC), как в остальных
--     таблицах (BaseEntity.createdAt).
--   * Только DDL, без сидинга.

BEGIN;

CREATE TABLE notifications (
    id         BIGSERIAL    PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users(id)  ON DELETE CASCADE,
    type       VARCHAR(16)  NOT NULL,
    title      VARCHAR(200) NOT NULL,
    body       TEXT         NOT NULL,
    plant_id   BIGINT       REFERENCES plants(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    read_at    TIMESTAMPTZ
);

COMMENT ON TABLE notifications IS
    'Персистентный inbox уведомлений пользователя для мобильного приложения '
        '(issue #183): по строке на уведомление, read_at IS NULL = непрочитано.';

COMMENT ON COLUMN notifications.user_id IS
    'Владелец уведомления. ON DELETE CASCADE: удаляется пользователь — уезжают '
        'и его уведомления.';

COMMENT ON COLUMN notifications.type IS
    'Тип уведомления как строка enum (care|alert|award|report|system). CHECK '
        'намеренно не ставится — новые типы добавляются кодом без миграции.';

COMMENT ON COLUMN notifications.title IS
    'Заголовок уведомления для отображения в ленте.';

COMMENT ON COLUMN notifications.body IS
    'Текст уведомления.';

COMMENT ON COLUMN notifications.plant_id IS
    'Опциональная ссылка на растение для deep-link из ленты. ON DELETE SET NULL: '
        'растение удалили — уведомление остаётся как исторический факт без ссылки.';

COMMENT ON COLUMN notifications.created_at IS
    'Момент создания уведомления в UTC (TIMESTAMPTZ). @CreationTimestamp.';

COMMENT ON COLUMN notifications.read_at IS
    'Момент прочтения в UTC (TIMESTAMPTZ). NULL = непрочитано.';

-- Основной запрос ленты: уведомления юзера, новые сверху.
--   SELECT ... WHERE user_id = :uid ORDER BY created_at DESC
-- Покрывает и FK user_id.
CREATE INDEX idx_notifications_user_created_at
    ON notifications (user_id, created_at DESC);

-- Счётчик непрочитанных: SELECT count(*) WHERE user_id = :uid AND read_at IS NULL.
-- Частичный индекс — только по непрочитанным, не раздувается по мере прочтения.
CREATE INDEX idx_notifications_user_unread
    ON notifications (user_id)
    WHERE read_at IS NULL;

-- FK plant_id под ON DELETE SET NULL и deep-link-поиск. Postgres не индексирует FK сам.
CREATE INDEX idx_notifications_plant
    ON notifications (plant_id);

COMMIT;
