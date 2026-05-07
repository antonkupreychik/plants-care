package com.plantcare.bot.state.impl;

import com.plantcare.bot.domain.Location;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.service.LocationMenuService;
import com.plantcare.bot.service.LocationService;
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

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AwaitingLocationChangeEmojiStateHandler implements StateHandler {

    private static final String CALLBACK_PREFIX = "LOCATION_CHANGE_EMOJI:";

    private final UserService userService;
    private final LocationService locationService;
    private final LocationMenuService locationMenuService;

    @Override
    public ConversationState getSupportedState() {
        return ConversationState.AWAITING_LOCATION_CHANGE_EMOJI;
    }

    @Override
    public void handle(User user, Update update, TelegramClient client) {
        Long chatId = user.getTelegramChatId();

        String emoji = extractEmoji(update, client);
        if (emoji == null) {
            return;
        }

        try {
            Long locationId = getEditingLocationId(user);

            Location location = locationService.changeEmoji(
                    user.getId(),
                    locationId,
                    emoji
            );

            sendText(client, chatId, "✅ Emoji изменён: " + location.getDisplayName());

            userService.resetToIdle(user);
            locationMenuService.sendLocationScreen(user, locationId, client);

            log.info(
                    "Changed emoji for location {} of user {}",
                    locationId,
                    user.getTelegramChatId()
            );

        } catch (IllegalArgumentException e) {
            sendText(client, chatId, "❌ " + e.getMessage() + "\n\nПопробуй отправить другой emoji.");
        } catch (Exception e) {
            log.error("Failed to change location emoji for user {}", chatId, e);
            sendText(client, chatId, "❌ Не получилось сменить emoji. Попробуй позже.");
            userService.resetToIdle(user);
        }
    }

    private String extractEmoji(Update update, TelegramClient client) {
        if (update.hasCallbackQuery()) {
            String data = update.getCallbackQuery().getData();

            if (data == null || !data.startsWith(CALLBACK_PREFIX)) {
                return null;
            }

            answerCallback(update, client);

            String emoji = data.substring(CALLBACK_PREFIX.length()).trim();
            return emoji.isBlank() ? null : emoji;
        }

        if (update.hasMessage() && update.getMessage().hasText()) {
            String emoji = update.getMessage().getText().trim();
            return emoji.isBlank() ? null : emoji;
        }

        return null;
    }

    private Long getEditingLocationId(User user) {
        Map<String, Object> stateData = user.getStateData();

        if (stateData == null || stateData.get("editing_location_id") == null) {
            throw new IllegalArgumentException("Комната для редактирования не найдена");
        }

        return Long.parseLong(String.valueOf(stateData.get("editing_location_id")));
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