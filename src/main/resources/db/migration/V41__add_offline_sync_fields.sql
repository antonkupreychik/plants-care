-- V41: Поля для офлайн-синхронизации мобильного приложения (issue #91).
--
-- Добавляем:
--   1. updated_at TIMESTAMPTZ в таблицы, где его ещё нет (locations, care_history, plant_progress_photos)
--   2. deleted_at TIMESTAMPTZ в plants и locations (soft-delete для sync /deletions)
--   3. client_id VARCHAR(64) в plants и locations (идемпотентность на стороне клиента)
--
-- plants.updated_at уже существует (V1), plants.archived_at — soft-delete через архивацию;
-- чтобы не ломать архивную логику, добавляем deleted_at как отдельный признак физического удаления
-- в рамках синка. На данный момент archived_at продолжает использоваться, а deleted_at = NULL.
--
-- Backward-compat:
--   - Все новые колонки nullable (кроме updated_at у которого есть дефолт).
--   - Добавление nullable колонки безопасно: старый код (Telegram-бот) не знает о них и не ломается.
--   - Триггер update_updated_at_column() уже объявлен в V1 — переиспользуем.

BEGIN;

-- ===========================================================
-- 1. locations: добавить updated_at и deleted_at
-- ===========================================================
ALTER TABLE locations
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ NULL;

CREATE INDEX IF NOT EXISTS idx_locations_updated_at ON locations(updated_at);

-- Триггер для auto-update updated_at на locations
CREATE TRIGGER trg_locations_updated_at
    BEFORE UPDATE ON locations
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ===========================================================
-- 2. care_history: добавить updated_at
-- ===========================================================
ALTER TABLE care_history
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX IF NOT EXISTS idx_care_history_updated_at ON care_history(updated_at);

-- Триггер для auto-update updated_at на care_history
CREATE TRIGGER trg_care_history_updated_at
    BEFORE UPDATE ON care_history
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ===========================================================
-- 3. plant_progress_photos: добавить updated_at
-- ===========================================================
ALTER TABLE plant_progress_photos
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX IF NOT EXISTS idx_plant_progress_photos_updated_at ON plant_progress_photos(updated_at);

-- ===========================================================
-- 4. plants: добавить deleted_at и client_id
--    (updated_at уже есть с V1)
-- ===========================================================
ALTER TABLE plants
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS client_id  VARCHAR(64) NULL;

CREATE INDEX IF NOT EXISTS idx_plants_updated_at ON plants(updated_at);

CREATE UNIQUE INDEX IF NOT EXISTS idx_plants_client_id
    ON plants(client_id)
    WHERE client_id IS NOT NULL;

-- ===========================================================
-- 5. locations: добавить client_id
-- ===========================================================
ALTER TABLE locations
    ADD COLUMN IF NOT EXISTS client_id VARCHAR(64) NULL;

CREATE UNIQUE INDEX IF NOT EXISTS idx_locations_client_id
    ON locations(client_id)
    WHERE client_id IS NOT NULL;

COMMIT;
