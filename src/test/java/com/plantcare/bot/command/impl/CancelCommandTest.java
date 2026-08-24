package com.plantcare.bot.command.impl;

import com.plantcare.core.domain.User;
import com.plantcare.core.service.MessageService;
import com.plantcare.core.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты {@link CancelCommand} (/cancel).
 *
 * <p>Три ветки: happy path (сброс состояния + подтверждение), пользователь
 * не найден (IllegalStateException до любых side-эффектов) и провал отправки
 * Telegram (перехватывается, resetToIdle к этому моменту уже применён).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-тесты для CancelCommand (/cancel)")
class CancelCommandTest {

    @Mock
    private UserService userService;

    @Mock
    private MessageService messageService;

    @Mock
    private TelegramClient telegramClient;

    @Mock
    private Update update;

    @Mock
    private Message message;

    @InjectMocks
    private CancelCommand cancelCommand;

    @Test
    @DisplayName("Команда зарегистрирована как /cancel")
    void should_return_cancel_name_when_getCommandName_called() {
        assertThat(cancelCommand.getCommandName()).isEqualTo("/cancel");
    }

    @Test
    @DisplayName("Юзер найден: resetToIdle вызывается и подтверждение уходит в чат")
    void should_reset_state_and_send_confirmation_when_user_found() throws TelegramApiException {
        Long chatId = 555L;
        User user = User.builder().telegramChatId(chatId).build();

        when(update.getMessage()).thenReturn(message);
        when(message.getChatId()).thenReturn(chatId);
        when(userService.findByChatId(chatId)).thenReturn(Optional.of(user));
        when(messageService.get(chatId, "command.cancel.confirmation")).thenReturn("Отменено");

        cancelCommand.execute(update, telegramClient);

        verify(userService).resetToIdle(user);

        var captor = org.mockito.ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getChatId()).isEqualTo(chatId.toString());
        assertThat(captor.getValue().getText()).isEqualTo("Отменено");
    }

    @Test
    @DisplayName("Юзер не найден: бросает IllegalStateException, resetToIdle не вызывается")
    void should_throw_illegal_state_when_user_not_found() {
        Long chatId = 777L;

        when(update.getMessage()).thenReturn(message);
        when(message.getChatId()).thenReturn(chatId);
        when(userService.findByChatId(chatId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cancelCommand.execute(update, telegramClient))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("User not found on /cancel");

        verify(userService, never()).resetToIdle(any());
    }

    @Test
    @DisplayName("Отправка падает с TelegramApiException: ошибка перехвачена, resetToIdle уже применён")
    void should_swallow_telegram_exception_when_send_fails() throws TelegramApiException {
        Long chatId = 888L;
        User user = User.builder().telegramChatId(chatId).build();

        when(update.getMessage()).thenReturn(message);
        when(message.getChatId()).thenReturn(chatId);
        when(userService.findByChatId(chatId)).thenReturn(Optional.of(user));
        when(messageService.get(eq(chatId), eq("command.cancel.confirmation"))).thenReturn("Отменено");
        when(telegramClient.execute(any(SendMessage.class)))
                .thenThrow(new TelegramApiException("network down"));

        cancelCommand.execute(update, telegramClient);

        // Исключение не должно всплыть — resetToIdle уже выполнен к моменту отправки.
        verify(userService).resetToIdle(user);
    }
}
