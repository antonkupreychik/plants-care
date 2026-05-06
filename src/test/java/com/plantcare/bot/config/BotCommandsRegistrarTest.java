package com.plantcare.bot.config;

import com.plantcare.bot.client.TelegramClientProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-тесты для BotCommandsRegistrar")
class BotCommandsRegistrarTest {

    @Mock private TelegramClientProvider telegramClientProvider;
    @Mock private TelegramClient telegramClient;

    @InjectMocks
    private BotCommandsRegistrar registrar;

    @Test
    @DisplayName("Регистрирует 5 команд: /start, /menu, /add, /cancel, /help")
    void shouldRegisterAllCommands() throws TelegramApiException {
        when(telegramClientProvider.getTelegramClient()).thenReturn(telegramClient);

        registrar.registerCommands();

        ArgumentCaptor<SetMyCommands> captor = ArgumentCaptor.forClass(SetMyCommands.class);
        verify(telegramClient).execute(captor.capture());

        List<BotCommand> commands = captor.getValue().getCommands();
        List<String> commandNames = commands.stream()
                .map(BotCommand::getCommand)
                .toList();

        assertThat(commandNames).containsExactlyInAnyOrder(
                "/start", "/menu", "/add", "/cancel", "/help"
        );
    }

    @Test
    @DisplayName("Каждая команда имеет описание")
    void shouldHaveDescriptionForEachCommand() throws TelegramApiException {
        when(telegramClientProvider.getTelegramClient()).thenReturn(telegramClient);

        registrar.registerCommands();

        ArgumentCaptor<SetMyCommands> captor = ArgumentCaptor.forClass(SetMyCommands.class);
        verify(telegramClient).execute(captor.capture());

        for (BotCommand cmd : captor.getValue().getCommands()) {
            assertThat(cmd.getDescription()).isNotBlank();
        }
    }

    @Test
    @DisplayName("Обрабатывает TelegramApiException без выброса")
    void shouldHandleTelegramApiException() throws TelegramApiException {
        when(telegramClientProvider.getTelegramClient()).thenReturn(telegramClient);
        when(telegramClient.execute(any(SetMyCommands.class)))
                .thenThrow(new TelegramApiException("Error"));

        registrar.registerCommands();

        verify(telegramClient).execute(any(SetMyCommands.class));
    }
}