-- V37__add_tokens_valid_from_to_users.sql
-- Issue #178: logout + per-user token epoch ("logout-all").
-- В users добавляется колонка tokens_valid_from — момент (UTC), начиная с
-- которого refresh-токены данного пользователя считаются действительными.
-- Проверка при предъявлении refresh: если tokens_valid_from задан и
-- iat(refresh) < tokens_valid_from — токен невалиден. logout-all выставляет
-- tokens_valid_from = now(), тем самым одномоментно инвалидируя все ранее
-- выданные refresh-токены пользователя.
--
-- NULL == "эпоха не задана" == проверка iat < tokens_valid_from ПРОПУСКАЕТСЯ.
-- Это намеренная backward-compat семантика, а не недосмотр:
--   * все refresh-токены, выданные до этой фичи, остаются валидными;
--   * пользователи, которые никогда не делали logout-all, не затрагиваются.
-- Поэтому колонка СТРОГО NULLABLE и БЕЗ DEFAULT. NOT NULL или DEFAULT now()/
-- CURRENT_TIMESTAMP проставил бы эпоху всем существующим строкам и
-- инвалидировал бы каждый активный refresh-токен при деплое — это запрещено.
--
-- Backward-compat note:
--   * NULLABLE ADD COLUMN без DEFAULT в Postgres — метаданные-онли, без
--     переписывания таблицы (instant, no rewrite). Безопасно для rolling deploy.
--   * Старый код вставляет/читает users без упоминания колонки и продолжает
--     работать: новые строки получают NULL, что = "проверка пропускается".
--   * Бэкфил НЕ нужен и НЕ допускается (NULL — рабочее значение по умолчанию).
--   * Индекс не нужен: колонка читается по строке пользователя (PK lookup),
--     а не используется как предикат в выборках.

BEGIN;

ALTER TABLE users
    ADD COLUMN tokens_valid_from TIMESTAMPTZ;

COMMENT ON COLUMN users.tokens_valid_from IS
    'Эпоха валидности refresh-токенов пользователя (UTC). Refresh с '
        'iat < tokens_valid_from считается невалидным. NULL = эпоха не задана, '
        'проверка пропускается (backward-compat для старых токенов и юзеров без '
        'logout-all). logout-all выставляет это поле в now().';

COMMIT;
