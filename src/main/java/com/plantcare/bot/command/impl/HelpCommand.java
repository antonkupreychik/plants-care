package com.plantcare.bot.command.impl;

import com.plantcare.bot.command.interfaces.BotCommand;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Slf4j
@Component
public class HelpCommand implements BotCommand {

    @Override
    public String getCommandName() {
        return "/help";
    }

    @Override
    public void execute(Update update, TelegramClient client) {
        Long chatId = update.getMessage().getChatId();

        String text = """
                🌿 *Plants Care Bot — Помощь*
                
                Доступные команды:
                /start — Начать или вернуться в главное меню
                /menu — Главное меню (растения + задачи на сегодня)
                /add — Добавить новое растение
                /calendar — Календарь ухода на неделю вперёд
                /cancel — Отменить текущее действие
                /help — Эта справка
                
                Бот присылает напоминания о поливе, опрыскивании и удобрении. \
                Вы можете настроить тихие часы, чтобы уведомления не приходили ночью.
                """;

        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .parseMode("Markdown")
                .build();

        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send help message: {}", e.getMessage(), e);
        }
    }
}