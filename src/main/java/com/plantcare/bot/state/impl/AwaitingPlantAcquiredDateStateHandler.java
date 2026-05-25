package com.plantcare.bot.state.impl;

import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.ConversationState;
import com.plantcare.core.service.UserService;
import com.plantcare.bot.state.interfaces.StateHandler;
import com.plantcare.core.util.TimezoneSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Шаг визарда «введи дату завода растения вручную» (issue #117).
 *
 * <p>Принимает только текст, формат — ДД.ММ.ГГГГ. Будущие даты отклоняются
 * (с проверкой по «сегодня» в TZ юзера). После успешного ввода — переход
 * к шагу последнего полива.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AwaitingPlantAcquiredDateStateHandler implements StateHandler {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final UserService userService;

    @Override
    public ConversationState getSupportedState() {
        return ConversationState.AWAITING_PLANT_ACQUIRED_DATE;
    }

    @Override
    public void handle(User user, Update update, TelegramClient client) {
        Long chatId = user.getTelegramChatId();

        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return;
        }

        String input = update.getMessage().getText().trim();

        LocalDate parsed;
        try {
            parsed = LocalDate.parse(input, DATE_FMT);
        } catch (DateTimeParseException e) {
            log.debug("Acquired-date parse error for user {}: '{}'", chatId, input);
            sendError(chatId, "Не понял формат, пример: 15.03.2024", client);
            return;
        }

        LocalDate today = TimezoneSupport.todayInUserZone(user);
        if (parsed.isAfter(today)) {
            sendError(chatId, "Дата не может быть в будущем", client);
            return;
        }

        userService.setStateData(user,
                AwaitingPlantAcquiredChoiceStateHandler.STATE_KEY_ACQUIRED_AT,
                parsed.toString());
        userService.updateState(user, ConversationState.AWAITING_PLANT_LAST_WATERED);

        String plantName = (String) user.getStateData().get("plant_name");
        String greeting = plantName != null ? plantName : "растение";

        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text("💧 Когда ты в последний раз поливал(а) " + greeting + "?")
                .replyMarkup(buildLastWateredKeyboard())
                .build();

        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send last watered question", e);
        }

        log.info("Acquired-at set manually for user {}: {}", chatId, parsed);
    }

    private void sendError(Long chatId, String text, TelegramClient client) {
        SendMessage error = SendMessage.builder()
                .chatId(chatId.toString())
                .text("❌ " + text)
                .build();
        try {
            client.execute(error);
        } catch (TelegramApiException e) {
            log.error("Failed to send acquired-date error", e);
        }
    }

    private InlineKeyboardMarkup buildLastWateredKeyboard() {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("📅 Сегодня")
                                .callbackData("LAST_WATERED:TODAY")
                                .build()
                )))
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("📅 Вчера")
                                .callbackData("LAST_WATERED:YESTERDAY")
                                .build()
                )))
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("❓ Не помню")
                                .callbackData("LAST_WATERED:FORGOTTEN")
                                .build()
                )))
                .build();
    }
}
