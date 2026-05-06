package com.plantcare.bot.state.impl;

import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.service.PlantService;
import com.plantcare.bot.service.UserService;
import com.plantcare.bot.state.interfaces.StateHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AwaitingPlantLastWateredStateHandler implements StateHandler {

    private final UserService userService;
    private final PlantService plantService;

    @Override
    public ConversationState getSupportedState() {
        return ConversationState.AWAITING_PLANT_LAST_WATERED;
    }

    @Override
    public void handle(User user, Update update, TelegramClient client) {
        Long chatId = user.getTelegramChatId();

        if (!update.hasCallbackQuery()) {
            return;
        }

        String callbackData = update.getCallbackQuery().getData();

        if (!callbackData.startsWith("LAST_WATERED:")) {
            return;
        }

        String lastWateredChoice = callbackData.substring("LAST_WATERED:".length());

        try {
            // Извлекаем данные из stateData
            Map<String, Object> stateData = user.getStateData();

            String speciesIdStr = (String) stateData.get("species_id");
            String intervalDaysStr = (String) stateData.get("interval_days");
            String plantName = (String) stateData.get("plant_name");

            Long speciesId = "null".equals(speciesIdStr) ? null : Long.parseLong(speciesIdStr);

            var defaultIntervalDays = 0;
            if (intervalDaysStr == null || intervalDaysStr.isEmpty()) {
                defaultIntervalDays = plantService.getSpeciesById(speciesId)
                        .get()
                        .getWateringDays();
            }
            var intervalDays = !"null".equals(intervalDaysStr) ? defaultIntervalDays : Integer.parseInt(intervalDaysStr);

            // Парсим интервал с валидацией и значением по умолчанию
            // ВАЖНО: это гарантирует что interval_days никогда не будет 0 или null
            // что нарушало бы constraint chk_interval_positive в БД
            if (intervalDaysStr == null || intervalDaysStr.trim().isEmpty()) {
                // Если интервал не установлен - используем значение по умолчанию (7 дней)
                log.warn("interval_days not set for user {}, using default value 7",
                        user.getTelegramChatId());
                intervalDays = 7;
            } else {
                try {
                    intervalDays = Integer.parseInt(intervalDaysStr);
                    // Валидация: должен быть от 1 до 365
                    if (intervalDays <= 0 || intervalDays > 365) {
                        log.warn("interval_days {} out of range for user {}, using default 7",
                                intervalDays, user.getTelegramChatId());
                        intervalDays = 7;
                    }
                } catch (NumberFormatException e) {
                    log.warn("Failed to parse interval_days '{}' for user {}, using default 7",
                            intervalDaysStr, user.getTelegramChatId(), e);
                    intervalDays = 7;
                }
            }

            // Рассчитываем next_due_at
            LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
            LocalDateTime nextDueAt = calculateNextDueAt(now, lastWateredChoice, intervalDays);

            log.info("Creating plant: name={}, speciesId={}, interval={}, nextDueAt={}",
                    plantName, speciesId, intervalDays, nextDueAt);

            // Создаём растение и расписание
            Plant savedPlant = plantService.createPlantWithWateringSchedule(
                    user,
                    speciesId,
                    plantName,
                    intervalDays,
                    nextDueAt
            );

            // Отправляем подтверждение и возвращаемся в IDLE
            sendConfirmation(user, savedPlant, nextDueAt, client);
            userService.resetToIdle(user);

            log.info("Plant creation completed for user {}", chatId);

        } catch (Exception e) {
            log.error("Failed to create plant for user {}", chatId, e);

            SendMessage errorMsg = SendMessage.builder()
                    .chatId(chatId.toString())
                    .text("❌ Ошибка при сохранении растения. Попробуй ещё раз позже.")
                    .build();

            try {
                client.execute(errorMsg);
            } catch (TelegramApiException ex) {
                log.error("Failed to send error message", ex);
            }

            userService.resetToIdle(user);
        }

        try {
            client.execute(org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery.builder()
                    .callbackQueryId(update.getCallbackQuery().getId())
                    .build());
        } catch (Exception e) {
            log.error("Failed to answer callback", e);
        }
    }

    /**
     * Рассчитывает next_due_at на основе выбора юзера
     */
    private LocalDateTime calculateNextDueAt(LocalDateTime now, String choice, int intervalDays) {
        return switch (choice) {
            case "TODAY" -> now.plusDays(intervalDays);
            case "YESTERDAY" -> now.minusDays(1).plusDays(intervalDays);
            case "FORGOTTEN" -> now;  // Полить нужно прямо сейчас
            default -> throw new IllegalArgumentException("Unknown choice: " + choice);
        };
    }

    private void sendConfirmation(User user, Plant plant, LocalDateTime nextDueAt, TelegramClient client) {
        String nextDueFormatted = formatDate(nextDueAt);

        SendMessage message = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text(String.format(
                        "✅ Растение добавлено!\n\n" +
                        "🌿 *%s*\n" +
                        "💧 Следующий полив: %s\n\n" +
                        "Я буду напоминать тебе о поливе. Готово!",
                        plant.getName(),
                        nextDueFormatted
                ))
                .parseMode("Markdown")
                .build();

        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send confirmation message", e);
        }
    }

    private String formatDate(LocalDateTime dt) {
        int day = dt.getDayOfMonth();
        int month = dt.getMonthValue();
        int hour = dt.getHour();
        int minute = dt.getMinute();

        String[] monthNames = {"янв", "фев", "мар", "апр", "май", "июн",
                "июл", "авг", "сен", "окт", "ноя", "дек"};

        return String.format("%d %s в %02d:%02d", day, monthNames[month - 1], hour, minute);
    }
}