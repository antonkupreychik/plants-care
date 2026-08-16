package com.plantcare.core.service;

import com.plantcare.bot.support.IntegrationTestBase;
import com.plantcare.core.metrics.MetricsService.TelegramErrorCode;
import com.plantcare.core.service.PushSender.PushResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционные тесты записи журнала доставок (issue #95).
 *
 * <p>Репозиторий не мокаем — есть Testcontainers Postgres, а проверять надо в
 * том числе резолв {@code chat_id → users.id} и то, что enum'ы ложатся строками,
 * которые потом читает SQL дашборда.
 */
@DisplayName("NotificationDeliveryRecorder — журнал попыток доставки (issue #95)")
class NotificationDeliveryRecorderTest extends IntegrationTestBase {

    @Autowired
    private NotificationDeliveryRecorder recorder;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.execute("DELETE FROM notification_delivery_events");
        jdbc.execute("DELETE FROM user_devices");
        jdbc.execute("DELETE FROM users");
    }

    @Test
    @DisplayName("should_store_sent_event_with_resolved_user_when_telegram_delivered")
    void should_store_sent_event_with_resolved_user_when_telegram_delivered() {
        long userId = insertUser(500_001L, "tg-user");

        recorder.recordTelegramSent(500_001L, 137L);

        Map<String, Object> row = singleEvent();
        assertThat(row.get("channel")).isEqualTo("TELEGRAM");
        assertThat(row.get("status")).isEqualTo("SENT");
        assertThat(row.get("error_code")).isNull();
        assertThat(row.get("latency_ms")).isEqualTo(137);
        assertThat(((Number) row.get("user_id")).longValue()).isEqualTo(userId);
    }

    @Test
    @DisplayName("should_store_rate_limited_status_when_telegram_answers_429")
    void should_store_rate_limited_status_when_telegram_answers_429() {
        insertUser(500_002L, "flooded");

        recorder.recordTelegramFailure(500_002L, TelegramErrorCode.RATE_LIMITED, 12L);

        Map<String, Object> row = singleEvent();
        assertThat(row.get("status")).isEqualTo("RATE_LIMITED");
        assertThat(row.get("error_code")).isEqualTo("telegram:429");
    }

    @Test
    @DisplayName("should_store_failed_status_when_telegram_answers_403")
    void should_store_failed_status_when_telegram_answers_403() {
        insertUser(500_003L, "blocked-me");

        recorder.recordTelegramFailure(500_003L, TelegramErrorCode.FORBIDDEN, 8L);

        Map<String, Object> row = singleEvent();
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("error_code")).isEqualTo("telegram:403");
    }

    @Test
    @DisplayName("should_store_event_without_user_when_chat_id_unknown")
    void should_store_event_without_user_when_chat_id_unknown() {
        // Чат, которого нет в users: caretaker-копия давно удалённого юзера.
        recorder.recordTelegramSent(999_999_999L, 5L);

        Map<String, Object> row = singleEvent();
        assertThat(row.get("user_id")).isNull();
        assertThat(row.get("status")).isEqualTo("SENT");
    }

    @Test
    @DisplayName("should_map_stale_token_to_fcm_error_code_when_push_token_dead")
    void should_map_stale_token_to_fcm_error_code_when_push_token_dead() {
        long userId = insertUser(500_004L, "dead-token");

        recorder.recordPush(userId, PushResult.STALE_TOKEN, 42L);

        Map<String, Object> row = singleEvent();
        assertThat(row.get("channel")).isEqualTo("PUSH");
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("error_code")).isEqualTo(NotificationDeliveryRecorder.PUSH_ERROR_STALE_TOKEN);
    }

    @Test
    @DisplayName("should_map_failed_result_to_delivery_error_when_push_provider_fails")
    void should_map_failed_result_to_delivery_error_when_push_provider_fails() {
        long userId = insertUser(500_005L, "flaky-net");

        recorder.recordPush(userId, PushResult.FAILED, 900L);

        Map<String, Object> row = singleEvent();
        assertThat(row.get("error_code")).isEqualTo(NotificationDeliveryRecorder.PUSH_ERROR_DELIVERY);
    }

    @Test
    @DisplayName("should_clamp_negative_latency_to_zero_when_clock_jumps_backwards")
    void should_clamp_negative_latency_to_zero_when_clock_jumps_backwards() {
        long userId = insertUser(500_006L, "time-traveller");

        recorder.recordPush(userId, PushResult.SENT, -5L);

        assertThat(singleEvent().get("latency_ms")).isEqualTo(0);
    }

    private long insertUser(long chatId, String username) {
        return jdbc.queryForObject(
                "INSERT INTO users (telegram_chat_id, username, is_blocked) VALUES (?,?,false) RETURNING id",
                Long.class, chatId, username);
    }

    private Map<String, Object> singleEvent() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT channel, user_id, status, error_code, latency_ms FROM notification_delivery_events");
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }
}
