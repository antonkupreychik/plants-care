-- =====================================================
-- Rollback for V5/V6: Add locations
-- PostgreSQL
-- =====================================================

DROP INDEX IF EXISTS idx_plants_user_location;
DROP INDEX IF EXISTS uq_locations_user_lower_name;
DROP INDEX IF EXISTS uq_locations_one_default_per_user;
DROP INDEX IF EXISTS idx_locations_user_default;
DROP INDEX IF EXISTS idx_locations_user_id;

ALTER TABLE plants
DROP CONSTRAINT IF EXISTS fk_plants_location;

ALTER TABLE plants
DROP COLUMN IF EXISTS location_id;

DROP TABLE IF EXISTS locations;