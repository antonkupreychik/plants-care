-- =====================================================
-- V9: Soil check как отдельный тип задачи ухода (issue #74)
--
-- 1. Расширяем chk_task_type CHECK-констрейнты на трёх таблицах
--    (care_schedules, care_history, notifications_log) — добавляем 'SOIL_CHECK'.
-- 2. Опциональное поле species.soil_check_days — резерв под per-species дефолты.
--    На v1 не используется: всегда берём хардкод 3 дня.
-- =====================================================

-- -------------------------------------------------------
-- care_schedules
-- -------------------------------------------------------
ALTER TABLE care_schedules
    DROP CONSTRAINT IF EXISTS chk_task_type;

ALTER TABLE care_schedules
    ADD CONSTRAINT chk_task_type
        CHECK (task_type IN ('WATERING', 'MISTING', 'FERTILIZING', 'SOIL_CHECK'));

COMMENT ON COLUMN care_schedules.task_type IS
    'WATERING / MISTING / FERTILIZING / SOIL_CHECK';

-- -------------------------------------------------------
-- care_history
-- -------------------------------------------------------
ALTER TABLE care_history
    DROP CONSTRAINT IF EXISTS chk_history_task_type;

ALTER TABLE care_history
    ADD CONSTRAINT chk_history_task_type
        CHECK (task_type IN ('WATERING', 'MISTING', 'FERTILIZING', 'SOIL_CHECK'));

COMMENT ON COLUMN care_history.note IS
    'Свободный текст до 500 символов. Для SOIL_CHECK записываем результат: '
        '"soil:DRY" | "soil:WET" | "soil:UNKNOWN".';

-- -------------------------------------------------------
-- notifications_log
-- -------------------------------------------------------
ALTER TABLE notifications_log
    DROP CONSTRAINT IF EXISTS chk_notif_task_type;

ALTER TABLE notifications_log
    ADD CONSTRAINT chk_notif_task_type
        CHECK (task_type IN ('WATERING', 'MISTING', 'FERTILIZING', 'SOIL_CHECK'));

-- -------------------------------------------------------
-- species.soil_check_days — резерв, пока всегда NULL.
-- Если когда-нибудь захотим per-species дефолт — заполним сидингом.
-- -------------------------------------------------------
ALTER TABLE species
    ADD COLUMN IF NOT EXISTS soil_check_days INT;

COMMENT ON COLUMN species.soil_check_days IS
    'Интервал по умолчанию для SOIL_CHECK (дней). NULL — использовать общий хардкод 3.';
