-- =====================================================
-- V8: Пользовательские шаблоны растений (issue #68)
-- PostgreSQL + Flyway
-- =====================================================

CREATE TABLE IF NOT EXISTS plant_templates (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name        VARCHAR(40)  NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_plant_templates_name_not_blank CHECK (length(trim(name)) > 0)
);

CREATE INDEX IF NOT EXISTS idx_plant_templates_user_id
    ON plant_templates(user_id);

-- case-insensitive uniqueness per user (issue #68: дубли запрещены)
CREATE UNIQUE INDEX IF NOT EXISTS uq_plant_templates_user_lower_name
    ON plant_templates(user_id, lower(name));

COMMENT ON TABLE plant_templates IS
    'Пользовательские шаблоны растений (issue #68).';

-- -------------------------------------------------------

CREATE TABLE IF NOT EXISTS plant_template_care_rules (
    id            BIGSERIAL PRIMARY KEY,
    template_id   BIGINT      NOT NULL REFERENCES plant_templates(id) ON DELETE CASCADE,
    care_type     VARCHAR(32) NOT NULL,
    interval_days INT         NOT NULL,
    created_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_template_care_type UNIQUE (template_id, care_type),
    CONSTRAINT chk_template_care_type CHECK (care_type IN ('WATERING', 'MISTING', 'FERTILIZING')),
    CONSTRAINT chk_template_interval_days CHECK (interval_days BETWEEN 1 AND 365)
);

CREATE INDEX IF NOT EXISTS idx_template_care_rules_template_id
    ON plant_template_care_rules(template_id);

COMMENT ON TABLE plant_template_care_rules IS
    'Правила ухода шаблона: тип (WATERING/MISTING/FERTILIZING) + интервал в днях.';
