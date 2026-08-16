package com.plantcare.admin.notifications;

import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Тесты страницы /admin/notifications/health и алерта на главной (issue #95).
 *
 * <p>Ключевой сценарий AC: «симулировать падение канала → error rate растёт →
 * алерт срабатывает». APNs как отдельного канала в проекте нет (ADR-016: push
 * идёт через FCM, он же маршрутизирует на APNs), поэтому падение симулируем в
 * реально существующем канале PUSH.
 */
@AutoConfigureMockMvc
@DisplayName("Admin health-дашборд каналов уведомлений (issue #95)")
class AdminNotificationHealthPageTest extends IntegrationTestBase {

    private static final String PAGE = "/admin/notifications/health";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.execute("DELETE FROM notification_delivery_events");
        jdbc.execute("DELETE FROM user_devices");
        jdbc.execute("DELETE FROM users");
    }

    @Test
    @DisplayName("should_render_both_channel_cards_when_database_is_empty")
    void should_render_both_channel_cards_when_database_is_empty() throws Exception {
        mockMvc.perform(get(PAGE).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Каналы уведомлений")))
                .andExpect(content().string(containsString("TELEGRAM")))
                .andExpect(content().string(containsString("PUSH")))
                .andExpect(content().string(containsString("Топ ошибок")))
                .andExpect(content().string(containsString("Юзеры с проблемами")))
                // HTMX-поллинг раз в минуту (AC)
                .andExpect(content().string(containsString("every 60s")))
                .andExpect(content().string(containsString("/admin/notifications/health/_fragment")));
    }

    @Test
    @DisplayName("should_require_authentication_when_anonymous")
    void should_require_authentication_when_anonymous() throws Exception {
        mockMvc.perform(get(PAGE))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @DisplayName("should_show_alert_on_page_when_push_channel_error_rate_exceeds_threshold")
    void should_show_alert_on_page_when_push_channel_error_rate_exceeds_threshold() throws Exception {
        long userId = insertUser(700_001L, "broken");
        // Провайдер push «упал»: 20 из 100 попыток неуспешны — 20% > 5%.
        insertEvents("PUSH", "SENT", null, userId, 80, 1);
        insertEvents("PUSH", "FAILED", "fcm:UnregisteredDevice", userId, 20, 1);

        mockMvc.perform(get(PAGE).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Канал доставки деградирует")))
                .andExpect(content().string(containsString("fcm:UnregisteredDevice")))
                .andExpect(content().string(containsString("20.0")));
    }

    @Test
    @DisplayName("should_not_show_alert_on_page_when_error_rate_below_threshold")
    void should_not_show_alert_on_page_when_error_rate_below_threshold() throws Exception {
        long userId = insertUser(700_002L, "fine");
        insertEvents("PUSH", "SENT", null, userId, 99, 1);
        insertEvents("PUSH", "FAILED", "fcm:DeliveryError", userId, 1, 1);

        mockMvc.perform(get(PAGE).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Канал доставки деградирует"))));
    }

    @Test
    @DisplayName("should_show_alert_on_main_dashboard_when_channel_degrades")
    void should_show_alert_on_main_dashboard_when_channel_degrades() throws Exception {
        long userId = insertUser(700_003L, "dashboard-alert");
        insertEvents("PUSH", "SENT", null, userId, 80, 2);
        insertEvents("PUSH", "FAILED", "fcm:UnregisteredDevice", userId, 20, 2);

        mockMvc.perform(get("/admin").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Канал доставки уведомлений деградирует")))
                .andExpect(content().string(containsString("Открыть health-дашборд каналов")));
    }

    @Test
    @DisplayName("should_not_show_alert_on_main_dashboard_when_channels_healthy")
    void should_not_show_alert_on_main_dashboard_when_channels_healthy() throws Exception {
        long userId = insertUser(700_004L, "dashboard-ok");
        insertEvents("TELEGRAM", "SENT", null, userId, 10, 1);

        mockMvc.perform(get("/admin").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Канал доставки уведомлений деградирует"))));
    }

    @Test
    @DisplayName("should_list_problem_user_with_actions_when_failures_pile_up")
    void should_list_problem_user_with_actions_when_failures_pile_up() throws Exception {
        long userId = insertUser(700_005L, "hopeless");
        insertDevice(userId, "dead-token");
        insertEvents("PUSH", "FAILED", "fcm:UnregisteredDevice", userId, 5, 1);

        mockMvc.perform(get(PAGE).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("@hopeless")))
                .andExpect(content().string(containsString("/prune-devices")))
                .andExpect(content().string(containsString("/test-push")))
                .andExpect(content().string(containsString("/test-telegram")));
    }

    @Test
    @DisplayName("should_return_body_without_layout_when_htmx_fragment_requested")
    void should_return_body_without_layout_when_htmx_fragment_requested() throws Exception {
        mockMvc.perform(get(PAGE + "/_fragment").param("hours", "24").param("channel", "all")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Топ ошибок")))
                // layout не подмешан: ни сайдбара, ни шапки
                .andExpect(content().string(not(containsString("Plants Care Admin"))));
    }

    @Test
    @DisplayName("should_prune_devices_and_keep_filters_when_action_invoked")
    void should_prune_devices_and_keep_filters_when_action_invoked() throws Exception {
        long userId = insertUser(700_006L, "prune-me");
        insertDevice(userId, "token-1");

        mockMvc.perform(post(PAGE + "/users/" + userId + "/prune-devices")
                        .param("hours", "6").param("channel", "PUSH")
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(PAGE + "?hours=6&channel=PUSH"));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM user_devices WHERE user_id = ?", Long.class, userId)).isZero();
    }

    @Test
    @DisplayName("should_redirect_with_error_when_pruning_user_without_devices")
    void should_redirect_with_error_when_pruning_user_without_devices() throws Exception {
        long userId = insertUser(700_007L, "no-devices");

        mockMvc.perform(post(PAGE + "/users/" + userId + "/prune-devices")
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(PAGE));
    }

    @Test
    @DisplayName("should_send_test_push_when_user_has_devices")
    void should_send_test_push_when_user_has_devices() throws Exception {
        long userId = insertUser(700_008L, "test-push");
        insertDevice(userId, "token-1");

        mockMvc.perform(post(PAGE + "/users/" + userId + "/test-push")
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(PAGE));
    }

    // ==================================================================
    // Фикстуры
    // ==================================================================

    private long insertUser(long chatId, String username) {
        return jdbc.queryForObject(
                "INSERT INTO users (telegram_chat_id, username, is_blocked) VALUES (?,?,false) RETURNING id",
                Long.class, chatId, username);
    }

    private void insertDevice(long userId, String token) {
        jdbc.update("""
                INSERT INTO user_devices (user_id, platform, push_token, created_at, last_seen_at)
                VALUES (?, 'ANDROID', ?, now(), now())
                """, userId, token);
    }

    private void insertEvents(String channel, String status, String errorCode,
                              Long userId, int count, int hoursAgo) {
        for (int i = 0; i < count; i++) {
            jdbc.update("""
                    INSERT INTO notification_delivery_events
                        (created_at, channel, user_id, status, error_code, latency_ms)
                    VALUES (now() - make_interval(hours => ?) + make_interval(secs => ?), ?, ?, ?, ?, ?)
                    """, hoursAgo, i * 0.001d, channel, userId, status, errorCode, 10);
        }
    }
}
