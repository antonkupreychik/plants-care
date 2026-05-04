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
public class StartCommand implements BotCommand {

    @Override
    public String getCommandName() {
        return "/start";
    }

    @Override
    public void execute(Update update, TelegramClient client) {
        Long chatId = update.getMessage().getChatId();

        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text("Привет! Я бот проекта Plants-care. Я помогу тебе ухаживать за твоими растениями.")
                .build();

        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Ошибка при отправке сообщения в /start: ", e);
        }
    }
}