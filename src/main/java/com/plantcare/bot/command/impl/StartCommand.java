package com.plantcare.bot.command.impl;

import com.plantcare.bot.command.interfaces.BotCommand;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class StartCommand implements BotCommand {

    private final UserService userService;

    @Override
    public String getCommandName() {
        return "/start";
    }

    @Override
    public void execute(Update update, TelegramClient client) {
        Long chatId = update.getMessage().getChatId();
        User user = userService.findByChatId(chatId)
                .orElseThrow(() -> new IllegalStateException("User not found"));
        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text("Привет! Я бот проекта Plants-care. Я помогу тебе ухаживать за твоими растениями.")
                .build();
        if ("UTC".equals(user.getTimezone())) {
            startOnboarding(chatId, user, client);
        } else {
            showMainMenu(chatId, client);
        }
        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Ошибка при отправке сообщения в /start: ", e);
        }
    }

    private void showMainMenu(Long chatId, TelegramClient client) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text("С возвращением! Главное меню скоро будет доступно здесь (задача #12). " +
                        "Пока что я готовлю для тебя лучшие инструменты по уходу за растениями!")
                .build();

        try {
            client.execute(message);
            log.info("Shown main menu stub to existing user: {}", chatId);
        } catch (TelegramApiException e) {
            log.error("Failed to show main menu stub", e);
        }
    }

    private void startOnboarding(Long chatId, User user, TelegramClient client) {
        userService.updateState(user, ConversationState.AWAITING_TIMEZONE);

        ReplyKeyboardMarkup keyboard = ReplyKeyboardMarkup.builder()
                .keyboardRow(new KeyboardRow(List.of(
                        KeyboardButton.builder()
                                .text("📍 Отправить локацию")
                                .requestLocation(true) // Критерий 2
                                .build()
                )))
                .keyboardRow(new KeyboardRow(List.of(
                        new KeyboardButton("⌨️ Выбрать вручную")
                )))
                .resizeKeyboard(true)
                .oneTimeKeyboard(true)
                .build();

        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text("Привет! Давай настроим время, чтобы напоминания приходили вовремя. Как тебе удобнее установить часовой пояс?")
                .replyMarkup(keyboard)
                .build();

        try {
            client.execute(message);
            log.info("Shown location button stub to existing user: {}", chatId);
        } catch (TelegramApiException e) {
            log.error("Failed to show main menu stub", e);
        }
    }
}