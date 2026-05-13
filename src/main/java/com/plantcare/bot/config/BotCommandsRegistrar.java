package com.plantcare.bot.config;

import com.plantcare.bot.client.TelegramClientProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.List;

/**
 * Регистрирует команды бота в Telegram (меню команд) один раз при старте приложения.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BotCommandsRegistrar {

    private final TelegramClientProvider telegramClientProvider;

    @EventListener(ApplicationReadyEvent.class)
    public void registerCommands() {
        List<BotCommand> commands = List.of(
                new BotCommand("/start", "Начать / перезапустить бота"),
                new BotCommand("/menu", "Главное меню"),
                new BotCommand("/add", "Добавить растение"),
                new BotCommand("/calendar", "Календарь ухода на неделю"),
                new BotCommand("/cancel", "Отменить текущее действие"),
                new BotCommand("/help", "Помощь")
        );

        SetMyCommands setMyCommands = new SetMyCommands(commands, null, null);

        try {
            telegramClientProvider.getTelegramClient().execute(setMyCommands);
            log.info("Bot commands registered: {}", commands.stream()
                    .map(BotCommand::getCommand).toList());
        } catch (TelegramApiException e) {
            log.error("Failed to register bot commands: {}", e.getMessage(), e);
        }
    }
}