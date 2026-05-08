package com.plantcare.bot.state.impl;

import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.service.UserService;
import com.plantcare.bot.state.interfaces.StateHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class AwaitingTimezoneManualStateHandler implements StateHandler {

    private static final String SET_TZ_PREFIX = "SET_TZ:";

    private final UserService userService;

    @Override
    public ConversationState getSupportedState() {
        return ConversationState.AWAITING_TIMEZONE_MANUAL;
    }

    @Override
    public void handle(User user, Update update, TelegramClient client) {
        if (update.hasCallbackQuery()) {
            handleCallback(user, update, client);
            return;
        }

        if (update.hasMessage() && update.getMessage().hasText()) {
            handleTextInsteadOfCallback(user, update, client);
        }
    }

    private void handleCallback(User user, Update update, TelegramClient client) {
        String callbackData = update.getCallbackQuery().getData();

        if (callbackData == null || !callbackData.startsWith(SET_TZ_PREFIX)) {
            answerCallback(update.getCallbackQuery().getId(), client);
            return;
        }

        String timezoneId = callbackData.substring(SET_TZ_PREFIX.length());

        saveAndFinish(user, timezoneId, client);
        answerCallback(update.getCallbackQuery().getId(), client);
    }

    private void handleTextInsteadOfCallback(User user, Update update, TelegramClient client) {
        Long chatId = user.getTelegramChatId();
        String userText = update.getMessage().getText();

        log.info("User {} sent text instead of selecting timezone: {}", chatId, userText);

        SendMessage reminder = SendMessage.builder()
                .chatId(chatId.toString())
                .text(
                        "Пожалуйста, выбери город из списка выше, нажав на кнопку.\n\n" +
                                "Если твоего города нет в списке, выбери наиболее подходящий по часовому поясу.\n\n" +
                                "Для отмены нажми /cancel."
                )
                .build();

        try {
            client.execute(reminder);
        } catch (TelegramApiException e) {
            log.error("Failed to send manual timezone reminder to chatId: {}", chatId, e);
        }
    }

    private void saveAndFinish(User user, String timezoneId, TelegramClient client) {
        user.setTimezone(timezoneId);

        log.info("Timezone set to {} for user {}", timezoneId, user.getTelegramChatId());

        userService.updateState(user, ConversationState.IDLE);

        SendMessage message = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text(
                        "✅ Часовой пояс установлен: " + timezoneId + "\n\n" +
                                "Теперь я буду напоминать тебе о растениях вовремя 🌱\n\n" +
                                "Напиши /menu, чтобы открыть главное меню."
                )
                .replyMarkup(new ReplyKeyboardRemove(true))
                .build();

        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send success message in onboarding", e);
        }
    }

    private void answerCallback(String callbackQueryId, TelegramClient client) {
        try {
            client.execute(AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackQueryId)
                    .build());
        } catch (Exception e) {
            log.error("Failed to answer callback", e);
        }
    }
}