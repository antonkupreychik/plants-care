-- V22__add_mobile_auth_to_users.sql
-- Issue #88: мобильная аутентификация (ADR-011).
-- Раньше пользователь идентифицировался только по Telegram chat id. Теперь
-- появляется вход через email (magic link) и Sign in with Apple / Google.
-- Поэтому в users добавляются альтернативные идентификаторы, а telegram_chat_id
-- перестаёт быть обязательным (пользователь может зарегистрироваться с телефона
-- вообще без Telegram).
--
-- Backward-compat note:
--   * Все новые колонки NULLABLE (email_verified — NOT NULL, но с DEFAULT false),
--     поэтому существующие INSERT в users, написанные старым кодом, продолжают
--     работать без упоминания новых колонок. Бэкфил не нужен.
--   * DROP NOT NULL на telegram_chat_id только ослабляет ограничение — любой
--     старый INSERT, который всё ещё передаёт telegram_chat_id, остаётся валидным.
--   * Уникальность telegram_chat_id СОХРАНЯЕТСЯ через исходный inline UNIQUE
--     (users_telegram_chat_id_key). В Postgres обычный UNIQUE-индекс допускает
--     несколько NULL, поэтому после снятия NOT NULL пользователи без Telegram
--     (telegram_chat_id = NULL) не конфликтуют друг с другом, а реальные chat id
--     по-прежнему уникальны. Пересоздавать индекс как partial НЕ требуется.
--   * Безопасно для rolling deploy в один шаг: старая версия приложения
--     продолжает работать на расширенной схеме без изменений.

BEGIN;

ALTER TABLE users
    ADD COLUMN email           VARCHAR(320),
    ADD COLUMN email_verified  BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN apple_subject   VARCHAR(255),
    ADD COLUMN google_subject  VARCHAR(255);

COMMENT ON COLUMN users.email IS
    'Email для входа по magic link / связки с OAuth. NULL для чисто Telegram-юзеров. '
        'Уникален среди не-NULL значений (idx_users_email).';
COMMENT ON COLUMN users.email_verified IS
    'TRUE после подтверждения email (magic link). По умолчанию FALSE.';
COMMENT ON COLUMN users.apple_subject IS
    'Стабильный subject (sub) из Sign in with Apple. NULL если Apple не привязан. '
        'Уникален среди не-NULL значений.';
COMMENT ON COLUMN users.google_subject IS
    'Стабильный subject (sub) из Google ID token. NULL если Google не привязан. '
        'Уникален среди не-NULL значений.';

-- telegram_chat_id больше не обязателен: регистрация возможна с мобильного
-- приложения без Telegram. Inline UNIQUE-индекс остаётся и продолжает
-- гарантировать уникальность реальных chat id (NULL не конфликтуют).
ALTER TABLE users
    ALTER COLUMN telegram_chat_id DROP NOT NULL;

COMMENT ON COLUMN users.telegram_chat_id IS
    'Telegram chat id. NULL для пользователей, зарегистрированных только через '
        'мобильное приложение (email/Apple/Google). Уникален среди не-NULL значений.';

-- Partial UNIQUE индексы: уникальность только для заданных (не-NULL) значений,
-- т.к. большинство строк по каждому из этих столбцов будет NULL.
CREATE UNIQUE INDEX idx_users_email
    ON users (email)
    WHERE email IS NOT NULL;

CREATE UNIQUE INDEX idx_users_apple_subject
    ON users (apple_subject)
    WHERE apple_subject IS NOT NULL;

CREATE UNIQUE INDEX idx_users_google_subject
    ON users (google_subject)
    WHERE google_subject IS NOT NULL;

COMMIT;
