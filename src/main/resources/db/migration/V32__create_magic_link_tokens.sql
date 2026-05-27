-- V23__create_magic_link_tokens.sql
-- Issue #88: мобильная аутентификация (ADR-011).
-- Одноразовые токены входа по email («magic link»). На запрос входа создаётся
-- строка с хешем токена и сроком жизни; при переходе по ссылке токен сверяется
-- по хешу, помечается consumed_at и больше не принимается.
--
-- Хранится ТОЛЬКО хеш токена (SHA-256), не сам токен — утечка таблицы не даёт
-- валидных ссылок.
--
-- Backward-compat note:
--   * Новая таблица — для старого кода невидима. Безопасно для rolling deploy.
--   * @Scheduled-чистка удаляет протухшие/использованные строки (по expires_at).

BEGIN;

CREATE TABLE magic_link_tokens (
    id          BIGSERIAL    PRIMARY KEY,
    email       VARCHAR(320) NOT NULL,
    token_hash  VARCHAR(64)  NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ  NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE magic_link_tokens IS
    'Одноразовые токены входа по email (magic link), issue #88. Хранится хеш токена.';
COMMENT ON COLUMN magic_link_tokens.email IS
    'Email, на который отправлена ссылка. На него привяжется/войдёт пользователь.';
COMMENT ON COLUMN magic_link_tokens.token_hash IS
    'SHA-256 хеш токена (hex/base64). Сам токен в БД не хранится. UNIQUE для lookup по хешу.';
COMMENT ON COLUMN magic_link_tokens.expires_at IS
    'Момент истечения токена (UTC). После — ссылка невалидна.';
COMMENT ON COLUMN magic_link_tokens.consumed_at IS
    'Момент использования токена (UTC). NULL — ещё не использован. Защита от повторного входа.';

-- Индекс под @Scheduled-чистку протухших токенов:
--   DELETE FROM magic_link_tokens WHERE expires_at < now()
CREATE INDEX idx_magic_link_tokens_expires_at
    ON magic_link_tokens (expires_at);

COMMIT;
