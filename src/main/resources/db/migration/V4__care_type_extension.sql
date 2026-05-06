-- =====================================================
-- V4: Расширение типов ухода (spray & fertilize)
--
-- 1. care_history:   добавляем cancelled_by для иммутабельной
--                    компенсирующей отмены (резерв под Этап 3)
-- 2. care_schedules: добавляем schedule_rules JSONB для сезонных
--                    интервалов (резерв под Этап 3, пока не используется)
-- =====================================================

-- -------------------------------------------------------
-- care_history: поле компенсирующей записи
-- NULL  → запись активна
-- NOT NULL → запись отменена, значение указывает на компенсирующую
-- -------------------------------------------------------
ALTER TABLE care_history
    ADD COLUMN cancelled_by BIGINT
        REFERENCES care_history(id)
            ON DELETE SET NULL;

CREATE INDEX idx_history_cancelled_by ON care_history(cancelled_by)
    WHERE cancelled_by IS NOT NULL;

COMMENT ON COLUMN care_history.cancelled_by IS
    'FK на компенсирующую запись. История иммутабельна: удаление/правка запрещены. '
        'Кнопка отмены в UI — Этап 3. Поле готово уже сейчас.';

-- -------------------------------------------------------
-- care_schedules: поле сезонных правил
-- Формат (Этап 3):
--   { "rules": [
--       { "season": "winter", "interval_days": 30 },
--       { "season": "summer", "interval_days": 7  }
--   ]}
-- На Этапе 2 поле NULL для всех строк — логика расчёта
-- next_due_at использует только interval_days.
-- -------------------------------------------------------
ALTER TABLE care_schedules
    ADD COLUMN schedule_rules JSONB;

COMMENT ON COLUMN care_schedules.schedule_rules IS
    'Сезонные интервалы — резерв под Этап 3. '
        'Пример: {"rules":[{"season":"winter","interval_days":30},{"season":"summer","interval_days":7}]}. '
        'NULL пока не используется: next_due_at считается только через interval_days.';