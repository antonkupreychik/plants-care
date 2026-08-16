-- V58__admin_photo_storage.sql
-- Issue #101: админское управление хранилищем фото.
--
-- ВАЖНО про расхождение со спекой: issue написана под Cloudflare R2, но проект
-- в итоге поехал на S3-совместимое хранилище Railway через AWS SDK v2
-- (issue #90 Slice A, V53/V54). Схема здесь — под фактическое S3.
--
-- Две вещи:
--
-- 1. photos.purged_at — момент ФИЗИЧЕСКОГО удаления объекта из бакета.
--    В #90 удаление было только soft (deleted_at), объект в бакете оставался
--    навсегда. Здесь появляется retention: спустя N дней после soft-delete
--    отложенная задача удаляет объект из S3 и проставляет purged_at.
--
--    Строку photos при этом НЕ удаляем — намеренно. plant_progress_photos.photo_id
--    имеет ON DELETE SET NULL (V54), а CHECK chk_progress_photo_source требует
--    ровно один непустой источник. Физический DELETE строки photos обнулил бы
--    photo_id у чисто-S3 записи таймлайна и уронил бы CHECK. Поэтому запись
--    photos остаётся тумбстоуном: purged_at != NULL значит «бинаря в бакете нет».
--
-- 2. storage_metrics — суточный снапшот объёма для графика роста на /admin/storage.
--    Считается по таблице photos (объекты, физически лежащие в бакете, т.е.
--    purged_at IS NULL), а не листингом бакета: листинг стоит API-вызовов и
--    требует живого S3 в тестах, а photos и есть реестр того, что мы туда клали.
--
-- Backward-compat:
--   * purged_at — nullable, аддитивно; старый код (PhotoService из #90) её не
--     видит и продолжает работать. Безопасно для rolling deploy.
--   * storage_metrics — новая таблица, для старого кода невидима.
--   * Только DDL, без сидинга.

ALTER TABLE photos
    ADD COLUMN purged_at TIMESTAMPTZ;

COMMENT ON COLUMN photos.purged_at IS
    'Момент физического удаления объекта из бакета (issue #101). NULL — бинарь ещё в S3. '
        'Строка photos не удаляется никогда: на неё ссылается plant_progress_photos.photo_id.';

-- Кандидаты на физическую чистку: soft-deleted, но ещё не purged.
-- Частичный индекс — таких строк единицы на фоне всей таблицы.
CREATE INDEX idx_photos_purge_due
    ON photos (deleted_at)
    WHERE deleted_at IS NOT NULL AND purged_at IS NULL;

-- Агрегаты /admin/storage считают «что лежит в бакете» = purged_at IS NULL.
CREATE INDEX idx_photos_in_bucket
    ON photos (created_at)
    WHERE purged_at IS NULL;

CREATE TABLE storage_metrics (
    metric_date  DATE        PRIMARY KEY,
    total_bytes  BIGINT      NOT NULL,
    total_count  BIGINT      NOT NULL,
    collected_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE  storage_metrics              IS 'Суточный снапшот объёма фото в бакете (issue #101). Источник графика роста на /admin/storage.';
COMMENT ON COLUMN storage_metrics.metric_date  IS 'Дата снапшота (UTC). PK — один снапшот в сутки, повторный запуск перезаписывает (UPSERT).';
COMMENT ON COLUMN storage_metrics.total_bytes  IS 'Суммарный размер объектов, физически лежащих в бакете (photos.purged_at IS NULL).';
COMMENT ON COLUMN storage_metrics.total_count  IS 'Количество объектов, физически лежащих в бакете.';
COMMENT ON COLUMN storage_metrics.collected_at IS 'Когда снапшот фактически посчитан.';
