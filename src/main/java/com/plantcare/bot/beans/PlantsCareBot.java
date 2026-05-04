package com.plantcare.bot.beans;

import com.plantcare.bot.command.container.CommandContainer;
import com.plantcare.bot.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Slf4j
@Component
public class PlantsCareBot implements LongPollingSingleThreadUpdateConsumer {

    private final TelegramClient telegramClient;
    private final CommandContainer commandContainer;
    private final UserService userService;

    public PlantsCareBot(@Value("${telegram.bot.token}") String botToken,
                         CommandContainer commandContainer, UserService userService) {
        this.telegramClient = new OkHttpTelegramClient(botToken);
        this.commandContainer = commandContainer;
        this.userService = userService;
    }

    @Override
    public void consume(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            String messageText = update.getMessage().getText();
            long chatId = update.getMessage().getChatId();
            String username = update.getMessage().getFrom().getUserName();

            log.info("Received message from {}: {}", chatId, messageText);

            userService.findOrCreate(chatId, username);

            String commandIdentifier = messageText.split(" ")[0].toLowerCase();

            commandContainer.retrieveCommand(commandIdentifier)
                    .execute(update, telegramClient);
        }
    }
}