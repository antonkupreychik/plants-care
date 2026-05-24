-- Issue #78: Feature Flags per-user.
-- Простой JSONB на users — без отдельной таблицы. Включить фичу для юзера
-- = записать { "feature_code": "true" } в этот объект.

ALTER TABLE users
    ADD COLUMN feature_flags JSONB NOT NULL DEFAULT '{}'::jsonb;

-- GIN-индекс на случай, если в будущем будут запросы вида «у каких юзеров
-- включён конкретный флаг». На текущем размере (~десятки юзеров) это
-- overkill, но индекс не мешает.
CREATE INDEX idx_users_feature_flags ON users USING GIN (feature_flags);


UPDATE users
SET feature_flags = jsonb_set(
        coalesce(feature_flags, '{}'::jsonb),
        '{calendar}', '"true"'
                    )
WHERE NOT (coalesce(feature_flags, '{}'::jsonb) ? 'calendar');

ALTER TABLE users
    ALTER COLUMN feature_flags SET DEFAULT '{"calendar": "true"}'::jsonb;
