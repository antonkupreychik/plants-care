-- Placeholder миграция для задачи #1.
-- В задаче #2 будет заменена на полную V1__init_schema.sql из подготовленных миграций.
-- Этот файл нужен только чтобы Flyway не ругался при первом запуске пустого проекта.

CREATE TABLE IF NOT EXISTS _placeholder (
    id BIGSERIAL PRIMARY KEY
);

DROP TABLE _placeholder;
