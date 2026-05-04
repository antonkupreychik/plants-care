package com.plantcare.bot.beans;

import com.plantcare.bot.command.CommandContainer;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.service.UserService;
import com.plantcare.bot.state.StateResolver;
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

    private static final String UNKNOWN = "UNKNOWN";

    private final TelegramClient telegramClient;
    private final CommandContainer commandContainer;
    private final UserService userService;
    private final StateResolver stateResolver;

    public PlantsCareBot(@Value("${telegram.bot.token}") String botToken,
                         CommandContainer commandContainer, UserService userService, StateResolver stateResolver) {
        this.telegramClient = new OkHttpTelegramClient(botToken);
        this.commandContainer = commandContainer;
        this.userService = userService;
        this.stateResolver = stateResolver;
    }

    @Override
    public void consume(Update update) {
        Long chatId = update.getMessage().getChatId();
        String text = update.getMessage().getText();
        User user = userService.findOrCreate(chatId, update.getMessage().getFrom().getUserName());

        if (user.getConversationState() == ConversationState.IDLE) {
            commandContainer.retrieveCommand(getCommandName(text))
                    .execute(update, telegramClient);
        } else {
            stateResolver.resolve(user, update, telegramClient);
        }
    }

    private String getCommandName(String text) {
        if (text == null || text.isBlank()) {
            return UNKNOWN;
        }
        return text.trim().split("\\s+")[0].toLowerCase();
    }
}