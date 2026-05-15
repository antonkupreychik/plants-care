-- Issue #69: интеграция с погодой (Open-Meteo).
-- Хранение per-user: вкл/выкл, координаты, последний fetch (для кеша 60мин),
-- последнее значение RH (чтобы рендерить из кеша без перезапроса).

ALTER TABLE users
    ADD COLUMN weather_enabled       BOOLEAN          NOT NULL DEFAULT FALSE,
    ADD COLUMN weather_lat           DOUBLE PRECISION,
    ADD COLUMN weather_lon           DOUBLE PRECISION,
    ADD COLUMN weather_last_fetch_at TIMESTAMP,
    ADD COLUMN weather_last_rh       INTEGER;

-- Никаких индексов — поле читается только в контексте конкретного юзера
-- (через PK или telegram_chat_id) и никогда «найди всех у кого weather_enabled».
