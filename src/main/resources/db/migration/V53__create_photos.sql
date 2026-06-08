-- V53__create_photos.sql
-- Issue #90 (Slice A): REST-хранилище фото в S3-совместимом бакете (Railway).
--
-- Таблица метаданных фото. Сам бинарь лежит в object storage (S3-совместимый
-- бакет), здесь храним только namespaced ключ объекта (storage_key вида
-- photos/<uuid>), MIME-тип, размер и владельца.
--
-- Удаление — soft-delete: проставляем deleted_at, физический объект в бакете в
-- этом срезе не трогаем (чистку делает отдельная batch-задача, вне Slice A).
--
-- Backward-compat note:
--   * Новая таблица — для старого кода невидима (аддитивно, один релиз).
--   * id маппится JPA как GenerationType.IDENTITY → BIGSERIAL.
--   * created_at / deleted_at — TIMESTAMPTZ (UTC), как везде; маппятся в Instant.
--   * ON DELETE CASCADE на user_id: удаление пользователя убирает его фото-записи.
--   * Существующие plants.photo_file_id / plant_progress_photos.telegram_file_id
--     НЕ трогаем — миграция Telegram-фото это отдельный срез (Slice B).
--   * Только DDL, без сидинга. Безопасно для rolling deploy.

CREATE TABLE photos (
    id           BIGSERIAL    PRIMARY KEY,
    storage_key  VARCHAR(512) NOT NULL UNIQUE,
    content_type VARCHAR(128) NOT NULL,
    size_bytes   BIGINT       NOT NULL,
    user_id      BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at   TIMESTAMPTZ
);

COMMENT ON TABLE  photos              IS 'Метаданные фото в S3-совместимом бакете (issue #90). Бинарь — в object storage, не в БД.';
COMMENT ON COLUMN photos.storage_key  IS 'Namespaced-ключ объекта в бакете, например photos/<uuid>.';
COMMENT ON COLUMN photos.content_type IS 'MIME-тип загруженного фото (image/...).';
COMMENT ON COLUMN photos.size_bytes   IS 'Размер объекта в байтах.';
COMMENT ON COLUMN photos.user_id      IS 'Владелец фото (users.id).';
COMMENT ON COLUMN photos.deleted_at   IS 'Момент soft-delete; NULL — запись активна.';

-- Выборка активных фото пользователя (GET/DELETE: findByIdAndUserIdAndDeletedAtIsNull).
CREATE INDEX idx_photos_user_active ON photos (user_id) WHERE deleted_at IS NULL;
