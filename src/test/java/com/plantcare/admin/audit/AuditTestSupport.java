package com.plantcare.admin.audit;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Очистка аудит-лога между тестами.
 *
 * <p>Таблица append-only, поэтому обычный {@code DELETE} по ней не проходит —
 * триггер снимается ровно на время очистки. Это единственное место в проекте,
 * где так можно: в проде защита не снимается никогда, и сам факт
 * {@code DISABLE TRIGGER} виден в логах БД.
 */
final class AuditTestSupport {

    private AuditTestSupport() {
    }

    static void clear(JdbcTemplate jdbc) {
        jdbc.execute("ALTER TABLE admin_audit_log DISABLE TRIGGER USER");
        try {
            jdbc.execute("DELETE FROM admin_audit_log");
        } finally {
            jdbc.execute("ALTER TABLE admin_audit_log ENABLE TRIGGER USER");
        }
    }
}
