-- =====================================================
-- V43: Мультидомность (issue #70)
--   1. locations.paused_until — пауза уведомлений по локации
--   2. locations.archived_at  — soft-delete локации
--   3. users.active_location_id — «текущая» локация пользователя
-- =====================================================

-- 1. Добавляем paused_until и archived_at на локации
ALTER TABLE locations
    ADD COLUMN IF NOT EXISTS paused_until TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS archived_at  TIMESTAMPTZ;

COMMENT ON COLUMN locations.paused_until IS
    'Если задано — уведомления по растениям этой локации не шлются до этого момента';
COMMENT ON COLUMN locations.archived_at IS
    'Soft-delete: локация архивирована, не показывается в основном списке';

-- 2. Добавляем active_location_id в users (nullable — для backward-compat)
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS active_location_id BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_users_active_location'
          AND conrelid = 'users'::regclass
    ) THEN
        ALTER TABLE users
            ADD CONSTRAINT fk_users_active_location
                FOREIGN KEY (active_location_id)
                    REFERENCES locations(id)
                    ON DELETE SET NULL
            NOT VALID;
    END IF;
END $$;

-- Индекс для быстрого нахождения пользователей с данной активной локацией
CREATE INDEX IF NOT EXISTS idx_users_active_location_id
    ON users(active_location_id)
    WHERE active_location_id IS NOT NULL;

-- Индекс для поиска незаархивированных локаций пользователя
CREATE INDEX IF NOT EXISTS idx_locations_user_not_archived
    ON locations(user_id)
    WHERE archived_at IS NULL;

-- 3. Заполняем active_location_id дефолтной локацией (если она есть)
UPDATE users u
SET active_location_id = l.id
FROM locations l
WHERE l.user_id = u.id
  AND l.is_default = TRUE
  AND u.active_location_id IS NULL;
