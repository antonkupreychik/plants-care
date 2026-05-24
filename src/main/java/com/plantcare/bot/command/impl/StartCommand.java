package com.plantcare.bot.command.impl;

import com.plantcare.bot.command.interfaces.BotCommand;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.service.MessageService;
import com.plantcare.bot.service.UserService;
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
public class StartCommand implements BotCommand {

    private static final String START_GREETING_SHOWN = "start_greeting_shown";

    private final UserService userService;
    private final MenuCommand menuCommand;
    private final MessageService messageService;

    @Override
    public String getCommandName() {
        return "/start";
    }

    @Override
    public void execute(Update update, TelegramClient client) {
        Long chatId = update.getMessage().getChatId();

        User user = userService.findByChatId(chatId)
                .orElseThrow(() -> new IllegalStateException("User not found"));

        if (!hasGreetingBeenShown(user)) {
            sendGreeting(chatId, client);
            userService.setStateData(user, START_GREETING_SHOWN, "true");
        }

        menuCommand.execute(update, client);
    }

    private boolean hasGreetingBeenShown(User user) {
        return user.getStateData() != null
                && "true".equals(user.getStateData().get(START_GREETING_SHOWN));
    }

    private void sendGreeting(Long chatId, TelegramClient client) {
        SendMessage greeting = SendMessage.builder()
                .chatId(chatId.toString())
                .text(messageService.get(chatId, "command.start.greeting"))
                .build();

        try {
            client.execute(greeting);
        } catch (TelegramApiException e) {
            log.error("Failed to send greeting in /start", e);
        }
    }
}
