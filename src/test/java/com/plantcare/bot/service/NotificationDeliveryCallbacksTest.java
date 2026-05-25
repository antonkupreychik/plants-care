package com.plantcare.bot.service;

import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.NotificationLog;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.NotificationType;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.metrics.MetricsService;
import com.plantcare.core.repository.LocationRepository;
import com.plantcare.core.repository.NotificationLogRepository;
import com.plantcare.core.repository.PlantRepository;
import com.plantcare.core.repository.UserRepository;
import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.verify;

/**
 * Интеграционные тесты {@link NotificationDeliveryCallbacks} (issue #29) на реальном
 * Postgres через Testcontainers. Колбэки доставки пишут дедуп-лог и помечают юзера
 * blocked — это касается БД, поэтому репозитории НЕ мокаются (CLAUDE.md). Замокан
 * только {@link MetricsService} (внешний счётчик, не БД).
 */
@DisplayName("NotificationDeliveryCallbacks — bookkeeping доставки (issue #29)")
class NotificationDeliveryCallbacksTest extends IntegrationTestBase {

    @Autowired private NotificationDeliveryCallbacks callbacks;
    @Autowired private UserRepository userRepository;
    @Autowired private LocationRepository locationRepository;
    @Autowired private PlantRepository plantRepository;
    @Autowired private NotificationLogRepository notificationLogRepository;

    @MockitoBean private MetricsService metricsService;

    @AfterEach
    void cleanup() {
        notificationLogRepository.deleteAll();
        plantRepository.deleteAll();
        locationRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("should_write_dedup_log_and_record_metric_when_on_sent")
    void should_write_dedup_log_and_record_metric_when_on_sent() {
        // arrange
        Plant plant = savedPlant(savedUser(100L));

        // act
        callbacks.onSent(plant.getId(), TaskType.WATERING);

        // assert
        List<NotificationLog> logs = notificationLogRepository.findAll();
        assertThat(logs).hasSize(1);
        NotificationLog log = logs.get(0);
        assertThat(log.getPlant().getId()).isEqualTo(plant.getId());
        assertThat(log.getTaskType()).isEqualTo(TaskType.WATERING);
        assertThat(log.getNotificationType()).isEqualTo(NotificationType.DUE);

        verify(metricsService).recordNotificationSent(MetricsService.CHANNEL_TELEGRAM, TaskType.WATERING);
    }

    @Test
    @DisplayName("should_write_one_log_per_task_and_record_digest_when_on_digest_sent")
    void should_write_one_log_per_task_and_record_digest_when_on_digest_sent() {
        // arrange
        Plant plant = savedPlant(savedUser(101L));
        List<NotificationDeliveryCallbacks.DigestLogItem> items = List.of(
                new NotificationDeliveryCallbacks.DigestLogItem(plant.getId(), TaskType.WATERING),
                new NotificationDeliveryCallbacks.DigestLogItem(plant.getId(), TaskType.MISTING));

        // act
        callbacks.onDigestSent(items);

        // assert
        assertThat(notificationLogRepository.findAll())
                .extracting(NotificationLog::getTaskType)
                .containsExactlyInAnyOrder(TaskType.WATERING, TaskType.MISTING);

        verify(metricsService).recordNotificationSent(MetricsService.CHANNEL_TELEGRAM, TaskType.WATERING);
        verify(metricsService).recordNotificationSent(MetricsService.CHANNEL_TELEGRAM, TaskType.MISTING);
        verify(metricsService).recordDigestSent();
    }

    @Test
    @DisplayName("should_mark_user_blocked_and_record_blocked_when_on_failed_with_403")
    void should_mark_user_blocked_and_record_blocked_when_on_failed_with_403() {
        // arrange
        User user = savedUser(102L);
        assertThat(user.isBlocked()).isFalse();

        // act
        callbacks.onFailed(user.getTelegramChatId(),
                new TelegramApiException("Forbidden: bot was blocked by the user [403]"));

        // assert
        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.isBlocked()).isTrue();
        verify(metricsService).recordNotificationFailed(
                MetricsService.CHANNEL_TELEGRAM, MetricsService.FailureReason.BLOCKED);
    }

    @Test
    @DisplayName("should_not_block_user_and_record_other_when_on_failed_with_non_403")
    void should_not_block_user_and_record_other_when_on_failed_with_non_403() {
        // arrange
        User user = savedUser(103L);

        // act
        callbacks.onFailed(user.getTelegramChatId(), new TelegramApiException("Network timeout"));

        // assert
        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.isBlocked()).isFalse();
        verify(metricsService).recordNotificationFailed(
                MetricsService.CHANNEL_TELEGRAM, MetricsService.FailureReason.OTHER);
    }

    @Test
    @DisplayName("should_not_throw_when_on_failed_and_user_not_found_by_chat_id")
    void should_not_throw_when_on_failed_and_user_not_found_by_chat_id() {
        // arrange: ни одного юзера с таким chatId нет.
        long unknownChatId = 999_999L;

        // act + assert: отсутствие записи не должно ронять колбэк, метрика всё равно пишется.
        assertThatNoException().isThrownBy(() ->
                callbacks.onFailed(unknownChatId,
                        new TelegramApiException("Forbidden: bot was blocked by the user [403]")));

        verify(metricsService).recordNotificationFailed(
                MetricsService.CHANNEL_TELEGRAM, MetricsService.FailureReason.BLOCKED);
    }

    // ===== helpers =====

    private User savedUser(long chatId) {
        return userRepository.save(User.builder()
                .telegramChatId(chatId)
                .timezone("UTC")
                .blocked(false)
                .build());
    }

    private Plant savedPlant(User user) {
        Location location = locationRepository.save(Location.builder()
                .user(user)
                .name(Location.DEFAULT_NAME)
                .emoji(Location.DEFAULT_EMOJI)
                .defaultLocation(true)
                .build());
        return plantRepository.save(Plant.builder()
                .user(user)
                .location(location)
                .name("Монстера")
                .build());
    }
}
