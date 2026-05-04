package com.plantcare.bot.state.impl;

import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.service.UserService;
import com.plantcare.bot.state.interfaces.StateHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class AwaitingTimezoneManualStateHandler implements StateHandler {
    private final UserService userService;

    @Override
    public ConversationState getSupportedState() {
        return ConversationState.AWAITING_TIMEZONE_MANUAL;
    }

    @Override
    public void handle(User user, Update update, TelegramClient client) {
        if (update.hasCallbackQuery()) {
            String callbackData = update.getCallbackQuery().getData();

            if (callbackData.startsWith("SET_TZ:")) {
                String selectedZone = callbackData.split(":")[1];
                saveAndFinish(user, selectedZone, client);
                answerCallback(update.getCallbackQuery().getId(), client);
            }
        } else {
            Long chatId = user.getTelegramChatId();
            String userText = update.getMessage().getText();

            log.info("User {} sent text instead of selecting timezone: {}", chatId, userText);

            SendMessage reminder = SendMessage.builder()
                    .chatId(chatId.toString())
                    .text("Пожалуйста, выбери город из списка выше, нажав на кнопку. \n\n" +
                            "Если твоего города нет в списке, выбери наиболее подходящий по часовому поясу. " +
                            "Для отмены нажми /cancel.")
                    .build();

            try {
                client.execute(reminder);
            } catch (TelegramApiException e) {
                log.error("Failed to send manual timezone reminder to chatId: {}", chatId, e);
            }
        }
    }

    private void saveAndFinish(User user, String timezoneId, TelegramClient client) {
        user.setTimezone(timezoneId);

        log.info("Timezone set to {} for user {}", timezoneId, user.getTelegramChatId());

        userService.updateState(user, ConversationState.AWAITING_PLANT_NAME);

        SendMessage message = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text("✅ Часовой пояс установлен: *" + timezoneId + "*.\n\n" +
                        "Теперь давай добавим твое первое растение. Как оно называется?")
                .parseMode("Markdown")
                .replyMarkup(new ReplyKeyboardRemove(true)) // Убираем кнопку локации
                .build();

        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send success message in onboarding", e);
        }
    }

    private void answerCallback(String callbackQueryId, TelegramClient client) {
        try {
            client.execute(org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackQueryId)
                    .build());
        } catch (Exception e) {
            log.error("Failed to answer callback", e);
        }
    }
}