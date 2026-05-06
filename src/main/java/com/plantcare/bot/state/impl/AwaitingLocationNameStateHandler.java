
package com.plantcare.bot.state.impl;

import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class AwaitingLocationNameStateHandler implements StateHandler {

    private final UserService userService;
    private final LocationService locationService;

    @Override
    public ConversationState getSupportedState() {
        return ConversationState.AWAITING_LOCATION_NAME;
    }

    @Override
    public void handle(User user, Update update, TelegramClient client) {
        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return;
        }

        String name = update.getMessage().getText().trim();

        try {
            // Проверяем название через create пока нельзя, потому что emoji ещё не выбран.
            // Поэтому делаем лёгкую валидацию тут.
            if (name.isEmpty() || name.length() > 30) {
                sendText(user, client, "Название должно быть от 1 до 30 символов. Попробуй ещё раз.");
                return;
            }

            if (locationService.getUserLocations(user.getId()).stream()
                    .anyMatch(location -> location.getName().equalsIgnoreCase(name))) {
                sendText(user, client, "Такая комната уже есть. Введи другое название.");
                return;
            }

            userService.setStateData(user, "location_name", name);
            userService.updateState(user, ConversationState.AWAITING_LOCATION_EMOJI);

            sendText(user, client,
                    "Выбери emoji для комнаты или отправь свой одним символом.\n\n" +
                    "Например: 🛋 🛏 🍳 🌿 💼 🚿 🪴");

        } catch (Exception e) {
            log.error("Failed to process location name for user {}", user.getTelegramChatId(), e);
            sendText(user, client, "❌ Не получилось сохранить название. Попробуй ещё раз.");
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