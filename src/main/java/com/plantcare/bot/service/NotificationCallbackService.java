package com.plantcare.bot.service;

import com.plantcare.bot.domain.CareSchedule;
import com.plantcare.bot.repository.CareScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Обрабатывает callback'и от inline-кнопок уведомлений.
 * Формат callback_data: v1:{action}:{scheduleId}
 * Действия: done, snooze, skip
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationCallbackService {

    private static final int SNOOZE_HOURS = 2;

    private final CareScheduleRepository careScheduleRepository;

    @Transactional
    public void handleCallback(CallbackQuery callbackQuery, TelegramClient client) {
        String data = callbackQuery.getData();
        String callbackId = callbackQuery.getId();
        Long chatId = callbackQuery.getMessage().getChatId();
        Integer messageId = callbackQuery.getMessage().getMessageId();

        String[] parts = data.split(":");
        if (parts.length != 3) {
            answerCallback(client, callbackId, "❌ Неизвестная команда");
            return;
        }

        String action = parts[1];
        Long scheduleId;
        try {
            scheduleId = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            answerCallback(client, callbackId, "❌ Неверный ID");
            return;
        }

        Optional<CareSchedule> optSchedule = careScheduleRepository.findById(scheduleId);
        if (optSchedule.isEmpty()) {
            answerCallback(client, callbackId, "❌ Расписание не найдено");
            return;
        }

        CareSchedule schedule = optSchedule.get();
        String plantName = schedule.getPlant().getName();
        LocalDateTime now = LocalDateTime.now();

        String responseText;
        String alertText;

        switch (action) {
            case "done" -> {
                schedule.rescheduleFrom(now);
                careScheduleRepository.save(schedule);
                responseText = "✅ " + plantName + " — готово! Следующий раз: " + formatDate(schedule.getNextDueAt());
                alertText = "Отмечено!";
                log.info("Schedule {} marked as done, next due at {}", scheduleId, schedule.getNextDueAt());
            }
            case "snooze" -> {
                schedule.setNextDueAt(now.plusHours(SNOOZE_HOURS));
                careScheduleRepository.save(schedule);
                responseText = "⏰ " + plantName + " — напомню через " + SNOOZE_HOURS + " часа";
                alertText = "Отложено!";
                log.info("Schedule {} snoozed, next due at {}", scheduleId, schedule.getNextDueAt());
            }
            case "skip" -> {
                schedule.rescheduleFrom(now);
                careScheduleRepository.save(schedule);
                responseText = "❌ " + plantName + " — пропущено. Следующий раз: " + formatDate(schedule.getNextDueAt());
                alertText = "Пропущено!";
                log.info("Schedule {} skipped, next due at {}", scheduleId, schedule.getNextDueAt());
            }
            default -> {
                answerCallback(client, callbackId, "❌ Неизвестное действие");
                return;
            }
        }

        // Убираем кнопки, обновляем текст сообщения
        editMessage(client, chatId, messageId, responseText);
        answerCallback(client, callbackId, alertText);
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

    private String formatDate(LocalDateTime dateTime) {
        return dateTime.toLocalDate().toString() + " " +
                String.format("%02d:%02d", dateTime.getHour(), dateTime.getMinute());
    }
}