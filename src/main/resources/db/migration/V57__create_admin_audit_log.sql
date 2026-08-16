-- Issue #98: аудит-лог админских действий.
--
-- Таблица append-only: пишем только INSERT, читаем на /admin/audit.
-- UPDATE / DELETE / TRUNCATE запрещены триггером (см. ниже) — это и есть
-- гарантия неизменности лога, ради которой он заводится.
--
-- admin_username, а не admin_user_id: админы живут в InMemoryUserDetailsManager
-- (см. AdminSecurityConfig), таблицы админов в схеме нет. Когда/если появится —
-- добавится отдельная nullable-колонка admin_user_id, forward-миграцией.
CREATE TABLE admin_audit_log (
    id             BIGSERIAL PRIMARY KEY,
    occurred_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    admin_username VARCHAR(100) NOT NULL,
    action         VARCHAR(64)  NOT NULL,
    target_type    VARCHAR(64),
    target_id      VARCHAR(64),
    details        JSONB,
    request_ip     VARCHAR(45)
);

COMMENT ON TABLE admin_audit_log IS
    'Append-only аудит админских действий (issue #98). UPDATE/DELETE/TRUNCATE запрещены триггером.';

-- Лента по умолчанию: последние события первыми.
CREATE INDEX idx_admin_audit_log_occurred_at ON admin_audit_log (occurred_at DESC);
-- Фильтр «по админу» + сортировка по времени.
CREATE INDEX idx_admin_audit_log_admin ON admin_audit_log (admin_username, occurred_at DESC);
-- Фильтр «по action» + сортировка по времени.
CREATE INDEX idx_admin_audit_log_action ON admin_audit_log (action, occurred_at DESC);
-- Секция «История админских действий» на карточке юзера и фильтр по target_type.
CREATE INDEX idx_admin_audit_log_target ON admin_audit_log (target_type, target_id, occurred_at DESC);

-- Защита от изменения: любая не-INSERT операция роняет транзакцию.
-- Триггер срабатывает и для владельца таблицы; штатный способ снять защиту
-- (например, для ретеншена) — ALTER TABLE ... DISABLE TRIGGER USER, что само
-- по себе видно в аудите БД.
CREATE FUNCTION admin_audit_log_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'admin_audit_log is append-only: % is not allowed', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_admin_audit_log_no_update_delete
    BEFORE UPDATE OR DELETE ON admin_audit_log
    FOR EACH ROW EXECUTE FUNCTION admin_audit_log_append_only();

CREATE TRIGGER trg_admin_audit_log_no_truncate
    BEFORE TRUNCATE ON admin_audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION admin_audit_log_append_only();
