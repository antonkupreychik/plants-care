package com.plantcare.bot.service;

import com.plantcare.bot.client.TelegramClientProvider;
import com.plantcare.bot.domain.CareSchedule;
import com.plantcare.bot.domain.NotificationLog;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.NotificationType;
import com.plantcare.bot.domain.enums.TaskType;
import com.plantcare.bot.repository.CareScheduleRepository;
import com.plantcare.bot.repository.NotificationLogRepository;
import com.plantcare.bot.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationSchedulerService {

    private static final int DEDUP_HOURS = 12;

    private final CareScheduleRepository careScheduleRepository;
    private final NotificationLogRepository notificationLogRepository;
    private final UserRepository userRepository;
    private final TelegramClientProvider telegramClientProvider;

    @Scheduled(fixedRate = 60_000)
    @Transactional
    public void checkAndSendNotifications() {
        LocalDateTime now = LocalDateTime.now();
        List<CareSchedule> dueSchedules = careScheduleRepository.findDueSchedules(now);

        log.debug("Found {} due schedules", dueSchedules.size());

        for (CareSchedule schedule : dueSchedules) {
            try {
                processSchedule(schedule, now);
            } catch (Exception e) {
                log.error("Error processing schedule id={}: {}", schedule.getId(), e.getMessage(), e);
            }
        }
    }

    private void processSchedule(CareSchedule schedule, LocalDateTime now) {
        Plant plant = schedule.getPlant();
        User user = plant.getUser();

        // Проверка paused_until
        if (user.isPaused()) {
            log.debug("User {} is paused until {}, skipping", user.getTelegramChatId(), user.getPausedUntil());
            return;
        }

        // Проверка тихих часов
        if (isQuietHours(user, now)) {
            log.debug("Quiet hours for user {}, skipping", user.getTelegramChatId());
            return;
        }

        // Дедупликация: не слать, если за последние 12 часов уже отправляли
        LocalDateTime deduplicationCutoff = now.minusHours(DEDUP_HOURS);
        if (notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(
                plant.getId(), schedule.getTaskType(), deduplicationCutoff)) {
            log.debug("Already notified for plant {} / {} within {} hours, skipping",
                    plant.getId(), schedule.getTaskType(), DEDUP_HOURS);
            return;
        }

        // Отправка уведомления
        sendNotification(user, plant, schedule);
    }

    private boolean isQuietHours(User user, LocalDateTime now) {
        ZoneId userZone;
        try {
            userZone = ZoneId.of(user.getTimezone());
        } catch (Exception e) {
            log.warn("Invalid timezone '{}' for user {}, defaulting to UTC",
                    user.getTimezone(), user.getTelegramChatId());
            userZone = ZoneId.of("UTC");
        }

        ZonedDateTime userNow = now.atZone(ZoneId.systemDefault()).withZoneSameInstant(userZone);
        LocalTime userTime = userNow.toLocalTime();

        LocalTime start = user.getQuietHoursStart();
        LocalTime end = user.getQuietHoursEnd();

        if (start.equals(end)) {
            // Тихие часы отключены (start == end)
            return false;
        }

        if (start.isBefore(end)) {
            // Обычный интервал, например 14:00 – 16:00
            return !userTime.isBefore(start) && userTime.isBefore(end);
        } else {
            // Интервал через полночь, например 22:00 – 09:00
            return !userTime.isBefore(start) || userTime.isBefore(end);
        }
    }

    private void sendNotification(User user, Plant plant, CareSchedule schedule) {
        String text = buildNotificationText(plant, schedule.getTaskType());
        InlineKeyboardMarkup keyboard = buildKeyboard(schedule.getId(), schedule.getTaskType());

        SendMessage message = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text(text)
                .replyMarkup(keyboard)
                .build();

        try {
            telegramClientProvider.getTelegramClient().execute(message);

            // Записываем в лог
            NotificationLog logEntry = NotificationLog.builder()
                    .plant(plant)
                    .taskType(schedule.getTaskType())
                    .notificationType(NotificationType.DUE)
                    .sentAt(LocalDateTime.now())
                    .build();
            notificationLogRepository.save(logEntry);

            log.info("Sent notification for plant '{}' (id={}) to user {}",
                    plant.getName(), plant.getId(), user.getTelegramChatId());

        } catch (TelegramApiException e) {
            handleTelegramError(user, e);
        }
    }

    private String buildNotificationText(Plant plant, TaskType taskType) {
        return switch (taskType) {
            case WATERING -> "🌿 Пора полить: " + plant.getName();
            case MISTING -> "💨 Пора опрыскать: " + plant.getName();
            case FERTILIZING -> "🧪 Пора удобрить: " + plant.getName();
        };
    }

    private InlineKeyboardMarkup buildKeyboard(Long scheduleId, TaskType taskType) {
        String doneBtnLabel = switch (taskType) {
            case WATERING    -> "✅ Полил";
            case MISTING     -> "✅ Опрыскал";
            case FERTILIZING -> "✅ Удобрил";
        };

        InlineKeyboardButton doneBtn = InlineKeyboardButton.builder()
                .text(doneBtnLabel)
                .callbackData("v1:done:" + scheduleId)
                .build();

        InlineKeyboardButton snoozeBtn = InlineKeyboardButton.builder()
                .text("⏰ Через 2 часа")
                .callbackData("v1:snooze:" + scheduleId)
                .build();

        InlineKeyboardButton skipBtn = InlineKeyboardButton.builder()
                .text("❌ Пропустить")
                .callbackData("v1:skip:" + scheduleId)
                .build();

        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(doneBtn, snoozeBtn, skipBtn))
                .build();
    }

    private void handleTelegramError(User user, TelegramApiException e) {
        String errorMessage = e.getMessage();
        if (errorMessage != null && errorMessage.contains("403")) {
            log.warn("Bot blocked by user {}, marking as blocked", user.getTelegramChatId());
            user.setBlocked(true);
            userRepository.save(user);
        } else {
            log.error("Failed to send notification to user {}: {}",
                    user.getTelegramChatId(), errorMessage, e);
        }
    }
}