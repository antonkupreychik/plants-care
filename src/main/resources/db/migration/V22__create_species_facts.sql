-- V22__create_species_facts.sql
-- Энциклопедический контент по видам растений (ADR-011, issue #129).
-- Хранит факты о видах: происхождение, уход, токсичность, любопытные факты.
-- Backward-compat note: новая таблица, на старых строках бэкфилл не нужен,
--   старый код её не читает и не пишет — NOT NULL колонки безопасны.
--   Сидинг данных НЕ здесь (issue #132), только DDL.

BEGIN;

CREATE TABLE species_facts (
    id              BIGSERIAL PRIMARY KEY,
    species_id      BIGINT       NOT NULL REFERENCES species(id) ON DELETE CASCADE,
    category        VARCHAR(24)  NOT NULL,
    title           VARCHAR(160),
    body            TEXT         NOT NULL,
    source          VARCHAR(300),
    display_order   INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_species_facts_category
        CHECK (category IN ('ORIGIN', 'CARE', 'TOXICITY', 'CURIOSITY'))
);

COMMENT ON TABLE species_facts IS 'Энциклопедические факты по видам (ADR-011). Сид в issue #132.';
COMMENT ON COLUMN species_facts.category IS 'ORIGIN / CARE / TOXICITY / CURIOSITY. Новая категория = правка CHECK + новая миграция.';
COMMENT ON COLUMN species_facts.title IS 'Опциональный заголовок факта; NULL если факт без заголовка.';
COMMENT ON COLUMN species_facts.source IS 'Опциональная ссылка/атрибуция источника.';
COMMENT ON COLUMN species_facts.display_order IS 'Порядок показа фактов внутри (species_id, category).';

-- Выборка фактов вида в порядке показа (покрывает FK species_id).
CREATE INDEX idx_species_facts_species_category_order
    ON species_facts (species_id, category, display_order);

-- Полнотекстовый GIN-индекс намеренно НЕ создаётся: searchByQuery де-коррелирует
-- подзапрос в hashed SubPlan + Seq Scan, GIN не задействуется (EXPLAIN ANALYZE на PG16,
-- 5000 видов / 20000 фактов). species_facts — маленькая кураторская таблица (~30 видов),
-- seq scan дешёвый. Индекс был бы чистым оверхедом на запись без выгоды.

-- Автообновление updated_at — переиспользуем функцию из V1.
CREATE TRIGGER trg_species_facts_updated_at BEFORE UPDATE ON species_facts
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

COMMIT;
