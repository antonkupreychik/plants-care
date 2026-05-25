package com.plantcare.bot.state.impl;

import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.ConversationState;
import com.plantcare.core.service.UserService;
import com.plantcare.bot.state.interfaces.StateHandler;
import com.plantcare.core.util.TimezoneSupport;
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
import java.util.List;

/**
 * Шаг визарда создания растения «когда завёл?» (issue #117).
 *
 * <p>Принимает inline-callback с одним из ACQUIRED:* пресетов. Пресеты
 * (TODAY/WEEK/MONTH/SKIP) сразу заполняют {@code acquired_at} в state-data
 * и переводят к следующему шагу (последний полив). Вариант MANUAL переводит
 * в {@link ConversationState#AWAITING_PLANT_ACQUIRED_DATE} — ждём ручной ввод.
 *
 * <p>Даты пресетов считаются в TZ юзера: «сегодня», «−3 дня», «−30 дней»
 * по календарю в зоне юзера, чтобы по-полночному UTC не съезжать на сутки.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AwaitingPlantAcquiredChoiceStateHandler implements StateHandler {

    /** Ключ в state-data, где лежит acquired_at в формате ISO (yyyy-MM-dd). */
    public static final String STATE_KEY_ACQUIRED_AT = "acquired_at";

    private static final String CALLBACK_PREFIX = "ACQUIRED:";

    private final UserService userService;

    @Override
    public ConversationState getSupportedState() {
        return ConversationState.AWAITING_PLANT_ACQUIRED_CHOICE;
    }

    @Override
    public void handle(User user, Update update, TelegramClient client) {
        if (!update.hasCallbackQuery()) {
            return;
        }

        String callbackData = update.getCallbackQuery().getData();
        if (callbackData == null || !callbackData.startsWith(CALLBACK_PREFIX)) {
            return;
        }

        String choice = callbackData.substring(CALLBACK_PREFIX.length());
        LocalDate today = TimezoneSupport.todayInUserZone(user);

        switch (choice) {
            case "TODAY" -> saveAndAdvance(user, today, client);
            case "WEEK" -> saveAndAdvance(user, today.minusDays(3), client);
            case "MONTH" -> saveAndAdvance(user, today.minusDays(30), client);
            case "SKIP" -> saveAndAdvance(user, null, client);
            case "MANUAL" -> promptForManualDate(user, client);
            default -> {
                log.warn("Unknown ACQUIRED choice '{}' from user {}",
                        choice, user.getTelegramChatId());
                answerCallback(update, client);
                return;
            }
        }

        answerCallback(update, client);
    }

    private void saveAndAdvance(User user, LocalDate acquiredAt, TelegramClient client) {
        if (acquiredAt != null) {
            userService.setStateData(user, STATE_KEY_ACQUIRED_AT, acquiredAt.toString());
        } else {
            userService.removeStateData(user, STATE_KEY_ACQUIRED_AT);
        }

        userService.updateState(user, ConversationState.AWAITING_PLANT_LAST_WATERED);

        String plantName = (String) user.getStateData().get("plant_name");
        String greeting = plantName != null ? plantName : "растение";

        SendMessage message = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text("💧 Когда ты в последний раз поливал(а) " + greeting + "?")
                .replyMarkup(buildLastWateredKeyboard())
                .build();

        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send last watered question", e);
        }

        log.info("Acquired-at set for user {}: {}",
                user.getTelegramChatId(),
                acquiredAt != null ? acquiredAt : "null (skipped)");
    }

    private void promptForManualDate(User user, TelegramClient client) {
        userService.updateState(user, ConversationState.AWAITING_PLANT_ACQUIRED_DATE);

        SendMessage message = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text("📅 Введи дату в формате ДД.ММ.ГГГГ. Например: 15.03.2024")
                .build();

        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send manual date prompt", e);
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
