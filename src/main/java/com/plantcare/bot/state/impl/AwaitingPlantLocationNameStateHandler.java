package com.plantcare.bot.state.impl;

import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.ConversationState;
import com.plantcare.core.service.LocationService;
import com.plantcare.core.service.UserService;
import com.plantcare.bot.state.interfaces.StateHandler;
import com.plantcare.core.util.LocationEmojiKeyboard;
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

    private static final String PLANT_LOCATION_EMOJI_CALLBACK_PREFIX = "PLANT_LOCATION_EMOJI:";

    private final UserService userService;
    private final LocationService locationService;

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

        if (name.isBlank() || name.length() > 30) {
            sendText(
                    client,
                    chatId,
                    "❌ Название комнаты должно быть от 1 до 30 символов. Попробуй ещё раз:"
            );
            return;
        }

        if (locationService.hasReachedLocationsLimit(user.getId())) {
            sendText(
                    client,
                    chatId,
                    "❌ Можно создать максимум " + locationService.getMaxLocationsPerUser() + " комнат.\n\n" +
                            "Выбери существующую комнату или нажми «Пропустить»."
            );

            userService.updateState(user, ConversationState.AWAITING_PLANT_ROOM);
            return;
        }

        boolean duplicateExists = locationService.getUserLocations(user.getId()).stream()
                .anyMatch(location -> location.getName().equalsIgnoreCase(name));

        if (duplicateExists) {
            sendText(
                    client,
                    chatId,
                    "❌ Такая комната уже есть. Введи другое название:"
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
                .replyMarkup(LocationEmojiKeyboard.build(PLANT_LOCATION_EMOJI_CALLBACK_PREFIX))
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