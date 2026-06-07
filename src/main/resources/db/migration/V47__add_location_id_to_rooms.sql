-- =====================================================
-- V47: Комнаты в локации (issue #283)
--   Добавляем nullable location_id в rooms (FK -> locations)
--   для привязки комнаты к конкретной локации пользователя.
--   Nullable для backward-compat: существующие записи rooms (из Telegram-бота)
--   не имеют локации — их location_id останется NULL пока не будет задан явно.
-- =====================================================

-- Добавляем колонку если её нет
ALTER TABLE rooms
    ADD COLUMN IF NOT EXISTS location_id BIGINT;

-- Гарантируем nullable (на случай, если колонка уже была добавлена как NOT NULL
-- в другом окружении или более ранней версии миграции)
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'rooms'
          AND column_name = 'location_id'
          AND is_nullable = 'NO'
    ) THEN
        ALTER TABLE rooms ALTER COLUMN location_id DROP NOT NULL;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_rooms_location'
          AND conrelid = 'rooms'::regclass
    ) THEN
        ALTER TABLE rooms
            ADD CONSTRAINT fk_rooms_location
                FOREIGN KEY (location_id)
                    REFERENCES locations(id)
                    ON DELETE SET NULL
            NOT VALID;
    END IF;
END $$;

COMMENT ON COLUMN rooms.location_id IS
    'Локация, к которой принадлежит комната (issue #283). NULL = не привязана (старые записи Telegram-бота).';

CREATE INDEX IF NOT EXISTS idx_rooms_location_id
    ON rooms(location_id)
    WHERE location_id IS NOT NULL;
