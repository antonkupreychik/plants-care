-- V18__add_users_calendar_token.sql
-- Issue #79: per-user calendar (.ics) export.
-- Add a nullable opaque token used to resolve a user from a public calendar URL
-- (GET /calendar/{token}.ics). Token is generated lazily on first export request
-- and persisted; until then the column stays NULL.
--
-- Backward-compat note:
--   * Column is NULLABLE with no DEFAULT -> existing INSERTs into users keep working
--     without touching this column. Old code that does not know about
--     calendar_token continues to function unchanged.
--   * Reads from users by old code are unaffected (no SELECT * contracts changed
--     in a breaking way; column is additive).
--   * Safe for rolling deploy: this migration can be applied before the new
--     application version is rolled out.

BEGIN;

ALTER TABLE users
    ADD COLUMN calendar_token VARCHAR(64);

COMMENT ON COLUMN users.calendar_token IS
    'Opaque random token for public .ics calendar URL. NULL until first calendar export request; generated lazily by the service. Unique when set.';

-- Partial unique index:
--   * uniqueness only enforced for non-NULL tokens (most users will have NULL
--     until they explicitly request their calendar URL);
--   * primary lookup: SELECT user_id FROM users WHERE calendar_token = ?
--     during /calendar/{token}.ics resolution.
CREATE UNIQUE INDEX idx_users_calendar_token
    ON users (calendar_token)
    WHERE calendar_token IS NOT NULL;

COMMIT;
