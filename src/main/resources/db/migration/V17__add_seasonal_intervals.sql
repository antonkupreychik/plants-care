-- Issue #67: Сезонные интервалы (лето/зима).
-- Поля на users — глобальная конфигурация: вкл/выкл, режим, границы сезонов,
-- мультипликаторы и фиксированные override-интервалы.
-- Поле на plants — per-plant решение «следовать глобальной / форс-вкл / форс-выкл».

ALTER TABLE users
    ADD COLUMN seasonal_enabled                    BOOLEAN          NOT NULL DEFAULT FALSE,
    ADD COLUMN seasonal_mode                       VARCHAR(16)      NOT NULL DEFAULT 'MULTIPLIER',
    -- Границы сезонов хранятся как «MMDD»: 0401 = 1 апреля, 1001 = 1 октября.
    -- Хранить INT, а не строку — проще сравнивать диапазоны без парсинга.
    ADD COLUMN summer_start_mmdd                   INTEGER          NOT NULL DEFAULT 401,
    ADD COLUMN winter_start_mmdd                   INTEGER          NOT NULL DEFAULT 1001,
    -- Дефолты по issue: лето 0.8 (чаще), зима 1.2 (реже).
    -- NUMERIC(3,2) — точность 0.01, диапазон 0.10..9.99 более чем достаточен.
    ADD COLUMN summer_multiplier                   NUMERIC(3, 2)    NOT NULL DEFAULT 0.80,
    ADD COLUMN winter_multiplier                   NUMERIC(3, 2)    NOT NULL DEFAULT 1.20,
    -- Опциональные фиксированные интервалы для режима FIXED.
    -- nullable: если null → fallback на базовый интервал растения.
    ADD COLUMN summer_interval_override_days       INTEGER,
    ADD COLUMN winter_interval_override_days       INTEGER,
    -- CHECK на режим — экономит проверку в коде.
    ADD CONSTRAINT chk_users_seasonal_mode
        CHECK (seasonal_mode IN ('MULTIPLIER', 'FIXED'));

ALTER TABLE plants
    -- INHERIT — следует глобальной настройке юзера;
    -- ON — сезонность включена для этого растения даже если глобально выкл;
    -- OFF — сезонность не применяется даже если глобально вкл.
    ADD COLUMN seasonal_override VARCHAR(8) NOT NULL DEFAULT 'INHERIT',
    ADD CONSTRAINT chk_plants_seasonal_override
        CHECK (seasonal_override IN ('INHERIT', 'ON', 'OFF'));

-- Никаких индексов: эти поля читаются только в контексте конкретного юзера /
-- растения (через PK / FK), не в bulk-запросах «найди всех у кого X».
