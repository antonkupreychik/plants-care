-- Issue #279, эпик #277 фаза 1: таблица для ShedLock JDBC-провайдера.
-- Обязательная схема по документации ShedLock 5.x — не менять имена колонок.
-- При single-instance поведение не меняется; при multi-instance лок не даёт
-- нескольким репликам одновременно запускать один и тот же @Scheduled-метод.
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at  TIMESTAMP(3) NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
