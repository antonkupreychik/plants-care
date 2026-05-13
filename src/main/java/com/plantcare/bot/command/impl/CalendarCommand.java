package com.plantcare.bot.command.impl;

import com.plantcare.bot.command.interfaces.BotCommand;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.service.CalendarMenuService;
import com.plantcare.bot.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Команда /calendar — недельный календарь ухода (issue #52).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CalendarCommand implements BotCommand {

    private final UserService userService;
    private final CalendarMenuService calendarMenuService;

    @Override
    public String getCommandName() {
        return "/calendar";
    }

    @Override
    public void execute(Update update, TelegramClient client) {
        Long chatId = update.getMessage().getChatId();

        User user = userService.findByChatId(chatId)
                .orElseThrow(() -> new IllegalStateException("User not found"));

        calendarMenuService.sendCalendar(user, client);
    }
}
