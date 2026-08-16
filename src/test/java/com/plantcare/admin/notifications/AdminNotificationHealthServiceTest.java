package com.plantcare.admin.notifications;

import com.plantcare.admin.notifications.dto.ChannelHealthDto;
import com.plantcare.admin.notifications.dto.NotificationHealthDto;
import com.plantcare.admin.notifications.dto.ProblemUserDto;
import com.plantcare.admin.notifications.service.AdminNotificationHealthService;
import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционные тесты агрегатов health-дашборда каналов (issue #95).
 *
 * <p>Работаем на реальном Postgres: половина логики живёт в SQL
 * ({@code FILTER}, {@code date_trunc}, оконный поиск серии фейлов), на H2 или
 * моках проверять было бы нечего.
 */
@DisplayName("AdminNotificationHealthService — агрегаты health-дашборда (issue #95)")
class AdminNotificationHealthServiceTest extends IntegrationTestBase {

    @Autowired
    private AdminNotificationHealthService service;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.execute("DELETE FROM notification_delivery_events");
        jdbc.execute("DELETE FROM user_devices");
        jdbc.execute("DELETE FROM users");
    }

    @Test
    @DisplayName("should_show_both_channels_with_zeros_when_no_events")
    void should_show_both_channels_with_zeros_when_no_events() {
        NotificationHealthDto health = service.loadHealth(24, null);

        assertThat(health.channels()).extracting(ChannelHealthDto::channel)
                .containsExactly("TELEGRAM", "PUSH");
        assertThat(health.totalAttempts()).isZero();
        assertThat(health.hasAlerts()).isFalse();
    }

    @Test
    @DisplayName("should_compute_success_and_error_rate_when_channel_has_mixed_outcomes")
    void should_compute_success_and_error_rate_when_channel_has_mixed_outcomes() {
        long userId = insertUser(600_001L, "mixed");
        insertEvents("TELEGRAM", "SENT", null, userId, 9, 1);
        insertEvents("TELEGRAM", "FAILED", "telegram:403", userId, 1, 1);

        ChannelHealthDto telegram = channel(service.loadHealth(24, null), "TELEGRAM");

        assertThat(telegram.total()).isEqualTo(10);
        assertThat(telegram.sent()).isEqualTo(9);
        assertThat(telegram.errorRate()).isEqualTo(10.0d);
        assertThat(telegram.successRate()).isEqualTo(90.0d);
    }

    @Test
    @DisplayName("should_not_alert_when_error_rate_below_threshold")
    void should_not_alert_when_error_rate_below_threshold() {
        long userId = insertUser(600_002L, "healthy");
        // 2 из 100 = 2% — ниже порога 5%.
        insertEvents("PUSH", "SENT", null, userId, 98, 1);
        insertEvents("PUSH", "FAILED", "fcm:DeliveryError", userId, 2, 1);

        NotificationHealthDto health = service.loadHealth(24, null);

        assertThat(channel(health, "PUSH").alerting()).isFalse();
        assertThat(health.hasAlerts()).isFalse();
        assertThat(service.alertingChannels(24)).isEmpty();
    }

    @Test
    @DisplayName("should_alert_when_push_error_rate_exceeds_threshold")
    void should_alert_when_push_error_rate_exceeds_threshold() {
        long userId = insertUser(600_003L, "broken-push");
        // Симулируем падение push-провайдера: 10 из 100 = 10% > 5%.
        insertEvents("PUSH", "SENT", null, userId, 90, 1);
        insertEvents("PUSH", "FAILED", "fcm:UnregisteredDevice", userId, 10, 1);

        NotificationHealthDto health = service.loadHealth(24, null);

        assertThat(channel(health, "PUSH").alerting()).isTrue();
        assertThat(health.alertingChannels()).extracting(ChannelHealthDto::channel).containsExactly("PUSH");
        assertThat(service.alertingChannels(24)).extracting(ChannelHealthDto::channel).containsExactly("PUSH");
    }

    @Test
    @DisplayName("should_count_429_separately_when_telegram_rate_limits")
    void should_count_429_separately_when_telegram_rate_limits() {
        long userId = insertUser(600_004L, "throttled");
        insertEvents("TELEGRAM", "RATE_LIMITED", "telegram:429", userId, 3, 1);

        ChannelHealthDto telegram = channel(service.loadHealth(24, null), "TELEGRAM");

        assertThat(telegram.rateLimited()).isEqualTo(3);
        assertThat(telegram.failed()).isZero();
        assertThat(telegram.errors()).isEqualTo(3);
    }

    @Test
    @DisplayName("should_ignore_events_outside_window_when_window_is_one_hour")
    void should_ignore_events_outside_window_when_window_is_one_hour() {
        long userId = insertUser(600_005L, "old-news");
        insertEvents("TELEGRAM", "FAILED", "telegram:403", userId, 5, 10);

        assertThat(channel(service.loadHealth(1, null), "TELEGRAM").total()).isZero();
        assertThat(channel(service.loadHealth(24, null), "TELEGRAM").total()).isEqualTo(5);
    }

    @Test
    @DisplayName("should_rank_top_error_codes_when_several_codes_present")
    void should_rank_top_error_codes_when_several_codes_present() {
        long userId = insertUser(600_006L, "errors");
        insertEvents("PUSH", "FAILED", "fcm:UnregisteredDevice", userId, 7, 1);
        insertEvents("TELEGRAM", "FAILED", "telegram:403", userId, 2, 1);

        NotificationHealthDto health = service.loadHealth(24, null);

        assertThat(health.topErrors()).hasSize(2);
        assertThat(health.topErrors().get(0).errorCode()).isEqualTo("fcm:UnregisteredDevice");
        assertThat(health.topErrors().get(0).count()).isEqualTo(7);
    }

    @Test
    @DisplayName("should_filter_top_errors_by_channel_when_filter_applied")
    void should_filter_top_errors_by_channel_when_filter_applied() {
        long userId = insertUser(600_007L, "filtered");
        insertEvents("PUSH", "FAILED", "fcm:UnregisteredDevice", userId, 7, 1);
        insertEvents("TELEGRAM", "FAILED", "telegram:403", userId, 2, 1);

        NotificationHealthDto health = service.loadHealth(24, "TELEGRAM");

        assertThat(health.channelFilter()).isEqualTo("TELEGRAM");
        assertThat(health.topErrors()).singleElement()
                .satisfies(e -> assertThat(e.errorCode()).isEqualTo("telegram:403"));
        // Карточки НЕ фильтруются — дашборд про сравнение каналов.
        assertThat(health.channels()).hasSize(2);
    }

    @Test
    @DisplayName("should_list_problem_user_when_more_than_three_failures_in_a_row")
    void should_list_problem_user_when_more_than_three_failures_in_a_row() {
        long userId = insertUser(600_008L, "hopeless");
        insertEvents("PUSH", "FAILED", "fcm:UnregisteredDevice", userId, 4, 1);

        NotificationHealthDto health = service.loadHealth(24, null);

        assertThat(health.problemUsers()).singleElement().satisfies(u -> {
            assertThat(u.userId()).isEqualTo(userId);
            assertThat(u.channel()).isEqualTo("PUSH");
            assertThat(u.consecutiveFailures()).isEqualTo(4);
            assertThat(u.lastErrorCode()).isEqualTo("fcm:UnregisteredDevice");
            assertThat(u.userLabel()).contains("@hopeless");
        });
    }

    @Test
    @DisplayName("should_not_list_problem_user_when_exactly_three_failures")
    void should_not_list_problem_user_when_exactly_three_failures() {
        long userId = insertUser(600_009L, "borderline");
        insertEvents("PUSH", "FAILED", "fcm:DeliveryError", userId, 3, 1);

        assertThat(service.loadHealth(24, null).problemUsers()).isEmpty();
    }

    @Test
    @DisplayName("should_reset_failure_streak_when_later_delivery_succeeded")
    void should_reset_failure_streak_when_later_delivery_succeeded() {
        long userId = insertUser(600_010L, "recovered");
        // 5 фейлов 5 часов назад, затем успех час назад — серии больше нет.
        insertEvents("PUSH", "FAILED", "fcm:DeliveryError", userId, 5, 5);
        insertEvents("PUSH", "SENT", null, userId, 1, 1);

        assertThat(service.loadHealth(24, null).problemUsers()).isEmpty();
    }

    @Test
    @DisplayName("should_keep_streak_per_channel_when_other_channel_is_healthy")
    void should_keep_streak_per_channel_when_other_channel_is_healthy() {
        long userId = insertUser(600_011L, "half-broken");
        insertEvents("PUSH", "FAILED", "fcm:UnregisteredDevice", userId, 4, 2);
        insertEvents("TELEGRAM", "SENT", null, userId, 4, 1);

        assertThat(service.loadHealth(24, null).problemUsers())
                .extracting(ProblemUserDto::channel)
                .containsExactly("PUSH");
    }

    @Test
    @DisplayName("should_ignore_events_without_user_when_building_problem_users")
    void should_ignore_events_without_user_when_building_problem_users() {
        insertEvents("TELEGRAM", "FAILED", "telegram:400", null, 6, 1);

        NotificationHealthDto health = service.loadHealth(24, null);

        assertThat(health.problemUsers()).isEmpty();
        assertThat(channel(health, "TELEGRAM").failed()).isEqualTo(6);
    }

    @Test
    @DisplayName("should_clamp_window_when_hours_out_of_range")
    void should_clamp_window_when_hours_out_of_range() {
        assertThat(service.loadHealth(0, null).hours()).isEqualTo(AdminNotificationHealthService.MIN_HOURS);
        assertThat(service.loadHealth(10_000, null).hours()).isEqualTo(AdminNotificationHealthService.MAX_HOURS);
    }

    @Test
    @DisplayName("should_drop_unknown_channel_filter_when_value_is_garbage")
    void should_drop_unknown_channel_filter_when_value_is_garbage() {
        assertThat(service.loadHealth(24, "APNS").channelFilter())
                .isEqualTo(AdminNotificationHealthService.CHANNEL_FILTER_ALL);
    }

    @Test
    @DisplayName("should_build_chart_series_for_every_channel_when_only_one_channel_has_data")
    void should_build_chart_series_for_every_channel_when_only_one_channel_has_data() {
        long userId = insertUser(600_012L, "chart");
        insertEvents("PUSH", "SENT", null, userId, 2, 1);
        insertEvents("PUSH", "SENT", null, userId, 3, 2);

        NotificationHealthDto health = service.loadHealth(24, null);

        assertThat(health.chartLabels()).hasSize(2);
        assertThat(health.chartSeries()).hasSize(2);
        // Ряды выровнены по общей оси: у «молчащего» канала — нули, а не пустота.
        assertThat(health.chartSeries()).allSatisfy(s -> assertThat(s.counts()).hasSize(2));
        assertThat(health.chartDataJson()).contains("\"labels\"").contains("PUSH");
    }

    @Test
    @DisplayName("should_delete_all_devices_when_pruning_tokens")
    void should_delete_all_devices_when_pruning_tokens() {
        long userId = insertUser(600_013L, "dead-devices");
        insertDevice(userId, "token-1");
        insertDevice(userId, "token-2");

        int deleted = service.pruneDevices(userId, "admin");

        assertThat(deleted).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM user_devices WHERE user_id = ?", Long.class, userId)).isZero();
    }

    @Test
    @DisplayName("should_report_empty_result_when_test_push_has_no_devices")
    void should_report_empty_result_when_test_push_has_no_devices() {
        long userId = insertUser(600_014L, "no-devices");

        AdminNotificationHealthService.TestPushResult result = service.sendTestPush(userId, "admin");

        assertThat(result.total()).isZero();
    }

    @Test
    @DisplayName("should_report_sent_when_test_push_goes_through_noop_sender")
    void should_report_sent_when_test_push_goes_through_noop_sender() {
        // push.enabled=false в тестах → NoopPushSender возвращает SENT.
        long userId = insertUser(600_015L, "noop-push");
        insertDevice(userId, "token-x");

        AdminNotificationHealthService.TestPushResult result = service.sendTestPush(userId, "admin");

        assertThat(result.sent()).isEqualTo(1);
        assertThat(result.total()).isEqualTo(1);
    }

    // ==================================================================
    // Фикстуры
    // ==================================================================

    /** Карточка конкретного канала из снапшота — карточки всегда обе, промаха быть не может. */
    private static ChannelHealthDto channel(NotificationHealthDto health, String channel) {
        return health.channels().stream()
                .filter(c -> c.channel().equals(channel))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No card for channel " + channel));
    }

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

    /**
     * Кладёт {@code count} событий одного вида «{@code hoursAgo} часов назад».
     * Порядок внутри пачки задаётся микросекундным смещением — чтобы у
     * «последней ошибки серии» был однозначный победитель.
     */
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
