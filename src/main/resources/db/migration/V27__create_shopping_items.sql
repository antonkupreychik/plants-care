-- V24__create_shopping_items.sql
-- Issue #136: персональный список покупок (чек-лист) пользователя.
--
-- Каждый пользователь ведёт свой список того, что нужно купить для ухода за
-- растениями (грунт, горшки, удобрения и т.п.). Позицию можно отметить
-- купленной (checked) или удалить. Сортировка/фильтрация — по (user_id, checked).
--
-- Backward-compat note:
--   * Новая таблица — для старого кода невидима (аддитивно, один релиз).
--   * id маппится JPA как GenerationType.IDENTITY → BIGSERIAL.
--   * created_at маппится @CreationTimestamp → TIMESTAMPTZ (UTC), как в остальных таблицах.
--   * ON DELETE CASCADE на user_id: при удалении пользователя его список покупок
--     уезжает вместе с ним — это ок, id не переиспользуется.
--   * Безопасно для rolling deploy. Только DDL, без сидинга.

BEGIN;

CREATE TABLE shopping_items (
    id          BIGSERIAL    PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title       VARCHAR(160) NOT NULL,
    checked     BOOLEAN      NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE shopping_items IS
    'Персональный список покупок пользователя (issue #136): по строке на позицию.';

COMMENT ON COLUMN shopping_items.title IS
    'Текст позиции списка (что купить), до 160 символов.';

COMMENT ON COLUMN shopping_items.checked IS
    'Отмечена ли позиция купленной. false = ещё нужно купить.';

COMMENT ON COLUMN shopping_items.created_at IS
    'Момент создания позиции в UTC (TIMESTAMPTZ).';

-- Основной запрос: список позиций юзера с разбивкой по статусу (checked).
-- Покрывает и FK user_id.
CREATE INDEX idx_shopping_items_user_checked
    ON shopping_items (user_id, checked);

COMMIT;
