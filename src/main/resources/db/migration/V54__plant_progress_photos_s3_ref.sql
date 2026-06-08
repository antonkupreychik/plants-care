-- V54__plant_progress_photos_s3_ref.sql
-- Issue #253: REST API фото-прогресса растения.
--
-- Бот пишет в plant_progress_photos.telegram_file_id (Telegram file_id).
-- REST-загрузка кладёт бинарь в S3-совместимый бакет (issue #90, таблица photos)
-- и у неё нет telegram_file_id. Чтобы один таймлайн обслуживал оба источника:
--
--   1. Ослабляем NOT NULL на telegram_file_id (бот продолжит писать своё
--      значение — для него ничего не меняется; REST оставляет колонку NULL).
--   2. Добавляем nullable photo_id — FK на photos(id) из #90, переиспользует
--      Photo-сущность и presigned-URL.
--   3. CHECK-инвариант: ровно один источник — либо telegram_file_id, либо
--      photo_id — должен быть заполнен (исключаем «оба пусты» и «оба заданы»).
--
-- Backward-compat:
--   * DROP NOT NULL — ослабление, безопасно для rolling deploy (старый код,
--     всегда передающий telegram_file_id, продолжает работать).
--   * photo_id — nullable, для старого кода невидим.
--   * CHECK добавляется поверх существующих строк: у них telegram_file_id
--     заполнен, photo_id IS NULL — инвариант выполняется, валидация пройдёт.
--   * ON DELETE SET NULL: удаление фото из photos (soft-delete не трогает строку,
--     но физическая чистка в Slice B+ может) не должно ломать таймлайн —
--     запись остаётся, ссылка обнуляется. Инвариант при этом мог бы нарушиться
--     для чисто-S3 записи, но физический DELETE фото в #90 пока не делается;
--     SET NULL выбран как наименее разрушительный для истории.

ALTER TABLE plant_progress_photos
    ALTER COLUMN telegram_file_id DROP NOT NULL;

ALTER TABLE plant_progress_photos
    ADD COLUMN photo_id BIGINT REFERENCES photos(id) ON DELETE SET NULL;

ALTER TABLE plant_progress_photos
    ADD CONSTRAINT chk_progress_photo_source
        CHECK (
            (telegram_file_id IS NOT NULL AND photo_id IS NULL)
            OR (telegram_file_id IS NULL AND photo_id IS NOT NULL)
        );

-- Выборка фото таймлайна с присоединением S3-метаданных (REST history/compare).
CREATE INDEX idx_progress_photo_photo_id
    ON plant_progress_photos (photo_id)
    WHERE photo_id IS NOT NULL;

COMMENT ON COLUMN plant_progress_photos.telegram_file_id IS
    'Telegram file_id (бот-источник). NULL, если фото загружено через REST в S3 '
        '(тогда заполнен photo_id). Инвариант chk_progress_photo_source: ровно один источник.';

COMMENT ON COLUMN plant_progress_photos.photo_id IS
    'FK на photos(id) из issue #90 — S3-источник фото (REST-загрузка). NULL для '
        'бот-фото (тогда заполнен telegram_file_id).';
