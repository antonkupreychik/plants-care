package com.plantcare.bot.command.impl;

import com.plantcare.bot.command.interfaces.BotCommand;
import com.plantcare.bot.domain.User;
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
public class CancelCommand implements BotCommand {

    private final UserService userService;

    @Override
    public String getCommandName() {
        return "/cancel";
    }

    @Override
    public void execute(Update update, TelegramClient client) {
        Long chatId = update.getMessage().getChatId();
        User user = userService.findByChatId(chatId)
                .orElseThrow(() -> new IllegalStateException("User not found on /cancel"));

        userService.resetToIdle(user);

        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text("Действие отменено. Я готов к новым командам.")
                .build();

        try {
            client.execute(message);
            log.info("User {} canceled the current operation", chatId); // Критерий 7
        } catch (TelegramApiException e) {
            log.error("Failed to send cancel message", e);
        }
    }
}