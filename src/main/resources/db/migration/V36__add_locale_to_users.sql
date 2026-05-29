-- V34__add_locale_to_users.sql
-- Issue #182: GET/PATCH /api/v1/me.
-- В users добавляется колонка locale — язык интерфейса/уведомлений мобильного
-- клиента. Допустимые значения: 'ru', 'en'. Дефолт 'ru' (русскоязычный userbase,
-- дефолтная таймзона Europe/Minsk — см. User entity).
--
-- Backward-compat note:
--   * Колонка NOT NULL, но с DEFAULT 'ru' — Postgres проставит значение всем
--     существующим строкам при ALTER, поэтому отдельный бэкфил не нужен.
--   * Старый код, который вставляет в users без упоминания locale, продолжает
--     работать: DEFAULT покрывает INSERT, а на чтение старый код колонку
--     игнорирует. Поэтому это безопасно одной миграцией для rolling deploy —
--     3-шаговый nullable-танец нужен только для NOT NULL БЕЗ дефолта.
--   * CHECK chk_users_locale соответствует house style проекта: enum-ish колонки
--     (seasonal_mode, seasonal_override, species.light_preference и т.д.)
--     ограничиваются на уровне БД через CHECK IN (...).

BEGIN;

ALTER TABLE users
    ADD COLUMN locale VARCHAR(8) NOT NULL DEFAULT 'ru',
    ADD CONSTRAINT chk_users_locale
        CHECK (locale IN ('ru', 'en'));

COMMENT ON COLUMN users.locale IS
    'Язык интерфейса/уведомлений мобильного клиента: ru | en. По умолчанию ru.';

COMMIT;
