-- V15__add_care_event_client_id.sql
-- Колонка client_id для идемпотентности мобильного REST API (issue #86).
-- Mobile-клиент генерирует UUID и передаёт его при POST /api/v1/care-events;
-- при retry после офлайн-синка повторный INSERT с тем же client_id
-- отклоняется уникальным индексом (или игнорируется через ON CONFLICT).
--
-- Backward-compat note:
--   Колонка nullable — старый код Telegram-бота не знает о client_id
--   и не передаёт его; такие записи останутся с NULL.
--   Partial unique index (WHERE client_id IS NOT NULL) гарантирует,
--   что множество NULL-значений не конфликтует между собой.

BEGIN;

ALTER TABLE care_history
    ADD COLUMN IF NOT EXISTS client_id VARCHAR(64) NULL;

COMMENT ON COLUMN care_history.client_id IS
    'UUID от мобильного клиента для идемпотентности (issue #86). '
    'NULL для записей из Telegram-бота и старых записей.';

-- Partial unique index: только строки с непустым client_id.
-- NULL не считается дублем NULL в Postgres, поэтому явный partial
-- WHERE client_id IS NOT NULL — это единственно корректный способ.
-- Обычный (не CONCURRENTLY) CREATE INDEX внутри транзакции допустим:
-- таблица care_history новая, объём строк пренебрежимо мал на момент
-- первого деплоя. Если в будущем потребуется добавить такой же индекс
-- на большую живую таблицу — делать отдельным файлом без транзакции
-- (CREATE INDEX CONCURRENTLY) и flyway.mixed=true.
CREATE UNIQUE INDEX IF NOT EXISTS idx_history_client_id
    ON care_history(client_id)
    WHERE client_id IS NOT NULL;

COMMIT;
