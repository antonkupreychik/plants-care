-- V24__create_revoked_refresh_tokens.sql
-- Issue #88: мобильная аутентификация (ADR-011).
-- Чёрный список отозванных/ротированных refresh-JWT по jti. При ротации или
-- логауте jti старого refresh-токена попадает сюда; при предъявлении refresh
-- сервис проверяет, что его jti не в списке.
--
-- Запись живёт до момента истечения самого refresh-токена (expires_at) — после
-- этого блокировать его уже не нужно, токен и так невалиден по сроку. @Scheduled
-- удаляет просроченные записи, чтобы таблица не росла бесконечно.
--
-- Backward-compat note:
--   * Новая таблица — для старого кода невидима. Безопасно для rolling deploy.

BEGIN;

CREATE TABLE revoked_refresh_tokens (
    jti        UUID        PRIMARY KEY,
    expires_at TIMESTAMPTZ NOT NULL
);

COMMENT ON TABLE revoked_refresh_tokens IS
    'Blacklist отозванных/ротированных refresh-JWT по jti (issue #88).';
COMMENT ON COLUMN revoked_refresh_tokens.jti IS
    'JWT id отозванного refresh-токена. PK — проверка наличия = проверка отзыва.';
COMMENT ON COLUMN revoked_refresh_tokens.expires_at IS
    'Срок жизни отозванного токена (UTC). После — запись чистится @Scheduled, '
        'т.к. токен уже невалиден по сроку.';

-- Индекс под @Scheduled-чистку по TTL:
--   DELETE FROM revoked_refresh_tokens WHERE expires_at < now()
CREATE INDEX idx_revoked_refresh_tokens_expires_at
    ON revoked_refresh_tokens (expires_at);

COMMIT;
