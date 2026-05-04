package com.plantcare.bot.command.interfaces;

import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.generics.TelegramClient;

public interface BotCommand {
    String getCommandName();

    void execute(Update update, TelegramClient client);
}