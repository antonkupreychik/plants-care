package com.plantcare.bot.state.impl;

import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.ConversationState;
import com.plantcare.core.service.LocationService;
import com.plantcare.core.service.PlantService;
import com.plantcare.core.service.UserService;
import com.plantcare.bot.state.interfaces.StateHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AwaitingPlantLastWateredStateHandler implements StateHandler {

    private final UserService userService;
    private final PlantService plantService;
    private final LocationService locationService;

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
            Map<String, Object> stateData = user.getStateData();

            String speciesIdStr = (String) stateData.get("species_id");
            String intervalDaysStr = (String) stateData.get("interval_days");
            String plantName = (String) stateData.get("plant_name");

            Long speciesId = parseSpeciesId(speciesIdStr);
            int intervalDays = resolveIntervalDays(user, speciesId, intervalDaysStr);

            LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
            LocalDateTime nextDueAt = calculateNextDueAt(now, lastWateredChoice, intervalDays);

            userService.setStateData(user, "interval_days", String.valueOf(intervalDays));
            userService.setStateData(user, "next_due_at", nextDueAt.toString());
            userService.updateState(user, ConversationState.AWAITING_PLANT_ROOM);

            log.info(
                    "Watering data prepared for user {}: plantName={}, speciesId={}, intervalDays={}, nextDueAt={}",
                    chatId,
                    plantName,
                    speciesId,
                    intervalDays,
                    nextDueAt
            );

            sendLocationQuestion(user, client);

        } catch (Exception e) {
            log.error("Failed to prepare plant location step for user {}", chatId, e);

            SendMessage errorMsg = SendMessage.builder()
                    .chatId(chatId.toString())
                    .text("❌ Ошибка при подготовке растения. Попробуй ещё раз позже.")
                    .build();

            try {
                client.execute(errorMsg);
            } catch (TelegramApiException ex) {
                log.error("Failed to send error message", ex);
            }

            userService.resetToIdle(user);
        }

        answerCallback(update, client);
    }

    private Long parseSpeciesId(String speciesIdStr) {
        if (speciesIdStr == null || speciesIdStr.isBlank() || "null".equals(speciesIdStr)) {
            return null;
        }

        return Long.parseLong(speciesIdStr);
    }

    private int resolveIntervalDays(User user, Long speciesId, String intervalDaysStr) {
        if (intervalDaysStr == null || intervalDaysStr.trim().isEmpty() || "null".equals(intervalDaysStr)) {
            if (speciesId != null) {
                return plantService.getSpeciesById(speciesId)
                        .map(species -> {
                            Integer wateringDays = species.getWateringDays();

                            if (wateringDays == null || wateringDays <= 0 || wateringDays > 365) {
                                return 7;
                            }

                            return wateringDays;
                        })
                        .orElse(7);
            }

            return 7;
        }

        try {
            int intervalDays = Integer.parseInt(intervalDaysStr);

            if (intervalDays <= 0 || intervalDays > 365) {
                log.warn(
                        "interval_days {} out of range for user {}, using default 7",
                        intervalDays,
                        user.getTelegramChatId()
                );

                return 7;
            }

            return intervalDays;
        } catch (NumberFormatException e) {
            log.warn(
                    "Failed to parse interval_days '{}' for user {}, using default 7",
                    intervalDaysStr,
                    user.getTelegramChatId(),
                    e
            );

            return 7;
        }
    }

    private LocalDateTime calculateNextDueAt(LocalDateTime now, String choice, int intervalDays) {
        return switch (choice) {
            case "TODAY" -> now.plusDays(intervalDays);
            case "YESTERDAY" -> now.minusDays(1).plusDays(intervalDays);
            case "FORGOTTEN" -> now;
            default -> throw new IllegalArgumentException("Unknown choice: " + choice);
        };
    }

    private void sendLocationQuestion(User user, TelegramClient client) {
        SendMessage message = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text("📍 Где оно стоит?")
                .replyMarkup(buildLocationKeyboard(user))
                .build();

        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send location question", e);
        }
    }

    private InlineKeyboardMarkup buildLocationKeyboard(User user) {
        InlineKeyboardMarkup.InlineKeyboardMarkupBuilder builder = InlineKeyboardMarkup.builder();

        List<Location> locations = locationService.getUserLocations(user.getId());

        for (Location location : locations) {
            builder.keyboardRow(new InlineKeyboardRow(List.of(
                    InlineKeyboardButton.builder()
                            .text(location.getDisplayName())
                            .callbackData("ADD_PLANT_LOCATION:" + location.getId())
                            .build()
            )));
        }

        builder.keyboardRow(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("➕ Новая локация")
                        .callbackData("ADD_PLANT_LOCATION_CREATE")
                        .build()
        )));

        builder.keyboardRow(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("Пропустить")
                        .callbackData("ADD_PLANT_LOCATION:SKIP")
                        .build()
        )));

        return builder.build();
    }

    private void answerCallback(Update update, TelegramClient client) {
        try {
            client.execute(AnswerCallbackQuery.builder()
                    .callbackQueryId(update.getCallbackQuery().getId())
                    .build());
        } catch (Exception e) {
            log.error("Failed to answer callback", e);
        }
    }
}