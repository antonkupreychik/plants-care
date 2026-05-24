package com.plantcare.bot.command.impl;

import com.plantcare.bot.service.MessageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-тесты для UnknownCommand")
class UnknownCommandTest {

    @Mock
    private TelegramClient telegramClient;

    @Mock
    private Update update;

    @Mock
    private Message message;

    @Mock
    private MessageService messageService;

    @InjectMocks
    private UnknownCommand unknownCommand;

    @Test
    @DisplayName("Должен возвращать корректное имя команды")
    void shouldReturnCorrectCommandName() {
        assertEquals("UNKNOWN", unknownCommand.getCommandName());
    }

    @Test
    @DisplayName("Должен отправлять сообщение о неизвестной команде")
    void shouldSendUnknownCommandMessage() throws TelegramApiException {
        // Given
        Long chatId = 12345L;
        when(update.getMessage()).thenReturn(message);
        when(message.getChatId()).thenReturn(chatId);
        when(messageService.get(anyLong(), eq("command.unknown.text")))
                .thenReturn("Извини, я не знаю такой команды. Попробуй /help.");

        // When
        unknownCommand.execute(update, telegramClient);

        // Then
        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient, times(1)).execute(captor.capture());

        SendMessage sentMessage = captor.getValue();
        assertEquals(chatId.toString(), sentMessage.getChatId());
        assertEquals("Извини, я не знаю такой команды. Попробуй /help.", sentMessage.getText());
    }

    @Test
    @DisplayName("Должен обрабатывать TelegramApiException без выброса исключения наружу")
    void shouldHandleTelegramApiException() throws TelegramApiException {
        // Given
        when(update.getMessage()).thenReturn(message);
        when(message.getChatId()).thenReturn(999L);
        when(messageService.get(anyLong(), eq("command.unknown.text"))).thenReturn("unknown");
        // Настраиваем мок так, чтобы он выбрасывал исключение
        when(telegramClient.execute(any(SendMessage.class))).thenThrow(new TelegramApiException("API Error"));

        // When & Then
        // Тест пройдет успешно, если исключение не пробросится выше (будет поймано в блоке catch)
        unknownCommand.execute(update, telegramClient);

        verify(telegramClient).execute(any(SendMessage.class));
    }
}
