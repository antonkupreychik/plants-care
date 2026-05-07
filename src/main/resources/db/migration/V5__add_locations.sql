-- =====================================================
-- V4: Add locations for grouping plants by rooms / places
-- PostgreSQL + Flyway
-- =====================================================

CREATE TABLE IF NOT EXISTS locations (
                                         id BIGSERIAL PRIMARY KEY,
                                         user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                                         name VARCHAR(30) NOT NULL,
                                         emoji VARCHAR(16),
                                         is_default BOOLEAN NOT NULL DEFAULT FALSE,
                                         created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

                                         CONSTRAINT uq_locations_user_name UNIQUE (user_id, name),
                                         CONSTRAINT chk_locations_name_not_blank CHECK (length(trim(name)) > 0)
);

CREATE INDEX IF NOT EXISTS idx_locations_user_id
    ON locations(user_id);

CREATE INDEX IF NOT EXISTS idx_locations_user_default
    ON locations(user_id, is_default);

CREATE UNIQUE INDEX IF NOT EXISTS uq_locations_one_default_per_user
    ON locations(user_id)
    WHERE is_default = TRUE;

INSERT INTO locations (user_id, name, emoji, is_default)
SELECT u.id, 'Мои растения', '🪴', TRUE
FROM users u
WHERE NOT EXISTS (
    SELECT 1
    FROM locations l
    WHERE l.user_id = u.id
      AND l.is_default = TRUE
);

ALTER TABLE plants
    ADD COLUMN IF NOT EXISTS location_id BIGINT;

UPDATE plants p
SET location_id = l.id
FROM locations l
WHERE l.user_id = p.user_id
  AND l.is_default = TRUE
  AND p.location_id IS NULL;

ALTER TABLE plants
    ALTER COLUMN location_id SET NOT NULL;

DO $$
    BEGIN
        IF NOT EXISTS (
            SELECT 1
            FROM information_schema.table_constraints
            WHERE table_schema = 'public'
              AND table_name = 'plants'
              AND constraint_name = 'fk_plants_location'
        ) THEN
            ALTER TABLE plants
                ADD CONSTRAINT fk_plants_location
                    FOREIGN KEY (location_id)
                        REFERENCES locations(id)
                        ON DELETE RESTRICT;
        END IF;
    END $$;

CREATE INDEX IF NOT EXISTS idx_plants_user_location
    ON plants(user_id, location_id)
    WHERE archived_at IS NULL;

COMMENT ON TABLE locations IS 'Комнаты / локации пользователя: кухня, балкон, офис и т.д.';
COMMENT ON COLUMN locations.is_default IS 'Дефолтная локация пользователя. У пользователя должна быть ровно одна.';
COMMENT ON COLUMN plants.location_id IS 'Обязательная локация растения';