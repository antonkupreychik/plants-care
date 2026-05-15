-- Issue #60: лог админ-рассылок.
-- На сразу N=1 запись на каждую попытку рассылки (фоновая @Async-таска
-- обновляет sent/failed/blocked инкрементально, polling из админки читает).

CREATE TABLE admin_broadcasts (
    id              BIGSERIAL PRIMARY KEY,
    message_text    TEXT        NOT NULL,
    audience_filter VARCHAR(64) NOT NULL,
    total_count     INT         NOT NULL,
    sent_count      INT         NOT NULL DEFAULT 0,
    failed_count    INT         NOT NULL DEFAULT 0,
    blocked_count   INT         NOT NULL DEFAULT 0,
    status          VARCHAR(16) NOT NULL,
    sent_by         VARCHAR(64) NOT NULL,
    created_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at     TIMESTAMP,
    CONSTRAINT chk_broadcast_status
        CHECK (status IN ('RUNNING', 'DONE', 'STOPPED', 'FAILED'))
);

-- Индекс для двух частых вьюх:
-- 1) поиск активной рассылки (защита от двойного запуска);
-- 2) выборка последних 20 для таблицы лога.
CREATE INDEX idx_admin_broadcasts_status_created
    ON admin_broadcasts (status, created_at DESC);
