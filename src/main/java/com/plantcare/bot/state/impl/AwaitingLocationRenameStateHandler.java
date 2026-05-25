package com.plantcare.bot.state.impl;

import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.ConversationState;
import com.plantcare.bot.service.LocationMenuService;
import com.plantcare.core.service.LocationService;
import com.plantcare.core.service.UserService;
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
public class AwaitingLocationRenameStateHandler implements StateHandler {

    private final UserService userService;
    private final LocationService locationService;
    private final LocationMenuService locationMenuService;

    @Override
    public ConversationState getSupportedState() {
        return ConversationState.AWAITING_LOCATION_RENAME;
    }

    @Override
    public void handle(User user, Update update, TelegramClient client) {
        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return;
        }

        String newName = update.getMessage().getText().trim();

        try {
            Map<String, Object> stateData = user.getStateData();
            String locationIdStr = (String) stateData.get("editing_location_id");

            if (locationIdStr == null || locationIdStr.isBlank()) {
                throw new IllegalStateException("editing_location_id is missing");
            }

            Long locationId = Long.parseLong(locationIdStr);

            Location location = locationService.renameLocation(
                    user.getId(),
                    locationId,
                    newName
            );

            sendText(user, client, "✅ Комната переименована: " + location.getDisplayName());

            userService.resetToIdle(user);
            locationMenuService.sendLocationScreen(user, location.getId(), client);

        } catch (Exception e) {
            log.error("Failed to rename location for user {}", user.getTelegramChatId(), e);
            sendText(user, client, "❌ Не получилось переименовать комнату: " + e.getMessage());
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