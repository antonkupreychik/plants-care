-- =====================================================
-- V48: Гарантируем nullable location_id в rooms (issue #283)
--   Если V47 была применена в окружении, где location_id был создан
--   как NOT NULL (ошибка в ранней версии), исправляем это.
--   В чистых окружениях — no-op.
-- =====================================================
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
