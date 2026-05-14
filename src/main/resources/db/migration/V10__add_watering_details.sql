-- =====================================================
-- V10: Расширенный полив — обильность и сухость грунта (issue #71)
--
-- При отметке «Полил» юзер теперь отвечает на 2 вопроса:
--   1. Обильно полил? (HEAVY / NORMAL)
--   2. Земля была сухая до полива? (DRY / WET / UNKNOWN)
--
-- Оба поля nullable: для bulk-полива (issue #19), для MISTING/FERTILIZING
-- и для исторических записей до миграции значения остаются NULL.
--
-- В v1 не используется для адаптации интервала/рекомендации — только сбор
-- данных для аналитики и будущих фич.
-- =====================================================

ALTER TABLE care_history
    ADD COLUMN IF NOT EXISTS was_abundant BOOLEAN;

ALTER TABLE care_history
    ADD COLUMN IF NOT EXISTS soil_was_dry BOOLEAN;

COMMENT ON COLUMN care_history.was_abundant IS
    'Полил обильно (true) или обычно (false). NULL — деталь не указана '
        '(bulk-полив, MISTING/FERTILIZING, или старая запись до issue #71).';

COMMENT ON COLUMN care_history.soil_was_dry IS
    'Земля была сухая до полива (true) или нет (false). NULL — деталь '
        'не указана / "не знаю" / не WATERING. Не путать с care_history.note '
        'для SOIL_CHECK (issue #74) — там результат отдельной проверки грунта.';

-- Индекс на (plant_id, was_abundant) и (plant_id, soil_was_dry) пока не делаем:
-- запросов «найди все обильные поливы растения» в v1 нет. Когда появится
-- аналитический экран — добавим точечный индекс в следующей миграции.
