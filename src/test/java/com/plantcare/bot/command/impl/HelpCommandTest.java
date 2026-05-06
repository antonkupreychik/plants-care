package com.plantcare.bot.command.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Unit-тесты для HelpCommand (/help)")
class HelpCommandTest {

    @Mock private TelegramClient telegramClient;
    @Mock private Update update;
    @Mock private Message message;

    @InjectMocks
    private HelpCommand helpCommand;

    private final Long chatId = 123L;

    @BeforeEach
    void setUp() {
        when(update.getMessage()).thenReturn(message);
        when(message.getChatId()).thenReturn(chatId);
    }

    @Test
    @DisplayName("Команда зарегистрирована как /help")
    void shouldHaveCorrectCommandName() {
        assertThat(helpCommand.getCommandName()).isEqualTo("/help");
    }

    @Test
    @DisplayName("Отправляет сообщение со списком всех команд")
    void shouldSendHelpMessageWithAllCommands() throws TelegramApiException {
        helpCommand.execute(update, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        String text = captor.getValue().getText();
        assertThat(text).contains("/start");
        assertThat(text).contains("/menu");
        assertThat(text).contains("/add");
        assertThat(text).contains("/cancel");
        assertThat(text).contains("/help");
    }

    @Test
    @DisplayName("Использует Markdown parseMode")
    void shouldUseMarkdown() throws TelegramApiException {
        helpCommand.execute(update, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        assertThat(captor.getValue().getParseMode()).isEqualTo("Markdown");
    }

    @Test
    @DisplayName("Обрабатывает TelegramApiException без выброса")
    void shouldHandleTelegramApiException() throws TelegramApiException {
        when(telegramClient.execute(any(SendMessage.class)))
                .thenThrow(new TelegramApiException("Error"));

        helpCommand.execute(update, telegramClient);

        verify(telegramClient).execute(any(SendMessage.class));
    }
}