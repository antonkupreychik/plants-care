-- Issue #227: гостевой вход (POST /auth/guest + POST /auth/guest/convert).
-- Оба поля nullable — backward-compatible для rolling deploy.

ALTER TABLE users
    ADD COLUMN is_guest  BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN device_id VARCHAR(64) UNIQUE;

COMMENT ON COLUMN users.is_guest  IS 'TRUE для гостевых аккаунтов без email/social.';
COMMENT ON COLUMN users.device_id IS 'UUID устройства для идентификации гостя. NULL для обычных юзеров.';

CREATE INDEX idx_users_device_id ON users(device_id) WHERE device_id IS NOT NULL;
