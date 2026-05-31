package com.plantcare.core.metrics;

import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.metrics.MetricsService.CallbackOutcome;
import com.plantcare.core.metrics.MetricsService.FailureReason;
import com.plantcare.core.metrics.MetricsService.TelegramErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-тесты для {@link MetricsService}. Используем in-memory
 * {@link SimpleMeterRegistry} — никакого Spring контекста, никакого
 * Prometheus-формата. Проверяем именно «вызов метода кладёт правильный
 * counter с правильными тэгами».
 */
@DisplayName("MetricsService — unit-тесты")
class MetricsServiceTest {

    private SimpleMeterRegistry registry;
    private MetricsService metricsService;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metricsService = new MetricsService(registry);

        // Имитируем PostConstruct, т.к. без него gauge USERS_ACTIVE_DAU не зарегистрирован.
        ReflectionTestUtils.invokeMethod(metricsService, "registerGauges");
    }

    @Nested
    @DisplayName("recordNotificationSent")
    class NotificationSent {

        @Test
        void should_increment_counter_with_channel_and_task_type_tags_when_called() {
            metricsService.recordNotificationSent(MetricsService.CHANNEL_TELEGRAM, TaskType.WATERING);

            Counter counter = registry.find(MetricsService.NOTIFICATIONS_SENT)
                    .tag("channel", "telegram")
                    .tag("task_type", "WATERING")
                    .counter();

            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1.0);
        }

        @Test
        void should_increment_separate_counters_for_different_task_types() {
            metricsService.recordNotificationSent("telegram", TaskType.WATERING);
            metricsService.recordNotificationSent("telegram", TaskType.WATERING);
            metricsService.recordNotificationSent("telegram", TaskType.MISTING);

            Counter watering = registry.find(MetricsService.NOTIFICATIONS_SENT)
                    .tag("task_type", "WATERING").counter();
            Counter misting = registry.find(MetricsService.NOTIFICATIONS_SENT)
                    .tag("task_type", "MISTING").counter();

            assertThat(watering.count()).isEqualTo(2.0);
            assertThat(misting.count()).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("recordNotificationFailed")
    class NotificationFailed {

        @Test
        void should_increment_counter_with_channel_and_reason_tags_when_called() {
            metricsService.recordNotificationFailed(MetricsService.CHANNEL_TELEGRAM, FailureReason.RATE_LIMIT);

            Counter counter = registry.find(MetricsService.NOTIFICATIONS_FAILED)
                    .tag("channel", "telegram")
                    .tag("reason", "rate_limit")
                    .counter();

            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1.0);
        }

        @Test
        void should_map_all_failure_reasons_to_distinct_tag_values() {
            metricsService.recordNotificationFailed("telegram", FailureReason.RATE_LIMIT);
            metricsService.recordNotificationFailed("telegram", FailureReason.API_ERROR);
            metricsService.recordNotificationFailed("telegram", FailureReason.BLOCKED);
            metricsService.recordNotificationFailed("telegram", FailureReason.OTHER);

            assertThat(registry.find(MetricsService.NOTIFICATIONS_FAILED).tag("reason", "rate_limit").counter().count()).isEqualTo(1.0);
            assertThat(registry.find(MetricsService.NOTIFICATIONS_FAILED).tag("reason", "api_error").counter().count()).isEqualTo(1.0);
            assertThat(registry.find(MetricsService.NOTIFICATIONS_FAILED).tag("reason", "blocked").counter().count()).isEqualTo(1.0);
            assertThat(registry.find(MetricsService.NOTIFICATIONS_FAILED).tag("reason", "other").counter().count()).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("recordDigestSent")
    class DigestSent {

        @Test
        void should_increment_digest_counter_when_called() {
            metricsService.recordDigestSent();
            metricsService.recordDigestSent();
            metricsService.recordDigestSent();

            Counter counter = registry.find(MetricsService.NOTIFICATIONS_DIGEST_SENT).counter();

            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(3.0);
        }
    }

    @Nested
    @DisplayName("recordTelegramApiError")
    class TelegramApiError {

        @Test
        void should_record_429_with_rate_limited_code_tag_when_message_contains_429() {
            metricsService.recordTelegramApiError(TelegramErrorCode.RATE_LIMITED);

            Counter counter = registry.find(MetricsService.TELEGRAM_API_ERRORS)
                    .tag("code", "429")
                    .counter();

            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1.0);
        }

        @Test
        void should_map_unknown_codes_to_other_tag() {
            metricsService.recordTelegramApiError(TelegramErrorCode.OTHER);

            Counter counter = registry.find(MetricsService.TELEGRAM_API_ERRORS)
                    .tag("code", "other")
                    .counter();

            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1.0);
        }

        @Test
        void should_parse_429_from_message_string() {
            assertThat(TelegramErrorCode.fromMessage("[429] Too Many Requests"))
                    .isEqualTo(TelegramErrorCode.RATE_LIMITED);
        }

        @Test
        void should_parse_403_from_message_string() {
            assertThat(TelegramErrorCode.fromMessage("Forbidden: bot was blocked by the user [403]"))
                    .isEqualTo(TelegramErrorCode.FORBIDDEN);
        }

        @Test
        void should_parse_400_from_message_string() {
            assertThat(TelegramErrorCode.fromMessage("[400] Bad Request: chat not found"))
                    .isEqualTo(TelegramErrorCode.BAD_REQUEST);
        }

        @Test
        void should_fall_back_to_other_when_message_has_no_known_code() {
            assertThat(TelegramErrorCode.fromMessage("Network timeout"))
                    .isEqualTo(TelegramErrorCode.OTHER);
        }

        @Test
        void should_fall_back_to_other_when_message_is_null() {
            assertThat(TelegramErrorCode.fromMessage(null))
                    .isEqualTo(TelegramErrorCode.OTHER);
        }
    }

    @Nested
    @DisplayName("recordCallback")
    class Callback {

        @Test
        void should_increment_counter_with_action_and_outcome_tags_when_called() {
            metricsService.recordCallback("done", CallbackOutcome.OK);

            Counter counter = registry.find(MetricsService.CALLBACKS_PROCESSED)
                    .tag("action", "done")
                    .tag("outcome", "ok")
                    .counter();

            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1.0);
        }

        @Test
        void should_distinguish_outcomes_for_same_action() {
            metricsService.recordCallback("done", CallbackOutcome.OK);
            metricsService.recordCallback("done", CallbackOutcome.OK);
            metricsService.recordCallback("done", CallbackOutcome.IDEMPOTENT);
            metricsService.recordCallback("done", CallbackOutcome.ERROR);

            assertThat(registry.find(MetricsService.CALLBACKS_PROCESSED)
                    .tag("action", "done").tag("outcome", "ok").counter().count()).isEqualTo(2.0);
            assertThat(registry.find(MetricsService.CALLBACKS_PROCESSED)
                    .tag("action", "done").tag("outcome", "idempotent").counter().count()).isEqualTo(1.0);
            assertThat(registry.find(MetricsService.CALLBACKS_PROCESSED)
                    .tag("action", "done").tag("outcome", "error").counter().count()).isEqualTo(1.0);
        }

        @Test
        void should_map_unknown_callback_action_to_unknown_when_action_not_in_whitelist() {
            // Имитация атаки: клиент шлёт callback_data с произвольным action,
            // которого нет в whitelist'е. Не должно создаваться отдельного
            // counter'а с этим тэгом — иначе cardinality реестра не ограничена.
            metricsService.recordCallback("RANDOM_GARBAGE_xxxxx", CallbackOutcome.OK);

            Counter unknownCounter = registry.find(MetricsService.CALLBACKS_PROCESSED)
                    .tag("action", "unknown")
                    .tag("outcome", "ok")
                    .counter();
            Counter garbageCounter = registry.find(MetricsService.CALLBACKS_PROCESSED)
                    .tag("action", "RANDOM_GARBAGE_xxxxx")
                    .counter();

            assertThat(unknownCounter).isNotNull();
            assertThat(unknownCounter.count()).isEqualTo(1.0);
            assertThat(garbageCounter).isNull();
        }

        @Test
        @DisplayName("flow #118 actions в whitelist'е: счётчик с собственным тэгом, не unknown")
        void should_keep_flow_118_actions_as_own_tag_not_unknown() {
            // Без добавления в KNOWN_CALLBACK_ACTIONS эти 5 санитайзились бы в "unknown"
            // и метрика по веткам #118 была бы слепой.
            for (String action : new String[]{
                    "snooze_pick", "snooze_back", "back_care", "back_pick", "back_custom"}) {
                metricsService.recordCallback(action, CallbackOutcome.OK);

                Counter counter = registry.find(MetricsService.CALLBACKS_PROCESSED)
                        .tag("action", action)
                        .tag("outcome", "ok")
                        .counter();

                assertThat(counter)
                        .as("action '%s' должен иметь собственный counter, а не unknown", action)
                        .isNotNull();
                assertThat(counter.count()).isEqualTo(1.0);
            }

            // и в "unknown" ничего не утекло
            assertThat(registry.find(MetricsService.CALLBACKS_PROCESSED)
                    .tag("action", "unknown").counter()).isNull();
        }
    }

    @Nested
    @DisplayName("recordUserRegistered")
    class UserRegistered {

        @Test
        void should_increment_users_registered_total_when_called() {
            metricsService.recordUserRegistered();
            metricsService.recordUserRegistered();

            Counter counter = registry.find(MetricsService.USERS_REGISTERED_TOTAL).counter();

            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(2.0);
        }
    }

    @Nested
    @DisplayName("updateActiveDau / DAU gauge")
    class ActiveDau {

        @Test
        void should_expose_zero_via_gauge_when_no_value_set() {
            Gauge gauge = registry.find(MetricsService.USERS_ACTIVE_DAU).gauge();

            assertThat(gauge).isNotNull();
            assertThat(gauge.value()).isEqualTo(0.0);
        }

        @Test
        void should_expose_latest_value_via_gauge_after_update() {
            metricsService.updateActiveDau(42L);

            Gauge gauge = registry.find(MetricsService.USERS_ACTIVE_DAU).gauge();

            assertThat(gauge.value()).isEqualTo(42.0);
            assertThat(metricsService.getActiveDau()).isEqualTo(42L);
        }

        @Test
        void should_overwrite_previous_value_when_updated_repeatedly() {
            metricsService.updateActiveDau(100L);
            metricsService.updateActiveDau(7L);

            assertThat(registry.find(MetricsService.USERS_ACTIVE_DAU).gauge().value()).isEqualTo(7.0);
        }
    }

    @Nested
    @DisplayName("Scheduler tick timer")
    class SchedulerTickTimer {

        @Test
        void should_return_non_null_sample_when_started() {
            Timer.Sample sample = metricsService.startSchedulerTickTimer();

            assertThat(sample).isNotNull();
        }

        @Test
        void should_record_one_observation_in_timer_when_sample_stopped() {
            Timer.Sample sample = metricsService.startSchedulerTickTimer();

            metricsService.stopSchedulerTickTimer(sample);

            Timer timer = registry.find(MetricsService.SCHEDULER_TICK_DURATION).timer();

            assertThat(timer).isNotNull();
            assertThat(timer.count()).isEqualTo(1L);
            // Non-negative time totalTime — sanity check; не привязываемся к конкретной длительности.
            assertThat(timer.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS)).isGreaterThanOrEqualTo(0.0);
        }

        @Test
        void should_aggregate_multiple_ticks_in_same_timer() {
            metricsService.stopSchedulerTickTimer(metricsService.startSchedulerTickTimer());
            metricsService.stopSchedulerTickTimer(metricsService.startSchedulerTickTimer());
            metricsService.stopSchedulerTickTimer(metricsService.startSchedulerTickTimer());

            Timer timer = registry.find(MetricsService.SCHEDULER_TICK_DURATION).timer();

            assertThat(timer.count()).isEqualTo(3L);
        }
    }
}
