-- V21__create_plant_anniversaries_sent.sql
-- Issue #117: «дни рождения» растений и UI архива.
--
-- Идемпотентность шедулера годовщин: не отправлять годовщину одного и того же
-- растения дважды в один и тот же календарный год. Шедулер перед отправкой
-- делает INSERT в эту таблицу — если PK конфликт, значит уже слали.
--
-- Год берётся по календарю в TZ юзера (users.timezone), в котором уже
-- отправили — это даёт устойчивость к сдвигам времени запуска шедулера
-- и не плодит дубликаты на стыке суток UTC/локали.
--
-- Backward-compat note:
--   * Новая таблица — для старого кода невидима.
--   * ON DELETE CASCADE на plant_id: при «удалить навсегда» (часть B issue)
--     растение полностью дропается, история отправленных годовщин уезжает
--     вместе с ним — это ок, новое растение с тем же id не появится.
--   * Безопасно для rolling deploy.
--
-- Также здесь создаётся частичный индекс на plants(acquired_at) под
-- основной запрос шедулера: «активные растения с заданной датой acquired_at».
-- Большинство строк (без acquired_at или в архиве) в индекс не попадает.

BEGIN;

CREATE TABLE plant_anniversaries_sent (
    plant_id            BIGINT      NOT NULL REFERENCES plants(id) ON DELETE CASCADE,
    anniversary_year    INT         NOT NULL,
    sent_at             TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (plant_id, anniversary_year)
);

COMMENT ON TABLE plant_anniversaries_sent IS
    'Идемпотентность годовщин растений (issue #117): по одной строке на '
        '(plant_id, год). Шедулер перед отправкой пытается INSERT — конфликт '
        'PK означает «уже слали в этом году».';

COMMENT ON COLUMN plant_anniversaries_sent.anniversary_year IS
    'Календарный год (в TZ юзера, users.timezone), в котором уже отправили '
        'годовщину этого растения. Например, 2026 для acquired_at=2024-05-24.';

COMMENT ON COLUMN plant_anniversaries_sent.sent_at IS
    'Момент отправки уведомления в UTC (TIMESTAMPTZ). Для дебага и метрик.';

-- Частичный индекс под запрос шедулера годовщин:
--   SELECT ... FROM plants
--    WHERE acquired_at IS NOT NULL
--      AND archived_at IS NULL
--      AND EXTRACT(MONTH FROM acquired_at) = :m
--      AND EXTRACT(DAY   FROM acquired_at) = :d
-- В индекс попадают только активные растения с проставленной датой —
-- это ничтожная доля от всех строк plants, индекс компактный.
CREATE INDEX idx_plants_acquired_active
    ON plants(acquired_at)
    WHERE acquired_at IS NOT NULL AND archived_at IS NULL;

COMMIT;
