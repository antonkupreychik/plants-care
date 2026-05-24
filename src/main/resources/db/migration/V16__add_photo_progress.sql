-- =====================================================
-- V16: Фото-прогресс растения (issue #72)
--
-- Юзер может включить периодические пуши «сделай фото» (раз в 2 недели
-- или раз в месяц), бот сохраняет фото в историю и собирает таймлайн
-- роста растения.
--
-- Состоит из двух частей:
--   1. Расширение plants — настройки частоты и анти-спам поля для шедулера.
--   2. Новая таблица plant_progress_photos — собственно история фото.
--
-- Backward-compat:
--   - photo_progress_frequency добавляется с DEFAULT 'OFF' и NOT NULL —
--     старый код, не знающий о колонке, продолжит работать, новые растения
--     заводятся с выключенным фото-прогрессом.
--   - next_photo_due_at / last_photo_prompt_sent_at — nullable, не мешают.
--   - plant_progress_photos — новая таблица, для существующего кода невидима.
-- =====================================================

-- 1) Настройки фото-прогресса на уровне растения.
ALTER TABLE plants
    ADD COLUMN IF NOT EXISTS photo_progress_frequency VARCHAR(8) NOT NULL DEFAULT 'OFF';

ALTER TABLE plants
    ADD COLUMN IF NOT EXISTS next_photo_due_at TIMESTAMP;

ALTER TABLE plants
    ADD COLUMN IF NOT EXISTS last_photo_prompt_sent_at TIMESTAMP;

ALTER TABLE plants
    ADD CONSTRAINT chk_plants_photo_progress_frequency
        CHECK (photo_progress_frequency IN ('OFF', 'P2W', 'P1M'));

COMMENT ON COLUMN plants.photo_progress_frequency IS
    'Частота пушей «обнови фото» (issue #72): OFF — выключено (default), '
        'P2W — раз в 2 недели, P1M — раз в месяц.';

COMMENT ON COLUMN plants.next_photo_due_at IS
    'Когда шедулер должен прислать следующий prompt «сделай фото». '
        'NULL если фото-прогресс выключен или ещё не запланирован.';

COMMENT ON COLUMN plants.last_photo_prompt_sent_at IS
    'Когда последний раз слали prompt — анти-спам, чтобы при сбоях шедулера '
        'не задолбать юзера повторами в течение короткого окна.';

-- Partial index под шедулер: «найди растения, которым пора слать фото-prompt».
-- Большинство растений с OFF → next_photo_due_at IS NULL, в индекс не лезут.
CREATE INDEX IF NOT EXISTS idx_plants_next_photo_due
    ON plants(next_photo_due_at)
    WHERE next_photo_due_at IS NOT NULL;


-- 2) История фото-прогресса.
CREATE TABLE IF NOT EXISTS plant_progress_photos (
    id                  BIGSERIAL PRIMARY KEY,
    plant_id            BIGINT       NOT NULL REFERENCES plants(id) ON DELETE CASCADE,
    user_id             BIGINT       NOT NULL REFERENCES users(id)  ON DELETE CASCADE,
    telegram_file_id    VARCHAR(255) NOT NULL,
    taken_at            TIMESTAMP    NOT NULL,
    caption             VARCHAR(500),
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Индекс для главного экрана истории «фото растения в обратном порядке».
CREATE INDEX IF NOT EXISTS idx_progress_photo_plant_taken
    ON plant_progress_photos(plant_id, taken_at DESC);

COMMENT ON TABLE plant_progress_photos IS
    'История фото-прогресса растения (issue #72). Не пересекается с '
        'plants.photo_file_id — там одна «обложка», тут таймлайн.';

COMMENT ON COLUMN plant_progress_photos.user_id IS
    'Денормализация: дублируется из plants.user_id для быстрых выборок '
        '«все фото юзера» без join (галерея, экспорт).';

COMMENT ON COLUMN plant_progress_photos.telegram_file_id IS
    'Telegram file_id, не URL. Получаем при загрузке фото юзером.';

COMMENT ON COLUMN plant_progress_photos.taken_at IS
    'Момент, когда фото попало в систему. Для v1 = время загрузки в бот; '
        'в будущем юзер сможет указать «фото из прошлого».';

COMMENT ON COLUMN plant_progress_photos.caption IS
    'Опциональная подпись юзера к фото (до 500 символов).';
