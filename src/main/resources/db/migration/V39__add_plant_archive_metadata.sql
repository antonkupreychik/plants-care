-- V39__add_plant_archive_metadata.sql
-- Issue #219 (mobile G15): метаданные выбытия растения для экрана «Архив».
--
-- Колонка archived_at уже есть (используется как soft-delete с самого начала).
-- Не хватало причины выбытия: было ли растение подарено (gifted = true) или
-- погибло (gifted = false), и свободной заметки пользователя.
--
-- Добавляем два опциональных поля:
--   * archive_gifted  — BOOLEAN: true = подарили, false = погибло. NULL до архивации.
--   * archive_note    — TEXT: свободная заметка о выбытии. NULL — не задана.
--
-- Backward-compat note:
--   * Обе колонки NULLABLE без NOT NULL → существующие строки plants остаются
--     валидными, бэкфил не нужен (CLAUDE.md: nullable без 3-шагового бэкфила).
--   * Семантика: поля осмысленны только для архивных растений
--     (archived_at IS NOT NULL); при восстановлении (restore) сбрасываются вместе
--     с archived_at кодом сервиса.
--   * Индекс не добавляем: фильтрации/сортировки по этим полям нет, читаются
--     вместе со строкой растения.
--   * Безопасно для rolling deploy. Только DDL, без сидинга.

BEGIN;

ALTER TABLE plants
    ADD COLUMN archive_gifted BOOLEAN,
    ADD COLUMN archive_note   TEXT;

COMMENT ON COLUMN plants.archive_gifted IS
    'Причина выбытия растения (issue #219): true — подарили, false — погибло. '
        'NULL пока растение активно (archived_at IS NULL).';

COMMENT ON COLUMN plants.archive_note IS
    'Свободная заметка о выбытии растения (issue #219), напр. «Залила соседка». '
        'NULL — не задана. Сбрасывается при восстановлении из архива.';

COMMIT;
