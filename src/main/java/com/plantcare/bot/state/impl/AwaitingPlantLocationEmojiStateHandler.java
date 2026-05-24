package com.plantcare.bot.state.impl;

import com.plantcare.bot.domain.Location;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.service.LocationService;
import com.plantcare.bot.service.PlantService;
import com.plantcare.bot.service.UserService;
import com.plantcare.bot.state.interfaces.StateHandler;
import com.plantcare.bot.util.EmojiValidator;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AwaitingPlantLocationEmojiStateHandler implements StateHandler {

    private static final String CALLBACK_PREFIX = "PLANT_LOCATION_EMOJI:";

    private final UserService userService;
    private final LocationService locationService;
    private final PlantService plantService;

    @Override
    public ConversationState getSupportedState() {
        return ConversationState.AWAITING_PLANT_LOCATION_EMOJI;
    }

    @Override
    public void handle(User user, Update update, TelegramClient client) {
        String emoji;

        if (update.hasCallbackQuery()) {
            String data = update.getCallbackQuery().getData();

            if (data == null || !data.startsWith(CALLBACK_PREFIX)) {
                return;
            }

            emoji = data.substring(CALLBACK_PREFIX.length());
            answerCallback(update, client);

        } else if (update.hasMessage() && update.getMessage().hasText()) {
            emoji = update.getMessage().getText().trim();
        } else {
            return;
        }

        if (EmojiValidator.isValidEmoji(emoji)) {
            sendText(
                    client,
                    user.getTelegramChatId(),
                    "❌ Emoji должен быть одним emoji-символом. Например: 🌿"
            );
            return;
        }

        try {
            Map<String, Object> stateData = user.getStateData();

            String locationName = (String) stateData.get("new_plant_location_name");

            Location location = locationService.createLocation(
                    user,
                    locationName,
                    emoji
            );

            Plant savedPlant = createPlantFromStateData(user, location.getId());

            userService.setStateData(user, "plant_id", String.valueOf(savedPlant.getId()));

            sendText(
                    client,
                    user.getTelegramChatId(),
                    "✅ Комната создана: " + location.getDisplayName() + "\n"
                            + "🌿 Растение добавлено в эту комнату."
            );

            askAboutMisting(user, savedPlant.getName(), client);

            log.info(
                    "Created location {} and plant {} for user {}",
                    location.getId(),
                    savedPlant.getId(),
                    user.getTelegramChatId()
            );

        } catch (IllegalArgumentException e) {
            log.warn(
                    "Failed to create location inside plant flow for user {}: {}",
                    user.getTelegramChatId(),
                    e.getMessage()
            );

            sendText(client, user.getTelegramChatId(), "❌ " + e.getMessage());

        } catch (Exception e) {
            log.error("Failed to create location inside plant flow for user {}", user.getTelegramChatId(), e);
            sendText(client, user.getTelegramChatId(), "❌ Не получилось создать комнату. Попробуй позже.");
            userService.resetToIdle(user);
        }
    }

    private Plant createPlantFromStateData(User user, Long locationId) {
        Map<String, Object> stateData = user.getStateData();

        String speciesIdStr = (String) stateData.get("species_id");
        String intervalDaysStr = (String) stateData.get("interval_days");
        String plantName = (String) stateData.get("plant_name");
        String nextDueAtStr = (String) stateData.get("next_due_at");
        String acquiredAtStr = (String) stateData.get("acquired_at");

        Long speciesId = null;
        if (speciesIdStr != null && !speciesIdStr.isBlank() && !"null".equals(speciesIdStr)) {
            speciesId = Long.parseLong(speciesIdStr);
        }

        Integer intervalDays = Integer.parseInt(intervalDaysStr);
        LocalDateTime nextDueAt = LocalDateTime.parse(nextDueAtStr);
        LocalDate acquiredAt = (acquiredAtStr == null || acquiredAtStr.isBlank())
                ? null
                : LocalDate.parse(acquiredAtStr);

        // Если юзер не ввёл/пропустил acquired_at — используем старый
        // 6-аргументный overload, чтобы не плодить лишний параметр в самом
        // частом сценарии.
        if (acquiredAt == null) {
            return plantService.createPlantWithWateringSchedule(
                    user,
                    speciesId,
                    plantName,
                    intervalDays,
                    nextDueAt,
                    locationId
            );
        }

        return plantService.createPlantWithWateringSchedule(
                user,
                speciesId,
                plantName,
                intervalDays,
                nextDueAt,
                locationId,
                acquiredAt
        );
    }

    private void askAboutMisting(User user, String plantName, TelegramClient client) {
        userService.updateState(user, ConversationState.AWAITING_PLANT_MISTING_SETUP);

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(
                        InlineKeyboardButton.builder()
                                .text("💨 Да, каждые 3 дня")
                                .callbackData("MISTING:DEFAULT")
                                .build()
                ))
                .keyboardRow(new InlineKeyboardRow(
                        InlineKeyboardButton.builder()
                                .text("✏️ Да, задать интервал")
                                .callbackData("MISTING:CUSTOM")
                                .build()
                ))
                .keyboardRow(new InlineKeyboardRow(
                        InlineKeyboardButton.builder()
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

    private void sendText(TelegramClient client, Long chatId, String text) {
        try {
            client.execute(SendMessage.builder()
                    .chatId(chatId.toString())
                    .text(text)
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to send message", e);
        }
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