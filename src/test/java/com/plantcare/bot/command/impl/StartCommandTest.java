package com.plantcare.bot.command.impl;

import com.plantcare.bot.service.TelegramAuthLinkService;
import com.plantcare.core.domain.User;
import com.plantcare.core.service.LocationSharingService;
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
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-тесты для StartCommand (/start)")
class StartCommandTest {

    @Mock
    private TelegramClient telegramClient;

    @Mock
    private Update update;

    @Mock
    private Message message;

    @Mock
    private UserService userService;

    @Mock
    private MenuCommand menuCommand;

    @Mock
    private MessageService messageService;

    @Mock
    private LocationSharingService locationSharingService;

    @Mock
    private TelegramAuthLinkService telegramAuthLinkService;

    @InjectMocks
    private StartCommand startCommand;

    @Test
    @DisplayName("Команда зарегистрирована как /start")
    void shouldHaveCorrectCommandName() {
        assertThat(startCommand.getCommandName()).isEqualTo("/start");
    }

    @Test
    @DisplayName("Первый /start отправляет приветствие и открывает меню")
    void shouldSendGreetingOnFirstStart() throws Exception {
        Long chatId = 100L;

        User user = User.builder()
                .telegramChatId(chatId)
                .timezone("Europe/Minsk")
                .stateData(new HashMap<String, Object>())
                .build();

        when(update.getMessage()).thenReturn(message);
        when(message.getChatId()).thenReturn(chatId);
        when(userService.findByChatId(chatId)).thenReturn(Optional.of(user));
        when(messageService.get(eq(chatId), eq("command.start.greeting"))).thenReturn("greeting text");

        startCommand.execute(update, telegramClient);

        verify(telegramClient).execute(any(SendMessage.class));
        verify(userService).setStateData(user, "start_greeting_shown", "true");
        verify(menuCommand).execute(update, telegramClient);
    }

    @Test
    @DisplayName("Повторный /start не отправляет приветствие, но открывает меню")
    void shouldNotSendGreetingAgain() throws Exception {
        Long chatId = 100L;

        Map<String, Object> stateData = new HashMap<>();
        stateData.put("start_greeting_shown", "true");

        User user = User.builder()
                .telegramChatId(chatId)
                .timezone("Europe/Minsk")
                .stateData(stateData)
                .build();

        when(update.getMessage()).thenReturn(message);
        when(message.getChatId()).thenReturn(chatId);
        when(userService.findByChatId(chatId)).thenReturn(Optional.of(user));

        startCommand.execute(update, telegramClient);

        verify(telegramClient, never()).execute(any(SendMessage.class));
        verify(userService, never()).setStateData(any(), any(), any());
        verify(menuCommand).execute(update, telegramClient);
    }

    @Test
    @DisplayName("/start invite_<token> принимает приглашение и шлёт сообщение «Вас пригласили…»")
    void shouldAcceptInviteFromDeepLink() throws Exception {
        Long chatId = 100L;

        User user = User.builder()
                .telegramChatId(chatId)
                .timezone("Europe/Minsk")
                .stateData(new HashMap<String, Object>())
                .build();

        when(update.getMessage()).thenReturn(message);
        when(message.getChatId()).thenReturn(chatId);
        when(update.hasMessage()).thenReturn(true);
        when(message.hasText()).thenReturn(true);
        when(message.getText()).thenReturn("/start invite_abc123");
        when(userService.findByChatId(chatId)).thenReturn(Optional.of(user));
        when(locationSharingService.acceptInvite(eq("abc123"), eq(user)))
                .thenReturn(LocationSharingService.AcceptResult.accepted("🛋 Гостиная", 1L, com.plantcare.core.domain.enums.AccessRole.CARETAKER));

        startCommand.execute(update, telegramClient);

        verify(locationSharingService).acceptInvite("abc123", user);
        verify(telegramClient).execute(any(SendMessage.class));
        verify(menuCommand).execute(update, telegramClient);
    }

    @Test
    @DisplayName("/start auth_<sessionId> делегирует вход в TelegramAuthLinkService с chat_id из update, меню не открывает")
    void shouldHandleAuthDeepLink() {
        Long chatId = 100L;

        User user = User.builder()
                .telegramChatId(chatId)
                .timezone("Europe/Minsk")
                .stateData(new HashMap<String, Object>())
                .build();

        when(update.getMessage()).thenReturn(message);
        when(message.getChatId()).thenReturn(chatId);
        when(update.hasMessage()).thenReturn(true);
        when(message.hasText()).thenReturn(true);
        when(message.getText()).thenReturn("/start auth_sess123");
        when(userService.findByChatId(chatId)).thenReturn(Optional.of(user));
        when(telegramAuthLinkService.extractSessionId("auth_sess123")).thenReturn("sess123");

        startCommand.execute(update, telegramClient);

        // chat_id берётся из update, не из payload — это и проверяем
        verify(telegramAuthLinkService).handleAuthLink("sess123", chatId, telegramClient);
        verify(menuCommand, never()).execute(any(), any());
        verifyNoInteractions(locationSharingService);
    }
}
