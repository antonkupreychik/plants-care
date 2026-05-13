package com.plantcare.bot.service;

import com.plantcare.bot.domain.CareHistory;
import com.plantcare.bot.domain.CareSchedule;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.enums.TaskType;
import com.plantcare.bot.repository.CareHistoryRepository;
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
import java.time.format.DateTimeFormatter;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationCallbackService {

    private static final int SNOOZE_HOURS = 2;
    private static final int DEDUP_SECONDS = 60;
    private static final int GRACE_PERIOD_HOURS = 24;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final CareScheduleRepository careScheduleRepository;
    private final CareHistoryRepository careHistoryRepository;

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
        Plant plant = schedule.getPlant();

        if (plant.isArchived()) {
            editMessage(client, chatId, messageId, "Растение уже удалено");
            answerCallback(client, callbackId, "Растение удалено");
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        String responseText;
        String alertText;

        switch (action) {
            case "done" -> {
                boolean marked = markScheduleDone(schedule, now);

                if (!marked) {
                    answerCallback(client, callbackId, "Уже отмечено!");
                    return;
                }

                String timeStr = now.format(TIME_FMT);
                String nextDateStr = schedule.getNextDueAt().format(DATE_FMT);
                String doneVerb = doneVerb(schedule.getTaskType());
                String nextLabel = nextLabel(schedule.getTaskType());

                responseText = "✅ " + doneVerb + " " + plant.getName() + " в " + timeStr + ".\n"
                        + nextLabel + " — " + nextDateStr;
                alertText = "Отмечено!";
            }
            case "snooze" -> {
                schedule.setNextDueAt(now.plusHours(SNOOZE_HOURS));
                careScheduleRepository.save(schedule);

                responseText = "⏰ " + plant.getName() + " — напомню через " + SNOOZE_HOURS + " часа";
                alertText = "Отложено!";
            }
            case "skip" -> {
                if (isDuplicateDone(plant, schedule, now)) {
                    answerCallback(client, callbackId, "Уже отмечено!");
                    return;
                }

                CareHistory history = CareHistory.builder()
                        .plant(plant)
                        .taskType(schedule.getTaskType())
                        .doneAt(now)
                        .onTime(false)
                        .note("skipped")
                        .build();

                careHistoryRepository.save(history);

                schedule.rescheduleFrom(now);
                careScheduleRepository.save(schedule);

                String nextDateStr = schedule.getNextDueAt().format(DATE_FMT);

                responseText = "❌ " + plant.getName() + " — пропущено.\n"
                        + "Следующий раз: " + nextDateStr;
                alertText = "Пропущено!";
            }
            default -> {
                answerCallback(client, callbackId, "❌ Неизвестное действие");
                return;
            }
        }

        editMessage(client, chatId, messageId, responseText);
        answerCallback(client, callbackId, alertText);
    }

    @Transactional
    public boolean markScheduleDone(CareSchedule schedule, LocalDateTime now) {
        Plant plant = schedule.getPlant();

        if (isDuplicateDone(plant, schedule, now)) {
            return false;
        }

        boolean wasOnTime = isOnTime(schedule.getNextDueAt(), now);

        CareHistory history = CareHistory.builder()
                .plant(plant)
                .taskType(schedule.getTaskType())
                .doneAt(now)
                .onTime(wasOnTime)
                .build();

        careHistoryRepository.save(history);

        schedule.rescheduleFrom(now);
        careScheduleRepository.save(schedule);

        return true;
    }

    private boolean isDuplicateDone(Plant plant, CareSchedule schedule, LocalDateTime now) {
        Optional<CareHistory> lastEntry = careHistoryRepository
                .findFirstByPlantIdAndTaskTypeOrderByDoneAtDesc(plant.getId(), schedule.getTaskType());

        if (lastEntry.isEmpty()) {
            return false;
        }

        LocalDateTime lastDoneAt = lastEntry.get().getDoneAt();
        return lastDoneAt.plusSeconds(DEDUP_SECONDS).isAfter(now);
    }

    private boolean isOnTime(LocalDateTime scheduledAt, LocalDateTime now) {
        return !now.isAfter(scheduledAt.plusHours(GRACE_PERIOD_HOURS));
    }

    private String doneVerb(TaskType taskType) {
        return switch (taskType) {
            case WATERING -> "Полил";
            case MISTING -> "Опрыскал";
            case FERTILIZING -> "Удобрил";
        };
    }

    private String nextLabel(TaskType taskType) {
        return switch (taskType) {
            case WATERING -> "Следующий полив";
            case MISTING -> "Следующее опрыскивание";
            case FERTILIZING -> "Следующее удобрение";
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