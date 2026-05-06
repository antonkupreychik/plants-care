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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Unit-тесты для StartCommand (/start)")
class StartCommandTest {

    @Mock private TelegramClient telegramClient;
    @Mock private Update update;
    @Mock private Message message;
    @Mock private UserService userService;
    @Mock private MenuCommand menuCommand;

    @InjectMocks
    private StartCommand startCommand;

    private final Long chatId = 123L;
    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .telegramChatId(chatId)
                .build();

        when(update.getMessage()).thenReturn(message);
        when(message.getChatId()).thenReturn(chatId);
    }

    @Test
    @DisplayName("Команда зарегистрирована как /start")
    void shouldHaveCorrectCommandName() {
        assertThat(startCommand.getCommandName()).isEqualTo("/start");
    }

    @Test
    @DisplayName("Новый пользователь (UTC): приветствие + онбординг")
    void shouldStartOnboardingForNewUser() throws TelegramApiException {
        testUser.setTimezone("UTC");
        when(userService.findByChatId(chatId)).thenReturn(Optional.of(testUser));

        startCommand.execute(update, telegramClient);

        verify(userService).updateState(testUser, ConversationState.AWAITING_TIMEZONE);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient, times(2)).execute(captor.capture());

        assertThat(captor.getAllValues().get(0).getText()).contains("Привет");

        SendMessage onboarding = captor.getAllValues().get(1);
        assertThat(onboarding.getText()).contains("часовой пояс");
        assertThat(onboarding.getReplyMarkup()).isInstanceOf(ReplyKeyboardMarkup.class);
    }

    @Test
    @DisplayName("Существующий пользователь: делегирует в MenuCommand")
    void shouldDelegateToMenuForExistingUser() throws TelegramApiException {
        testUser.setTimezone("Europe/Riga");
        when(userService.findByChatId(chatId)).thenReturn(Optional.of(testUser));

        startCommand.execute(update, telegramClient);

        verify(menuCommand).execute(update, telegramClient);
        verify(userService, never()).updateState(any(), any());
    }

    @Test
    @DisplayName("Существующий пользователь: не отправляет приветствие напрямую")
    void shouldNotSendGreetingForExistingUser() throws TelegramApiException {
        testUser.setTimezone("Europe/Moscow");
        when(userService.findByChatId(chatId)).thenReturn(Optional.of(testUser));

        startCommand.execute(update, telegramClient);

        verify(telegramClient, never()).execute(any(SendMessage.class));
        verify(menuCommand).execute(update, telegramClient);
    }
}