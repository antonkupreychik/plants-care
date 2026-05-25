package com.plantcare.bot.state.impl;

import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.service.UserService;
import com.plantcare.bot.service.UserSettingsService;
import com.plantcare.bot.state.interfaces.StateHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Ввод времени начала тихих часов (issue #116). Формат {@code HH:mm}; парсер сам
 * валидирует диапазон 0–23 ч / 0–59 мин. На невалидном вводе остаёмся в состоянии.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AwaitingQuietStartStateHandler implements StateHandler {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final UserService userService;
    private final UserSettingsService userSettingsService;

    @Override
    public ConversationState getSupportedState() {
        return ConversationState.AWAITING_QUIET_START;
    }

    @Override
    public void handle(User user, Update update, TelegramClient client) {
        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return;
        }

        LocalTime time;
        try {
            time = LocalTime.parse(update.getMessage().getText().trim(), TIME_FMT);
        } catch (DateTimeParseException e) {
            sendText(user, client, "❌ Не понял время. Введи в формате ЧЧ:ММ, например 22:00.");
            return;
        }

        userSettingsService.setQuietHoursStart(user, time);
        userService.updateState(user, ConversationState.IDLE);

        sendText(user, client, "🌙 Начало тихих часов: " + time.format(TIME_FMT) + ".");
    }

    private void sendText(User user, TelegramClient client, String text) {
        SendMessage message = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text(text)
                .build();

        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send quiet start message", e);
        }
    }
}
