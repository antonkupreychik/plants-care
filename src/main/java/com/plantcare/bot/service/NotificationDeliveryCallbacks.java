package com.plantcare.bot.service;

import com.plantcare.core.domain.NotificationLog;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.NotificationType;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.metrics.MetricsService;
import com.plantcare.core.repository.NotificationLogRepository;
import com.plantcare.core.repository.PlantRepository;
import com.plantcare.core.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Колбэки доставки уведомлений (issue #29), вызываемые воркер-потоком очереди
 * {@code RateLimitedTelegramSender} ПОСЛЕ резолва отправки.
 *
 * <p>Так как воркер-поток не имеет транзакции шедулера, каждый метод открывает
 * собственную транзакцию ({@code @Transactional} через Spring-прокси: вызов идёт
 * из другого бина — самовызова нет). Сущности перерезолвливаются по примитивным
 * идентификаторам, которые шедулер захватил до постановки в очередь — никаких
 * detached-entity между потоками.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationDeliveryCallbacks {

    private final NotificationLogRepository notificationLogRepository;
    private final PlantRepository plantRepository;
    private final UserRepository userRepository;
    private final MetricsService metricsService;

    /**
     * Зафиксировать успешную отправку одиночного уведомления: дедуп-лог +
     * метрика sent. Дедуп-лог пишется по факту успешной отправки (как и в
     * синхронной версии), просто теперь — на воркер-потоке очереди. На текущем
     * масштабе очередь дренирует задолго до следующего тика (60с), так что окно
     * дедупа не нарушается.
     */
    @Transactional
    public void onSent(long plantId, TaskType taskType) {
        plantRepository.findById(plantId).ifPresentOrElse(
                plant -> {
                    saveNotificationLog(plant, taskType);
                    metricsService.recordNotificationSent(MetricsService.CHANNEL_TELEGRAM, taskType);
                },
                () -> log.info("Plant {} deleted before sent-callback, skipping dedup-log/metric", plantId));
    }

    /**
     * Зафиксировать успешную отправку дайджеста: по дедуп-логу на каждую задачу +
     * метрика sent на каждую + отдельный счётчик дайджестов. См. комментарий к
     * {@link #onSent} про порядок записи дедупа.
     */
    @Transactional
    public void onDigestSent(List<DigestLogItem> items) {
        for (DigestLogItem item : items) {
            plantRepository.findById(item.plantId()).ifPresentOrElse(
                    plant -> {
                        saveNotificationLog(plant, item.taskType());
                        metricsService.recordNotificationSent(MetricsService.CHANNEL_TELEGRAM, item.taskType());
                    },
                    () -> log.info("Plant {} deleted before digest-callback, skipping dedup-log/metric",
                            item.plantId()));
        }
        metricsService.recordDigestSent();
    }

    /**
     * Обработать неудачу отправки: на 403 помечает юзера blocked, иначе пишет
     * метрику соответствующей причины. Юзер загружается свежим по chatId —
     * detached-entity между потоками не тащим.
     */
    @Transactional
    public void onFailed(long chatId, TelegramApiException e) {
        String errorMessage = e.getMessage();
        MetricsService.TelegramErrorCode code = MetricsService.TelegramErrorCode.fromMessage(errorMessage);

        if (errorMessage != null && errorMessage.contains("403")) {
            userRepository.findByTelegramChatId(chatId).ifPresent(user -> {
                if (!user.isBlocked()) {
                    log.warn("Bot blocked by user {}, marking as blocked", chatId);
                    user.setBlocked(true);
                    userRepository.save(user);
                }
            });
            metricsService.recordNotificationFailed(
                    MetricsService.CHANNEL_TELEGRAM, MetricsService.FailureReason.BLOCKED);
        } else {
            log.error("Failed to send notification to chat {}: {}", chatId, errorMessage);
            MetricsService.FailureReason reason = switch (code) {
                case RATE_LIMITED -> MetricsService.FailureReason.RATE_LIMIT;
                case BAD_REQUEST, FORBIDDEN -> MetricsService.FailureReason.API_ERROR;
                case OTHER -> MetricsService.FailureReason.OTHER;
            };
            metricsService.recordNotificationFailed(MetricsService.CHANNEL_TELEGRAM, reason);
        }
    }

    private void saveNotificationLog(Plant plant, TaskType taskType) {
        NotificationLog logEntry = NotificationLog.builder()
                .plant(plant)
                .taskType(taskType)
                .notificationType(NotificationType.DUE)
                .sentAt(LocalDateTime.now())
                .build();
        notificationLogRepository.save(logEntry);
    }

    /** Идентификаторы одной задачи дайджеста для записи дедуп-лога. */
    public record DigestLogItem(long plantId, TaskType taskType) {
    }
}
