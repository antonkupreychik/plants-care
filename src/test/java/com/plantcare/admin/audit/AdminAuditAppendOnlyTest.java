package com.plantcare.admin.audit;

import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue #98: гарантия append-only на уровне БД.
 *
 * <p>Проверяем именно триггер, а не код приложения: защита должна держать
 * и когда в таблицу лезут psql'ом мимо сервиса.
 */
@DisplayName("admin_audit_log — append-only")
class AdminAuditAppendOnlyTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        AuditTestSupport.clear(jdbc);
        jdbc.update("""
                INSERT INTO admin_audit_log (admin_username, action, target_type, target_id, details)
                VALUES ('admin', 'USER_TOGGLE_BLOCK', 'USER', '1', CAST(? AS jsonb))
                """, "{\"before\":false,\"after\":true}");
    }

    @Test
    @DisplayName("INSERT разрешён")
    void insertAllowed() {
        jdbc.update("INSERT INTO admin_audit_log (admin_username, action) VALUES ('admin2', 'USER_PAUSE')");

        Long count = jdbc.queryForObject("SELECT count(*) FROM admin_audit_log", Long.class);
        assertThat(count).isEqualTo(2L);
    }

    @Test
    @DisplayName("UPDATE отклоняется триггером")
    void updateRejected() {
        assertThatThrownBy(() ->
                jdbc.update("UPDATE admin_audit_log SET admin_username = 'hacker'"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("DELETE отклоняется триггером")
    void deleteRejected() {
        assertThatThrownBy(() ->
                jdbc.update("DELETE FROM admin_audit_log"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("TRUNCATE отклоняется триггером")
    void truncateRejected() {
        assertThatThrownBy(() ->
                jdbc.execute("TRUNCATE admin_audit_log"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("После отклонённого DELETE запись на месте")
    void rowSurvivesRejectedDelete() {
        assertThatThrownBy(() -> jdbc.update("DELETE FROM admin_audit_log"))
                .isInstanceOf(DataAccessException.class);

        Long count = jdbc.queryForObject("SELECT count(*) FROM admin_audit_log", Long.class);
        assertThat(count).isEqualTo(1L);
    }
}
