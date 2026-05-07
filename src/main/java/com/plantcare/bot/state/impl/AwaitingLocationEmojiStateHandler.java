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
public class AwaitingLocationEmojiStateHandler implements StateHandler {

    private static final String LOCATION_EMOJI_CALLBACK_PREFIX = "LOCATION_EMOJI:";

    private final UserService userService;
    private final LocationService locationService;
    private final LocationMenuService locationMenuService;

    @Override
    public ConversationState getSupportedState() {
        return ConversationState.AWAITING_LOCATION_EMOJI;
    }

    @Override
    public void handle(User user, Update update, TelegramClient client) {
        if (update.hasCallbackQuery()) {
            String callbackData = update.getCallbackQuery().getData();

            if (callbackData != null && callbackData.startsWith(LOCATION_EMOJI_CALLBACK_PREFIX)) {
                String emoji = callbackData.substring(LOCATION_EMOJI_CALLBACK_PREFIX.length());
                createLocation(user, emoji, client);
                answerCallback(update.getCallbackQuery().getId(), client);
            }

            return;
        }

        if (update.hasMessage() && update.getMessage().hasText()) {
            String emoji = update.getMessage().getText().trim();
            createLocation(user, emoji, client);
        }
    }

    private void createLocation(User user, String emoji, TelegramClient client) {
        try {
            if (emoji == null || emoji.isBlank() || emoji.length() > 16) {
                sendText(user, client, "Emoji должен быть одним символом. Попробуй ещё раз.");
                return;
            }

            Map<String, Object> stateData = user.getStateData();
            String name = stateData != null ? (String) stateData.get("location_name") : null;

            if (name == null || name.isBlank()) {
                sendText(user, client, "❌ Не нашёл название комнаты. Попробуй создать комнату заново.");
                userService.resetToIdle(user);
                locationMenuService.sendLocationsMenu(user, client);
                return;
            }

            Location location = locationService.createLocation(user, name, emoji);

            sendText(user, client, "✅ Комната создана: " + location.getDisplayName());

            userService.resetToIdle(user);
            locationMenuService.sendLocationsMenu(user, client);

        } catch (IllegalArgumentException e) {
            sendText(user, client, "❌ " + e.getMessage());
        } catch (Exception e) {
            log.error("Failed to create location for user {}", user.getTelegramChatId(), e);
            sendText(user, client, "❌ Не получилось создать комнату. Попробуй ещё раз.");
            userService.resetToIdle(user);
        }
    }

    private void sendText(User user, TelegramClient client, String text) {
        SendMessage message = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text(text)
                .build();

        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send message", e);
        }
    }

    private void answerCallback(String callbackQueryId, TelegramClient client) {
        try {
            client.execute(AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackQueryId)
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to answer callback", e);
        }
    }
}