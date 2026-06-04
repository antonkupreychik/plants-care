-- Issue #175: user_devices — хранилище device-токенов для push-уведомлений (ADR-014).
-- Регистрация и отвязка устройств; FCM-интеграция — отдельная задача.

CREATE TABLE user_devices (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT       NOT NULL
                     REFERENCES users(id) ON DELETE CASCADE,
    platform     VARCHAR(16)  NOT NULL,
    push_token   VARCHAR(512) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_devices_user_push_token UNIQUE (user_id, push_token)
);

CREATE INDEX idx_user_devices_user_id ON user_devices(user_id);
