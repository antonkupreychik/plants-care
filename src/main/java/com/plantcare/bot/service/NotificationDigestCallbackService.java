package com.plantcare.bot.service;

import com.plantcare.core.domain.CareSchedule;
import com.plantcare.core.domain.DigestTaskItem;
import com.plantcare.core.domain.NotificationDigest;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.metrics.MetricsService;
import com.plantcare.core.metrics.MetricsService.CallbackOutcome;
import com.plantcare.core.repository.CareScheduleRepository;
import com.plantcare.core.repository.NotificationDigestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationDigestCallbackService {

    private final NotificationDigestRepository notificationDigestRepository;
    private final CareScheduleRepository careScheduleRepository;
    private final NotificationCallbackService notificationCallbackService;
    private final MetricsService metricsService;

    @Transactional
    public void handleCallback(CallbackQuery callbackQuery, TelegramClient client) {
        String data = callbackQuery.getData();
        String callbackId = callbackQuery.getId();
        Long chatId = callbackQuery.getMessage().getChatId();
        Integer messageId = callbackQuery.getMessage().getMessageId();

        String[] parts = data.split(":");

        if (parts.length != 3) {
            answerCallback(client, callbackId, "❌ Неизвестная команда");
            metricsService.recordCallback("digest_unknown", CallbackOutcome.ERROR);
            return;
        }

        String action = parts[1];
        String metricAction = "digest_" + action;

        Long digestId;
        try {
            digestId = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            answerCallback(client, callbackId, "❌ Неверный ID");
            metricsService.recordCallback(metricAction, CallbackOutcome.ERROR);
            return;
        }

        Optional<NotificationDigest> optionalDigest = notificationDigestRepository.findById(digestId);

        if (optionalDigest.isEmpty()) {
            answerCallback(client, callbackId, "❌ Дайджест не найден");
            metricsService.recordCallback(metricAction, CallbackOutcome.ERROR);
            return;
        }

        NotificationDigest digest = optionalDigest.get();

        switch (action) {
            case "done_all" -> handleDoneAll(digest, client, callbackId, chatId, messageId);
            case "expand" -> handleExpand(digest, client, callbackId, chatId, messageId);
            default -> {
                answerCallback(client, callbackId, "❌ Неизвестное действие");
                metricsService.recordCallback(metricAction, CallbackOutcome.ERROR);
            }
        }
    }

    private void handleDoneAll(
            NotificationDigest digest,
            TelegramClient client,
            String callbackId,
            Long chatId,
            Integer messageId
    ) {
        LocalDateTime now = LocalDateTime.now();
        int markedCount = 0;

        for (DigestTaskItem item : digest.getPlantTaskIds()) {
            Optional<CareSchedule> optionalSchedule = careScheduleRepository.findById(item.scheduleId());

            if (optionalSchedule.isEmpty()) {
                continue;
            }

            CareSchedule schedule = optionalSchedule.get();

            if (schedule.getPlant().isArchived()) {
                continue;
            }

            boolean marked = notificationCallbackService.markScheduleDone(schedule, now);

            if (marked) {
                markedCount++;
            }
        }

        if (markedCount == 0) {
            answerCallback(client, callbackId, "Уже отмечено");
            metricsService.recordCallback("digest_done_all", CallbackOutcome.IDEMPOTENT);
            return;
        }

        editMessage(client, chatId, messageId, "✅ Все задачи из дайджеста отмечены как выполненные");
        answerCallback(client, callbackId, "Готово!");
        metricsService.recordCallback("digest_done_all", CallbackOutcome.OK);
    }

    private void handleExpand(
            NotificationDigest digest,
            TelegramClient client,
            String callbackId,
            Long chatId,
            Integer messageId
    ) {
        List<DigestTaskItem> remainingTasks = getRemainingTasks(digest);

        if (remainingTasks.isEmpty()) {
            editMessage(client, chatId, messageId, "✅ Все задачи уже выполнены");
            answerCallback(client, callbackId, "Все задачи выполнены");
            metricsService.recordCallback("digest_expand", CallbackOutcome.IDEMPOTENT);
            return;
        }

        String text = buildExpandText(remainingTasks);
        InlineKeyboardMarkup keyboard = buildExpandKeyboard(remainingTasks);

        EditMessageText edit = EditMessageText.builder()
                .chatId(chatId.toString())
                .messageId(messageId)
                .text(text)
                .replyMarkup(keyboard)
                .build();

        try {
            client.execute(edit);
            answerCallback(client, callbackId, "Открыто по одному");
            metricsService.recordCallback("digest_expand", CallbackOutcome.OK);
        } catch (TelegramApiException e) {
            log.error("Failed to expand digest: {}", e.getMessage(), e);
            answerCallback(client, callbackId, "❌ Не удалось открыть список");
            metricsService.recordCallback("digest_expand", CallbackOutcome.ERROR);
        }
    }

    private List<DigestTaskItem> getRemainingTasks(NotificationDigest digest) {
        List<DigestTaskItem> result = new ArrayList<>();

        for (DigestTaskItem item : digest.getPlantTaskIds()) {
            Optional<CareSchedule> optionalSchedule = careScheduleRepository.findById(item.scheduleId());

            if (optionalSchedule.isEmpty()) {
                continue;
            }

            CareSchedule schedule = optionalSchedule.get();

            if (schedule.getPlant().isArchived()) {
                continue;
            }

            if (schedule.getNextDueAt().isAfter(item.scheduledAt())) {
                continue;
            }

            result.add(item);
        }

        return result;
    }

    private String buildExpandText(List<DigestTaskItem> tasks) {
        StringBuilder builder = new StringBuilder("На сегодня:\n");

        for (DigestTaskItem task : tasks) {
            builder.append("• ")
                    .append(task.plantName())
                    .append(" — ")
                    .append(taskLabel(task.taskType()))
                    .append("\n");
        }

        return builder.toString().trim();
    }

    private InlineKeyboardMarkup buildExpandKeyboard(List<DigestTaskItem> tasks) {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        for (DigestTaskItem task : tasks) {
            InlineKeyboardButton doneButton = InlineKeyboardButton.builder()
                    .text("✅ Сделал")
                    .callbackData("v1:done:" + task.scheduleId())
                    .build();

            InlineKeyboardButton snoozeButton = InlineKeyboardButton.builder()
                    .text("⏰ Отложить")
                    .callbackData("v1:snooze:" + task.scheduleId())
                    .build();

            rows.add(new InlineKeyboardRow(doneButton, snoozeButton));
        }

        return InlineKeyboardMarkup.builder()
                .keyboard(rows)
                .build();
    }

    private String taskLabel(TaskType taskType) {
        return switch (taskType) {
            case WATERING -> "полить";
            case MISTING -> "опрыскать";
            case FERTILIZING -> "удобрить";
            case SOIL_CHECK -> "проверить грунт";
        };
    }

    private void editMessage(TelegramClient client, Long chatId, Integer messageId, String text) {
        EditMessageText edit = EditMessageText.builder()
                .chatId(chatId.toString())
                .messageId(messageId)
                .text(text)
                .replyMarkup(null)
                .build();

        try {
            client.execute(edit);
        } catch (TelegramApiException e) {
            log.error("Failed to edit message: {}", e.getMessage(), e);
        }
    }

    private void answerCallback(TelegramClient client, String callbackId, String text) {
        try {
            client.execute(AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackId)
                    .text(text)
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to answer callback: {}", e.getMessage(), e);
        }
    }
}