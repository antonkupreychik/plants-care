package com.plantcare.bot.state.impl;

import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.service.UserService;
import com.plantcare.bot.service.UserSettingsService;
import com.plantcare.bot.service.UserSettingsService.TimezoneChangeResult;
import com.plantcare.bot.state.interfaces.StateHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.DateTimeException;
import java.time.ZoneId;

/**
 * Ручной ввод таймзоны текстом (issue #116). Юзер пишет {@code Region/City},
 * валидируем через {@link ZoneId#of(String)}. На невалидном вводе остаёмся
 * в состоянии и переспрашиваем; на валидном — меняем таймзону и пересчитываем
 * расписания через {@link UserSettingsService#changeTimezone}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AwaitingTimezoneCustomStateHandler implements StateHandler {

    private final UserService userService;
    private final UserSettingsService userSettingsService;

    @Override
    public ConversationState getSupportedState() {
        return ConversationState.AWAITING_TIMEZONE_CUSTOM;
    }

    @Override
    public void handle(User user, Update update, TelegramClient client) {
        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return;
        }

        String text = update.getMessage().getText().trim();

        ZoneId zone;
        try {
            zone = ZoneId.of(text);
        } catch (DateTimeException e) {
            sendText(user, client,
                    "❌ Не знаю такую таймзону. Попробуй ещё раз, например `Europe/Warsaw`.");
            return;
        }

        TimezoneChangeResult result = userSettingsService.changeTimezone(user, zone);
        userService.updateState(user, ConversationState.IDLE);

        String confirmation = UserSettingsService.buildTimezoneConfirmation(result, user.getQuietHoursEnd())
                + "\n\nНапиши /menu, чтобы открыть главное меню.";

        SendMessage message = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text(confirmation)
                .replyMarkup(new ReplyKeyboardRemove(true))
                .build();

        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send custom timezone success message", e);
        }
    }

    private void sendText(User user, TelegramClient client, String text) {
        SendMessage message = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text(text)
                .parseMode("Markdown")
                .build();

        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send custom timezone error message", e);
        }
    }
}
