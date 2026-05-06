package com.plantcare.bot.command.impl;

import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.service.UserService;
import org.junit.jupiter.api.BeforeEach;
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
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StartCommandTest {

    @Mock
    private TelegramClient telegramClient;

    @Mock
    private Update update;

    @Mock
    private Message message;

    @Mock
    private UserService userService;

    @InjectMocks
    private StartCommand startCommand;

    private final Long chatId = 123L;
    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setTelegramChatId(chatId);

        when(update.getMessage()).thenReturn(message);
        when(message.getChatId()).thenReturn(chatId);
    }

    @Test
    @DisplayName("Новый пользователь: должен начаться онбординг (запрос таймзоны)")
    void shouldStartOnboardingForNewUser() throws TelegramApiException {
        // GIVEN: Пользователь с дефолтной таймзоной
        testUser.setTimezone("UTC");
        when(userService.findByChatId(chatId)).thenReturn(Optional.of(testUser));

        // WHEN
        startCommand.execute(update, telegramClient);

        // THEN
        // Проверяем смену состояния (Критерий 5 задачи #7)
        verify(userService).updateState(testUser, ConversationState.AWAITING_TIMEZONE);

        // Проверяем отправку сообщений (приветствие + вопрос про таймзону)
        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient, times(2)).execute(captor.capture());

        List<SendMessage> sentMessages = captor.getAllValues();

        // Второе сообщение должно содержать клавиатуру с кнопкой локации (Критерий 2)
        SendMessage onboardingMsg = sentMessages.get(0);
        assertTrue(onboardingMsg.getText().contains("установить часовой пояс"));
        assertTrue(onboardingMsg.getReplyMarkup() instanceof ReplyKeyboardMarkup);
    }

    @Test
    @DisplayName("Существующий пользователь: должна показаться заглушка меню")
    void shouldShowMainMenuForExistingUser() throws TelegramApiException {
        // GIVEN: Пользователь с уже установленной таймзоной
        testUser.setTimezone("Europe/Riga");
        when(userService.findByChatId(chatId)).thenReturn(Optional.of(testUser));

        // WHEN
        startCommand.execute(update, telegramClient);

        // THEN
        // Состояние не должно меняться на AWAITING_TIMEZONE
        verify(userService, never()).updateState(any(), any());

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient, times(2)).execute(captor.capture());

        // Проверяем текст заглушки (Критерий 6 задачи #7)
        SendMessage lastMsg = captor.getAllValues().get(0);
        assertTrue(lastMsg.getText().contains("С возвращением! Главное меню скоро будет"));
    }
}