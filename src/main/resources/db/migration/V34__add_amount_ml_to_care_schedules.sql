-- V34__add_amount_ml_to_care_schedules.sql
-- Issue #185: объём полива (миллилитры) на расписание ухода.
--
-- Добавляем опциональный объём в care_schedules.amount_ml. Поле осмысленно
-- только для task_type = 'WATERING' (сколько воды лить). Для MISTING /
-- FERTILIZING / SOIL_CHECK и для всех существующих строк значение NULL —
-- «объём не задан». Это корректная семантика, бэкфил не нужен.
--
-- CHECK (amount_ml > 0) — в стиле уже имеющегося в этой таблице
-- chk_interval_positive: отрицательный/нулевой объём бессмысленен.
-- NULL допускается (IS NULL OR > 0), т.к. объём опционален.
--
-- Backward-compat note:
--   * Колонка NULLABLE без NOT NULL → старые INSERT в care_schedules,
--     не упоминающие amount_ml, остаются валидными. Один шаг, без бэкфила
--     и без отдельной NOT NULL-миграции.
--   * CHECK не затрагивает существующие строки (для них amount_ml = NULL).
--   * Индекс не добавляем: запросов с WHERE/ORDER BY по amount_ml нет,
--     поле читается вместе со строкой расписания.
--   * Безопасно для rolling deploy. Только DDL, без сидинга.

BEGIN;

ALTER TABLE care_schedules
    ADD COLUMN amount_ml INTEGER;

ALTER TABLE care_schedules
    ADD CONSTRAINT chk_care_schedules_amount_ml_positive
        CHECK (amount_ml IS NULL OR amount_ml > 0);

COMMENT ON COLUMN care_schedules.amount_ml IS
    'Объём полива в миллилитрах. Осмысленно только для task_type = WATERING. '
        'NULL — объём не задан (другие типы задач или старые записи до issue #185).';

COMMIT;
