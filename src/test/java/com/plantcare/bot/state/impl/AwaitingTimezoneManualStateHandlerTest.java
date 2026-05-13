package com.plantcare.bot.state.impl;

import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.service.UserService;
import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("Тесты обработчика ручного выбора таймзоны")
class AwaitingTimezoneManualStateHandlerTest extends IntegrationTestBase {

    @Autowired
    private AwaitingTimezoneManualStateHandler handler;

    @Autowired
    private UserService userService;

    @MockBean
    private TelegramClient telegramClient;

    private User testUser;

    private final Long chatId = 12345L;

    @BeforeEach
    void setUp() {
        testUser = userService.findOrCreate(chatId, "test_user");
        userService.updateState(testUser, ConversationState.AWAITING_TIMEZONE_MANUAL);
        reset(telegramClient);
    }

    @Test
    @DisplayName("Callback SET_TZ сохраняет таймзону и возвращает пользователя в IDLE")
    void shouldSaveTimezoneAndReturnToIdleOnCallback() throws TelegramApiException {
        String selectedTz = "Europe/Berlin";

        Update update = mock(Update.class);
        CallbackQuery callbackQuery = mock(CallbackQuery.class);

        when(update.hasCallbackQuery()).thenReturn(true);
        when(update.getCallbackQuery()).thenReturn(callbackQuery);
        when(callbackQuery.getData()).thenReturn("SET_TZ:" + selectedTz);
        when(callbackQuery.getId()).thenReturn("query_123");

        handler.handle(testUser, update, telegramClient);

        User reloadedUser = userService.findByChatId(chatId).orElseThrow();

        assertThat(reloadedUser.getTimezone()).isEqualTo(selectedTz);
        assertThat(reloadedUser.getConversationState()).isEqualTo(ConversationState.IDLE);

        ArgumentCaptor<SendMessage> messageCaptor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(messageCaptor.capture());

        SendMessage sentMessage = messageCaptor.getValue();

        assertThat(sentMessage.getChatId()).isEqualTo(chatId.toString());
        assertThat(sentMessage.getText()).contains("Часовой пояс обновлён");
        assertThat(sentMessage.getText()).contains(selectedTz);
        assertThat(sentMessage.getText()).contains("/menu");

        verify(telegramClient).execute(any(AnswerCallbackQuery.class));
    }

    @Test
    @DisplayName("Текст вместо callback не меняет состояние")
    void shouldSendReminderWhenTextReceivedInsteadOfCallback() throws TelegramApiException {
        Update update = mock(Update.class);
        Message message = mock(Message.class);

        when(update.hasCallbackQuery()).thenReturn(false);
        when(update.hasMessage()).thenReturn(true);
        when(update.getMessage()).thenReturn(message);
        when(message.hasText()).thenReturn(true);
        when(message.getText()).thenReturn("Москва");

        handler.handle(testUser, update, telegramClient);

        User reloadedUser = userService.findByChatId(chatId).orElseThrow();

        assertThat(reloadedUser.getConversationState())
                .isEqualTo(ConversationState.AWAITING_TIMEZONE_MANUAL);

        ArgumentCaptor<SendMessage> messageCaptor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(messageCaptor.capture());

        SendMessage sentMessage = messageCaptor.getValue();

        assertThat(sentMessage.getChatId()).isEqualTo(chatId.toString());
        assertThat(sentMessage.getText()).contains("Пожалуйста, выбери регион");

        verify(telegramClient, never()).execute(any(AnswerCallbackQuery.class));
    }
}