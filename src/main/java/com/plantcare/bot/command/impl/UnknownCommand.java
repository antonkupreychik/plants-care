package com.plantcare.bot.command.impl;

import com.plantcare.bot.command.interfaces.BotCommand;
import com.plantcare.bot.service.MessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class UnknownCommand implements BotCommand {

    private final MessageService messageService;

    @Override
    public String getCommandName() {
        return "UNKNOWN";
    }

    @Override
    public void execute(Update update, TelegramClient client) {
        Long chatId = update.getMessage().getChatId();

        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(messageService.get(chatId, "command.unknown.text"))
                .build();

        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Ошибка при отправке сообщения в UnknownCommand: ", e);
        }
    }
}
