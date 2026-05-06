package com.plantcare.bot.state.impl;

import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.service.UserService;
import com.plantcare.bot.state.interfaces.StateHandler;
import com.plantcare.bot.util.LocationEmojiKeyboard;
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
public class AwaitingPlantLocationNameStateHandler implements StateHandler {

    private final UserService userService;

    @Override
    public ConversationState getSupportedState() {
        return ConversationState.AWAITING_PLANT_LOCATION_NAME;
    }

    @Override
    public void handle(User user, Update update, TelegramClient client) {
        Long chatId = user.getTelegramChatId();

        if (!update.hasMessage() || !update.getMessage().hasText()) {
            sendText(
                    client,
                    chatId,
                    "Введи название комнаты текстом.\n\nНапример: Кухня, Балкон, Спальня"
            );
            return;
        }

        String name = update.getMessage().getText().trim();

        if (name.isBlank()) {
            sendText(
                    client,
                    chatId,
                    "❌ Название комнаты не может быть пустым.\n\nВведи название от 1 до 30 символов:"
            );
            return;
        }

        if (name.length() > 30) {
            sendText(
                    client,
                    chatId,
                    "❌ Название комнаты должно быть не длиннее 30 символов.\n\nПопробуй ещё раз:"
            );
            return;
        }

        userService.setStateData(user, "new_plant_location_name", name);
        userService.updateState(user, ConversationState.AWAITING_PLANT_LOCATION_EMOJI);

        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text("""
                        Выбери emoji для комнаты или отправь свой одним сообщением.

                        Например:
                        🛋 🛏 🍳 🌿 💼 🚿 🪴 ❤️
                        """)
                .replyMarkup(LocationEmojiKeyboard.build("PLANT_LOCATION_EMOJI:"))
                .build();

        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to ask plant location emoji", e);
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
}