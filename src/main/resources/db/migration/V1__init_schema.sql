-- =====================================================
-- V1: Initial schema for Plant Care Bot
-- =====================================================

-- Пользователи Telegram
CREATE TABLE users (
    id                  BIGSERIAL PRIMARY KEY,
    telegram_chat_id    BIGINT NOT NULL UNIQUE,
    username            VARCHAR(255),
    timezone            VARCHAR(64) NOT NULL DEFAULT 'UTC',
    quiet_hours_start   TIME NOT NULL DEFAULT '22:00',
    quiet_hours_end     TIME NOT NULL DEFAULT '09:00',
    paused_until        TIMESTAMP,
    conversation_state  VARCHAR(64) NOT NULL DEFAULT 'IDLE',
    state_data          JSONB,
    is_blocked          BOOLEAN NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_users_telegram_chat_id ON users(telegram_chat_id);

COMMENT ON COLUMN users.conversation_state IS 'Текущий шаг диалога: IDLE, AWAITING_TIMEZONE, AWAITING_PLANT_NAME и т.д.';
COMMENT ON COLUMN users.state_data IS 'Промежуточные данные для текущего диалога';
COMMENT ON COLUMN users.paused_until IS 'Если задано — уведомления не шлются до этого момента (отпуск-режим)';
COMMENT ON COLUMN users.is_blocked IS 'TRUE если юзер заблокировал бота — не пытаемся слать сообщения';


-- Справочник видов растений (шаблоны)
CREATE TABLE species (
    id                      BIGSERIAL PRIMARY KEY,
    name                    VARCHAR(100) NOT NULL UNIQUE,
    latin_name              VARCHAR(150),
    watering_days           INT,
    misting_days            INT,
    fertilizing_days        INT,
    light_preference        VARCHAR(16),
    care_difficulty         VARCHAR(16),
    description             TEXT,
    search_tags             TEXT,
    popularity              INT NOT NULL DEFAULT 0,
    created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_light    CHECK (light_preference IN ('SHADE', 'PARTIAL', 'BRIGHT', 'DIRECT')),
    CONSTRAINT chk_difficulty CHECK (care_difficulty IN ('EASY', 'MEDIUM', 'HARD'))
);

CREATE INDEX idx_species_popularity ON species(popularity DESC);
CREATE INDEX idx_species_search_tags ON species USING gin(to_tsvector('simple', coalesce(search_tags, '')));

COMMENT ON COLUMN species.watering_days IS 'NULL если для этого вида не нужен регулярный полив';
COMMENT ON COLUMN species.misting_days IS 'NULL если опрыскивание не требуется (например, для кактусов)';
COMMENT ON COLUMN species.fertilizing_days IS 'NULL если удобрение не требуется';
COMMENT ON COLUMN species.search_tags IS 'Через запятую, для поиска: "монстера, monstera, лиана"';


-- Комнаты пользователя
CREATE TABLE rooms (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name            VARCHAR(100) NOT NULL,
    display_order   INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_rooms_user_name UNIQUE (user_id, name)
);

CREATE INDEX idx_rooms_user_id ON rooms(user_id, display_order);


-- Растения
CREATE TABLE plants (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    room_id         BIGINT REFERENCES rooms(id) ON DELETE SET NULL,
    species_id      BIGINT REFERENCES species(id) ON DELETE SET NULL,
    name            VARCHAR(100) NOT NULL,
    notes           TEXT,
    photo_file_id   VARCHAR(255),
    archived_at     TIMESTAMP,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_plants_user_room ON plants(user_id, room_id) WHERE archived_at IS NULL;
CREATE INDEX idx_plants_species ON plants(species_id) WHERE species_id IS NOT NULL;

COMMENT ON COLUMN plants.user_id IS 'Денормализация: дублируется из room для удобства запросов и работы при room_id IS NULL';
COMMENT ON COLUMN plants.archived_at IS 'Soft delete: растение умерло, но история сохраняется';
COMMENT ON COLUMN plants.photo_file_id IS 'Telegram file_id, не URL';


-- Расписания ухода (полив, опрыскивание, удобрение)
CREATE TABLE care_schedules (
    id              BIGSERIAL PRIMARY KEY,
    plant_id        BIGINT NOT NULL REFERENCES plants(id) ON DELETE CASCADE,
    task_type       VARCHAR(32) NOT NULL,
    interval_days   INT NOT NULL,
    next_due_at     TIMESTAMP NOT NULL,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    metadata        JSONB,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_schedules_plant_type UNIQUE (plant_id, task_type),
    CONSTRAINT chk_task_type CHECK (task_type IN ('WATERING', 'MISTING', 'FERTILIZING')),
    CONSTRAINT chk_interval_positive CHECK (interval_days > 0)
);

CREATE INDEX idx_schedules_next_due ON care_schedules(next_due_at) WHERE is_active = TRUE;
CREATE INDEX idx_schedules_plant ON care_schedules(plant_id);

COMMENT ON COLUMN care_schedules.task_type IS 'WATERING / MISTING / FERTILIZING';
COMMENT ON COLUMN care_schedules.metadata IS 'Type-specific данные, например {"fertilizer_type": "NPK 10-10-10"}';


-- История выполнения задач ухода
CREATE TABLE care_history (
    id              BIGSERIAL PRIMARY KEY,
    plant_id        BIGINT NOT NULL REFERENCES plants(id) ON DELETE CASCADE,
    task_type       VARCHAR(32) NOT NULL,
    done_at         TIMESTAMP NOT NULL,
    was_on_time     BOOLEAN NOT NULL,
    note            VARCHAR(500),
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_history_task_type CHECK (task_type IN ('WATERING', 'MISTING', 'FERTILIZING'))
);

CREATE INDEX idx_history_plant_date ON care_history(plant_id, done_at DESC);
CREATE INDEX idx_history_task_type ON care_history(task_type, done_at DESC);

COMMENT ON COLUMN care_history.was_on_time IS 'Считается в момент записи: done_at <= scheduled + grace_period';


-- Лог отправленных уведомлений (для дедупликации)
CREATE TABLE notifications_log (
    id                  BIGSERIAL PRIMARY KEY,
    plant_id            BIGINT NOT NULL REFERENCES plants(id) ON DELETE CASCADE,
    task_type           VARCHAR(32) NOT NULL,
    notification_type   VARCHAR(32) NOT NULL,
    sent_at             TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_notif_task_type CHECK (task_type IN ('WATERING', 'MISTING', 'FERTILIZING')),
    CONSTRAINT chk_notif_type CHECK (notification_type IN ('DUE', 'OVERDUE_REMINDER'))
);

CREATE INDEX idx_notif_plant_task_sent ON notifications_log(plant_id, task_type, sent_at DESC);

COMMENT ON TABLE notifications_log IS 'Чтобы не слать одно и то же уведомление повторно каждую минуту';


-- Триггер для автоматического обновления updated_at
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_users_updated_at BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER trg_plants_updated_at BEFORE UPDATE ON plants
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER trg_schedules_updated_at BEFORE UPDATE ON care_schedules
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
