ALTER TABLE locations
DROP CONSTRAINT IF EXISTS uq_locations_user_name;

CREATE UNIQUE INDEX IF NOT EXISTS uq_locations_user_lower_name
    ON locations(user_id, lower(name));