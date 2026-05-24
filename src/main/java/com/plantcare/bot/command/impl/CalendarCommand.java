package com.plantcare.bot.command.impl;

import com.plantcare.bot.command.interfaces.BotCommand;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.featureflag.FeatureFlag;
import com.plantcare.bot.service.CalendarMenuService;
import com.plantcare.bot.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Команда /calendar — недельный календарь ухода (issue #52).
 * Скрыт за feature flag {@link FeatureFlag#CALENDAR} (issue #78).
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

        if (!user.hasFeature(FeatureFlag.CALENDAR)) {
            // Команда зарегистрирована в BotCommandsRegistrar для autocomplete'а
            // Telegram, и убрать её оттуда условно для конкретного юзера нельзя.
            // Поэтому при выключенном флаге просто отвечаем заглушкой.
            sendUnavailable(chatId, client);
            return;
        }

        calendarMenuService.sendCalendar(user, client);
    }

    private void sendUnavailable(Long chatId, TelegramClient client) {
        SendMessage msg = SendMessage.builder()
                .chatId(chatId.toString())
                .text("Эта функция временно недоступна.")
                .build();
        try {
            client.execute(msg);
        } catch (TelegramApiException e) {
            log.warn("Failed to send calendar-unavailable to {}: {}",
                    chatId, e.getMessage());
        }
    }
}
