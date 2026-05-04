package com.plantcare.bot.state.impl;

import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.service.UserService;
import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.Serializable;

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
    }

    @Test
    @DisplayName("Успешное сохранение таймзоны при получении CallbackQuery")
    void shouldSaveTimezoneAndTransitionStateOnCallback() throws TelegramApiException {
        // GIVEN
        String selectedTz = "Europe/Riga";
        Update update = mock(Update.class);
        CallbackQuery callbackQuery = mock(CallbackQuery.class);
        when(update.hasCallbackQuery()).thenReturn(true);
        when(update.getCallbackQuery()).thenReturn(callbackQuery);
        when(callbackQuery.getData()).thenReturn("SET_TZ:" + selectedTz);
        when(callbackQuery.getId()).thenReturn("query_123");

        // WHEN
        handler.handle(testUser, update, telegramClient);

        // THEN
        // 1. Проверяем обновление сущности пользователя
        assertThat(testUser.getTimezone()).isEqualTo(selectedTz);
        assertThat(testUser.getConversationState()).isEqualTo(ConversationState.AWAITING_PLANT_NAME);

        // 2. Проверяем вызовы Telegram API
        verify(telegramClient).execute(any(SendMessage.class));
        verify(telegramClient).execute(any(AnswerCallbackQuery.class));
    }

    @Test
    @DisplayName("Отправка напоминания при получении текста вместо клика по кнопке")
    void shouldSendReminderWhenTextReceivedInsteadOfCallback() throws TelegramApiException {
        // GIVEN
        Update update = mock(Update.class);
        Message message = mock(Message.class);
        when(update.hasCallbackQuery()).thenReturn(false);
        when(update.hasMessage()).thenReturn(true);
        when(update.getMessage()).thenReturn(message);
        when(message.getText()).thenReturn("Москва");

        // WHEN
        handler.handle(testUser, update, telegramClient);

        // THEN
        // 1. Состояние не должно измениться
        assertThat(testUser.getConversationState()).isEqualTo(ConversationState.AWAITING_TIMEZONE_MANUAL);

        // 2. Должно быть отправлено сообщение с напоминанием
        verify(telegramClient).execute((BotApiMethod<Serializable>) argThat(msg -> {
            if (msg instanceof SendMessage sm) {
                return sm.getText().contains("Пожалуйста, выбери город из списка");
            }
            return false;
        }));

        // AnswerCallbackQuery не должен вызываться
        verify(telegramClient, never()).execute(any(AnswerCallbackQuery.class));
    }
}