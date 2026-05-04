package com.plantcare.bot.command.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StartCommandTest {

    @Mock
    private TelegramClient telegramClient;

    @Mock
    private Update update;

    @Mock
    private Message message;

    private final StartCommand startCommand = new StartCommand();

    @Test
    void shouldExecuteStartCommand() throws TelegramApiException {
        Long chatId = 123L;
        when(update.getMessage()).thenReturn(message);
        when(message.getChatId()).thenReturn(chatId);

        startCommand.execute(update, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        assertEquals(chatId.toString(), captor.getValue().getChatId());
        assertEquals("Привет! Я бот проекта Plants-care. Я помогу тебе ухаживать за твоими растениями.", captor.getValue().getText());
    }
}