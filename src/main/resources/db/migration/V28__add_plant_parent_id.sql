-- V28__add_plant_parent_id.sql
-- Родословная растений: self-FK plants.parent_id → plants.id (ADR-012, issue #139).
--
-- Растение может быть отростком (черенком) другого растения того же пользователя.
-- parent_id ссылается на материнское растение. Денормализованный user_id уже есть
-- на plants (V1) и здесь НЕ добавляется — scope только parent_id + индекс.
--
-- ON DELETE SET NULL: при удалении материнского растения отросток не удаляется,
-- а просто теряет связь (становится «корнем» родословной) — как room_id/species_id в V1.
--
-- Backward-compat note:
--   * Колонка NULLABLE, без DEFAULT и без NOT NULL — старый код её не читает и не пишет,
--     на существующих строках остаётся NULL. Аддитивно, один релиз, бэкфил НЕ нужен.
--   * 3-шаговая процедура NOT NULL НЕ применяется и НЕ нужна.
--   * Индекс на FK-колонку — Postgres не создаёт его автоматически.
--   * Безопасно для rolling deploy. Только DDL, без сидинга.

BEGIN;

ALTER TABLE plants
    ADD COLUMN parent_id BIGINT REFERENCES plants(id) ON DELETE SET NULL;

COMMENT ON COLUMN plants.parent_id IS
    'Материнское растение, от которого получен отросток (self-FK, ADR-012). NULL — корень родословной.';

-- Индекс под выборку отростков по родителю и под сам FK (Postgres не индексирует FK автоматом).
CREATE INDEX idx_plants_parent_id ON plants(parent_id);

COMMIT;
