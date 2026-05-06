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
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AwaitingLocationEmojiStateHandler implements StateHandler {

    private final UserService userService;
    private final LocationService locationService;
    private final LocationMenuService locationMenuService;

    @Override
    public ConversationState getSupportedState() {
        return ConversationState.AWAITING_LOCATION_EMOJI;
    }

    @Override
    public void handle(User user, Update update, TelegramClient client) {
        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return;
        }

        String emoji = update.getMessage().getText().trim();

        try {
            if (emoji.isEmpty() || emoji.length() > 16) {
                sendText(user, client, "Emoji должен быть одним символом. Попробуй ещё раз.");
                return;
            }

            Map<String, Object> stateData = user.getStateData();
            String name = (String) stateData.get("location_name");

            Location location = locationService.createLocation(user, name, emoji);

            sendText(user, client, "✅ Комната создана: " + location.getDisplayName());

            userService.resetToIdle(user);
            locationMenuService.sendLocationsMenu(user, client);

        } catch (Exception e) {
            log.error("Failed to create location for user {}", user.getTelegramChatId(), e);
            sendText(user, client, "❌ Не получилось создать комнату: " + e.getMessage());
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
}