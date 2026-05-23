package com.plantcare.bot.state.impl;

import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.service.UserService;
import com.plantcare.bot.state.interfaces.StateHandler;
import com.plantcare.bot.util.TimezoneSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Обрабатывает ввод произвольного числа дней отпуска (issue #53).
 * Активируется после кнопки «Другое» в меню MENU:VACATION_OTHER.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AwaitingVacationDaysStateHandler implements StateHandler {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM");

    private final UserService userService;

    @Override
    public ConversationState getSupportedState() {
        return ConversationState.AWAITING_VACATION_DAYS;
    }

    @Override
    public void handle(User user, Update update, TelegramClient client) {
        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return;
        }

        String text = update.getMessage().getText().trim();

        if (isCancelKeyword(text)) {
            userService.resetToIdle(user);
            sendText(user, client, "Отменил. Можешь зайти в ⚙️ Настройки и попробовать снова.");
            return;
        }

        int days;
        try {
            days = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            sendText(user, client, "❌ Введи целое число от "
                    + UserService.MIN_VACATION_DAYS + " до " + UserService.MAX_VACATION_DAYS
                    + ", или напиши «отмена» чтобы выйти.");
            return;
        }

        try {
            User updated = userService.startVacation(user, days);
            userService.resetToIdle(updated);

            String date = updated.getPausedUntil() == null
                    ? "—"
                    : TimezoneSupport.dateInUserZone(updated.getPausedUntil(), updated).format(DATE_FMT);
            sendText(user, client,
                    "🏖 Отпуск до " + date + ". Бот будет молчать, удачной поездки!");
        } catch (IllegalArgumentException e) {
            sendText(user, client, "❌ " + e.getMessage());
        }
    }

    private static boolean isCancelKeyword(String text) {
        String lower = text.toLowerCase();
        return lower.equals("/cancel") || lower.equals("отмена") || lower.equals("/отмена")
                || lower.equals("назад") || lower.equals("cancel");
    }

    private void sendText(User user, TelegramClient client, String text) {
        SendMessage message = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text(text)
                .build();
        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send vacation days response to {}", user.getTelegramChatId(), e);
        }
    }
}
