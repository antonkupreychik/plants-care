-- V26__add_species_toxicity_flags.sql
-- Флаги токсичности вида для бейджа в карточке растения (ADR-011, issue #130).
-- Версия V26 (не V24): V24/V25 параллельно заняты другими открытыми PR (#136, #140).
-- Тройное состояние значимо: TRUE — токсично, FALSE — безопасно, NULL — нет данных.
-- Backward-compat note: три nullable-колонки на существующую таблицу species — безопасно.
--   Старый код колонки не читает и не пишет, на старых строках значение остаётся NULL
--   ("нет данных") по дизайну. 3-шаговая процедура NOT NULL НЕ нужна и НЕ применяется.
--   Сидинг значений токсичности НЕ здесь (вне scope #130), только DDL.

BEGIN;

ALTER TABLE species
    ADD COLUMN IF NOT EXISTS toxic_to_cats   BOOLEAN,
    ADD COLUMN IF NOT EXISTS toxic_to_dogs   BOOLEAN,
    ADD COLUMN IF NOT EXISTS toxic_to_humans BOOLEAN;

COMMENT ON COLUMN species.toxic_to_cats IS
    'Токсичен для кошек. TRUE — токсично, FALSE — безопасно, NULL — нет данных (ADR-011).';
COMMENT ON COLUMN species.toxic_to_dogs IS
    'Токсичен для собак. TRUE — токсично, FALSE — безопасно, NULL — нет данных (ADR-011).';
COMMENT ON COLUMN species.toxic_to_humans IS
    'Токсичен для людей. TRUE — токсично, FALSE — безопасно, NULL — нет данных (ADR-011).';

COMMIT;
