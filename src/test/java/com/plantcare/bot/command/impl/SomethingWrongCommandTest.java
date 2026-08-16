package com.plantcare.bot.command.impl;

import com.plantcare.core.service.MessageService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты {@link SomethingWrongCommand} — фолбэк-хендлер для нераспознанных
 * команд/сообщений («SOMETHINGWRONG»).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-тесты для SomethingWrongCommand")
class SomethingWrongCommandTest {

    @Mock
    private MessageService messageService;

    @Mock
    private TelegramClient telegramClient;

    @Mock
    private Update update;

    @Mock
    private Message message;

    @InjectMocks
    private SomethingWrongCommand command;

    @Test
    @DisplayName("Команда зарегистрирована как SOMETHINGWRONG")
    void should_return_somethingwrong_name_when_getCommandName_called() {
        assertThat(command.getCommandName()).isEqualTo("SOMETHINGWRONG");
    }

    @Test
    @DisplayName("Шлёт локализованный текст 'что-то пошло не так' в чат отправителя")
    void should_send_fallback_message_when_executed() throws TelegramApiException {
        Long chatId = 321L;

        when(update.getMessage()).thenReturn(message);
        when(message.getChatId()).thenReturn(chatId);
        when(messageService.get(eq(chatId), eq("command.something_wrong.text")))
                .thenReturn("Что-то пошло не так");

        command.execute(update, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        org.mockito.Mockito.verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getChatId()).isEqualTo(chatId.toString());
        assertThat(captor.getValue().getText()).isEqualTo("Что-то пошло не так");
    }

    @Test
    @DisplayName("Отправка падает с TelegramApiException: ошибка перехвачена, execute не бросает")
    void should_swallow_telegram_exception_when_send_fails() throws TelegramApiException {
        Long chatId = 654L;

        when(update.getMessage()).thenReturn(message);
        when(message.getChatId()).thenReturn(chatId);
        when(messageService.get(eq(chatId), eq("command.something_wrong.text")))
                .thenReturn("Что-то пошло не так");
        when(telegramClient.execute(any(SendMessage.class)))
                .thenThrow(new TelegramApiException("network down"));

        command.execute(update, telegramClient);

        // Дошли сюда — исключение не всплыло наружу.
        org.mockito.Mockito.verify(telegramClient).execute(any(SendMessage.class));
    }
}
