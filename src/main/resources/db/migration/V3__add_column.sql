-- =====================================================
-- V3: Потерянная колонка
-- =====================================================

ALTER TABLE notifications_log
    ADD created_at TIMESTAMP WITHOUT TIME ZONE;

ALTER TABLE notifications_log
    ALTER COLUMN created_at SET NOT NULL;