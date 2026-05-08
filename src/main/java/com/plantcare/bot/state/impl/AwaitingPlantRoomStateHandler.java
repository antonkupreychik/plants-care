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
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AwaitingPlantRoomStateHandler implements StateHandler {

    private final UserService userService;
    private final PlantService plantService;

    @Override
    public ConversationState getSupportedState() {
        return ConversationState.AWAITING_PLANT_ROOM;
    }

    @Override
    public void handle(User user, Update update, TelegramClient client) {
        Long chatId = user.getTelegramChatId();

        if (!update.hasCallbackQuery()) {
            return;
        }

        String callbackData = update.getCallbackQuery().getData();

        if ("ADD_PLANT_LOCATION_CREATE".equals(callbackData)) {
            userService.updateState(user, ConversationState.AWAITING_PLANT_LOCATION_NAME);

            SendMessage message = SendMessage.builder()
                    .chatId(chatId.toString())
                    .text("""
                            ➕ Создадим новую комнату для этого растения.

                            Как назовём комнату?

                            Например:
                            Кухня
                            Балкон
                            Спальня
                            Офис
                            """)
                    .build();

            try {
                client.execute(message);
            } catch (TelegramApiException e) {
                log.error("Failed to ask new plant location name", e);
            }

            answerCallback(update, client);
            return;
        }

        if (!callbackData.startsWith("ADD_PLANT_LOCATION:")) {
            return;
        }

        String locationChoice = callbackData.substring("ADD_PLANT_LOCATION:".length());

        try {
            Long locationId = null;

            if (!"SKIP".equals(locationChoice)) {
                locationId = Long.parseLong(locationChoice);
            }

            Plant savedPlant = createPlantFromStateData(user, locationId);

            userService.setStateData(user, "plant_id", String.valueOf(savedPlant.getId()));
            askAboutMisting(user, savedPlant.getName(), client);

            log.info(
                    "Plant created for user {} with location {}",
                    chatId,
                    locationId != null ? locationId : "default"
            );

        } catch (Exception e) {
            log.error("Failed to create plant for user {}", chatId, e);
            sendErrorAndReset(user, client);
        }

        answerCallback(update, client);
    }

    private Plant createPlantFromStateData(User user, Long locationId) {
        Map<String, Object> stateData = user.getStateData();

        String speciesIdStr = (String) stateData.get("species_id");
        String intervalDaysStr = (String) stateData.get("interval_days");
        String plantName = (String) stateData.get("plant_name");
        String nextDueAtStr = (String) stateData.get("next_due_at");

        Long speciesId = null;
        if (speciesIdStr != null && !speciesIdStr.isBlank() && !"null".equals(speciesIdStr)) {
            speciesId = Long.parseLong(speciesIdStr);
        }

        Integer intervalDays = Integer.parseInt(intervalDaysStr);
        LocalDateTime nextDueAt = LocalDateTime.parse(nextDueAtStr);

        return plantService.createPlantWithWateringSchedule(
                user,
                speciesId,
                plantName,
                intervalDays,
                nextDueAt,
                locationId
        );
    }

    private void askAboutMisting(User user, String plantName, TelegramClient client) {
        userService.updateState(user, ConversationState.AWAITING_PLANT_MISTING_SETUP);

        var keyboard = org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup.builder()
                .keyboardRow(new org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow(
                        org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton.builder()
                                .text("💨 Да, каждые 3 дня")
                                .callbackData("MISTING:DEFAULT")
                                .build()
                ))
                .keyboardRow(new org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow(
                        org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton.builder()
                                .text("✏️ Да, задать интервал")
                                .callbackData("MISTING:CUSTOM")
                                .build()
                ))
                .keyboardRow(new org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow(
                        org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton.builder()
                                .text("❌ Не нужно")
                                .callbackData("MISTING:SKIP")
                                .build()
                ))
                .build();

        SendMessage message = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text("💨 Нужно ли опрыскивать *" + plantName + "*?\n\n"
                        + "Опрыскивание повышает влажность воздуха — полезно для тропических растений.")
                .parseMode("Markdown")
                .replyMarkup(keyboard)
                .build();

        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send misting question", e);
        }
    }

    private void sendErrorAndReset(User user, TelegramClient client) {
        SendMessage errorMsg = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text("❌ Ошибка при сохранении растения. Попробуй ещё раз позже.")
                .build();

        try {
            client.execute(errorMsg);
        } catch (TelegramApiException ex) {
            log.error("Failed to send error message", ex);
        }

        userService.resetToIdle(user);
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