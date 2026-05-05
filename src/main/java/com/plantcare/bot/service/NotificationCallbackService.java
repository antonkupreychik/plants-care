package com.plantcare.bot.service;

import com.plantcare.bot.domain.CareHistory;
import com.plantcare.bot.domain.CareSchedule;
import com.plantcare.bot.domain.Plant;
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

        // Растение удалено (архивировано) — graceful 404
        if (plant.isArchived()) {
            editMessage(client, chatId, messageId, "🗑 Растение уже удалено");
            answerCallback(client, callbackId, "Растение удалено");
            log.info("Callback for archived plant id={}, scheduleId={}", plant.getId(), scheduleId);
            return;
        }

        String plantName = plant.getName();
        LocalDateTime now = LocalDateTime.now();

        String responseText;
        String alertText;

        switch (action) {
            case "done" -> {
                // Дедупликация: проверяем, не было ли записи за последние 60 секунд
                if (isDuplicateDone(plant, schedule, now)) {
                    answerCallback(client, callbackId, "Уже отмечено!");
                    log.debug("Duplicate done callback for schedule {}, ignoring", scheduleId);
                    return;
                }

                // was_on_time: true если now <= scheduled_at + 24h grace period
                boolean wasOnTime = isOnTime(schedule.getNextDueAt(), now);

                // Записываем в историю
                CareHistory history = CareHistory.builder()
                        .plant(plant)
                        .taskType(schedule.getTaskType())
                        .doneAt(now)
                        .onTime(wasOnTime)
                        .build();
                careHistoryRepository.save(history);

                // Пересчитываем next_due_at от фактического времени выполнения
                schedule.rescheduleFrom(now);
                careScheduleRepository.save(schedule);

                String timeStr = now.format(TIME_FMT);
                String nextDateStr = schedule.getNextDueAt().format(DATE_FMT);
                responseText = "✅ Полил " + plantName + " в " + timeStr
                        + ". Следующий полив — " + nextDateStr;
                alertText = "Отмечено!";
                log.info("Schedule {} marked as done (on_time={}), next due at {}",
                        scheduleId, wasOnTime, schedule.getNextDueAt());
            }
            case "snooze" -> {
                schedule.setNextDueAt(now.plusHours(SNOOZE_HOURS));
                careScheduleRepository.save(schedule);
                responseText = "⏰ " + plantName + " — напомню через " + SNOOZE_HOURS + " часа";
                alertText = "Отложено!";
                log.info("Schedule {} snoozed, next due at {}", scheduleId, schedule.getNextDueAt());
            }
            case "skip" -> {
                // Дедупликация: проверяем, не было ли записи за последние 60 секунд
                if (isDuplicateDone(plant, schedule, now)) {
                    answerCallback(client, callbackId, "Уже отмечено!");
                    log.debug("Duplicate skip callback for schedule {}, ignoring", scheduleId);
                    return;
                }

                // Записываем в историю: was_on_time = false, note = "skipped"
                CareHistory history = CareHistory.builder()
                        .plant(plant)
                        .taskType(schedule.getTaskType())
                        .doneAt(now)
                        .onTime(false)
                        .note("skipped")
                        .build();
                careHistoryRepository.save(history);

                // Пересчитываем next_due_at от now()
                schedule.rescheduleFrom(now);
                careScheduleRepository.save(schedule);

                String nextDateStr = schedule.getNextDueAt().format(DATE_FMT);
                responseText = "❌ " + plantName + " — пропущено. Следующий раз: " + nextDateStr;
                alertText = "Пропущено!";
                log.info("Schedule {} skipped, next due at {}", scheduleId, schedule.getNextDueAt());
            }
            default -> {
                answerCallback(client, callbackId, "❌ Неизвестное действие");
                return;
            }
        }

        editMessage(client, chatId, messageId, responseText);
        answerCallback(client, callbackId, alertText);
    }

    /**
     * Проверяет, была ли запись в CareHistory за последние {@value DEDUP_SECONDS} секунд
     * для данного растения и типа задачи. Если да — это дубликат нажатия.
     */
    private boolean isDuplicateDone(Plant plant, CareSchedule schedule, LocalDateTime now) {
        Optional<CareHistory> lastEntry = careHistoryRepository
                .findFirstByPlantIdAndTaskTypeOrderByDoneAtDesc(plant.getId(), schedule.getTaskType());
        if (lastEntry.isEmpty()) {
            return false;
        }
        LocalDateTime lastDoneAt = lastEntry.get().getDoneAt();
        return lastDoneAt.plusSeconds(DEDUP_SECONDS).isAfter(now);
    }

    /**
     * was_on_time: true, если фактическое время выполнения (now) не позже
     * запланированного времени + 24 часа grace period.
     */
    private boolean isOnTime(LocalDateTime scheduledAt, LocalDateTime now) {
        return !now.isAfter(scheduledAt.plusHours(GRACE_PERIOD_HOURS));
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