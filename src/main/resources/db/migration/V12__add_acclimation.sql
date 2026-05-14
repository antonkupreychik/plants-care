-- =====================================================
-- V12: Режим акклиматизации новых растений (issue #75)
--
-- Период (по умолчанию 21 день) после добавления растения, когда бот
-- ведёт себя бережнее: мягкий текст пушей полива, опрос «проверь грунт»
-- вместо прямого «полил», периодические check-in вопросы «как растение?».
--
-- Не меняет интервалы расписаний — лишь модулирует UX уведомлений.
-- =====================================================

ALTER TABLE plants
    ADD COLUMN IF NOT EXISTS acclimation_until TIMESTAMP;

ALTER TABLE plants
    ADD COLUMN IF NOT EXISTS acclimation_checkin_next_at TIMESTAMP;

COMMENT ON COLUMN plants.acclimation_until IS
    'Если задано и > now() — растение в режиме акклиматизации (issue #75). '
        'NULL = обычный режим. Очищается hourly-тиком после окончания.';

COMMENT ON COLUMN plants.acclimation_checkin_next_at IS
    'Когда отправить следующий check-in вопрос «как растение?» во время '
        'акклиматизации. Сбрасывается на NULL при выходе из режима.';

-- Индекс под hourly-тики — finish и check-in. Покрывает оба запроса:
--   acclimation_until IS NOT NULL AND acclimation_until <= now()
--   acclimation_until IS NOT NULL AND acclimation_checkin_next_at <= now()
-- Большинство растений вне акклиматизации (NULL) → их в индекс не пускаем.
CREATE INDEX IF NOT EXISTS idx_plants_acclimation_until
    ON plants(acclimation_until)
    WHERE acclimation_until IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_plants_acclimation_checkin
    ON plants(acclimation_checkin_next_at)
    WHERE acclimation_checkin_next_at IS NOT NULL;
