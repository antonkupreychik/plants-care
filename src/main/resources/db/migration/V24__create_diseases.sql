-- V24__create_diseases.sql
-- База типичных болезней и вредителей комнатных растений (ADR-013, issue #140).
-- Справочник для диагностики (#73): по симптомам подбираем вероятные болезни,
-- показываем лечение и профилактику.
--
-- Backward-compat note:
--   * Новая таблица — для старого кода невидима (аддитивно, один релиз).
--   * NOT NULL колонки безопасны: на старых строках бэкфилл не нужен, таблица пустая.
--   * Сидинг данных НЕ здесь — отдельной миграцией V25 (правило проекта).
--   * Безопасно для rolling deploy.

BEGIN;

CREATE TABLE diseases (
    id            BIGSERIAL    PRIMARY KEY,
    name          VARCHAR(100) NOT NULL UNIQUE,
    latin_name    VARCHAR(150),
    symptoms      TEXT         NOT NULL,
    treatment     TEXT         NOT NULL,
    prevention    TEXT         NOT NULL,
    symptom_codes TEXT,
    search_tags   TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE diseases IS
    'Справочник типичных болезней/вредителей комнатных растений (ADR-013, issue #140). '
        'Используется в диагностике (#73). Сид в V25.';
COMMENT ON COLUMN diseases.name IS 'Русское название болезни/вредителя, уникальное. Например, "Паутинный клещ".';
COMMENT ON COLUMN diseases.latin_name IS 'Латинское/научное название; NULL для неинфекционных проблем (хлороз, ожог и т.п.).';
COMMENT ON COLUMN diseases.symptoms IS 'Описание симптомов (1-3 предложения), как распознать.';
COMMENT ON COLUMN diseases.treatment IS 'Что делать: меры лечения/борьбы.';
COMMENT ON COLUMN diseases.prevention IS 'Как избежать: профилактика.';
COMMENT ON COLUMN diseases.symptom_codes IS
    'CSV из кодов enum DiagnosisSymptom для матчинга в диагностике (#73): '
        'yellow_leaves, wilting_leaves, brown_dry_tips, leaf_spots, pests_or_web, other. '
        'NULL допустим. Новый код = правка enum, а не схемы.';
COMMENT ON COLUMN diseases.search_tags IS 'Ключевые слова для FTS через пробел (RU + синонимы). NULL допустим.';

-- Полнотекстовый поиск по болезни (по образцу species, V1).
-- Покрывает поиск пользователя по названию/симптомам/ключевым словам.
CREATE INDEX idx_diseases_search ON diseases
    USING gin(to_tsvector('simple',
        coalesce(name, '') || ' ' || coalesce(search_tags, '') || ' ' || coalesce(symptoms, '')));

COMMIT;
