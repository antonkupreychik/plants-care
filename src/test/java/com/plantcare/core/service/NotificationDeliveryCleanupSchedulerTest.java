package com.plantcare.core.service;

import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ретенция журнала доставок (issue #95): чистим старое, свежее не трогаем.
 */
@DisplayName("NotificationDeliveryCleanupScheduler — ретенция журнала доставок (issue #95)")
class NotificationDeliveryCleanupSchedulerTest extends IntegrationTestBase {

    @Autowired
    private NotificationDeliveryCleanupScheduler scheduler;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.execute("DELETE FROM notification_delivery_events");
    }

    @Test
    @DisplayName("should_delete_only_events_older_than_retention_when_cleanup_runs")
    void should_delete_only_events_older_than_retention_when_cleanup_runs() {
        insertEvent(31);
        insertEvent(45);
        insertEvent(29);
        insertEvent(0);

        scheduler.cleanup();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notification_delivery_events", Long.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("should_be_idempotent_when_run_twice")
    void should_be_idempotent_when_run_twice() {
        insertEvent(40);

        scheduler.cleanup();
        scheduler.cleanup();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notification_delivery_events", Long.class)).isZero();
    }

    private void insertEvent(int daysAgo) {
        jdbc.update("""
                INSERT INTO notification_delivery_events
                    (created_at, channel, user_id, status, error_code, latency_ms)
                VALUES (now() - make_interval(days => ?), 'PUSH', NULL, 'SENT', NULL, 5)
                """, daysAgo);
    }
}
