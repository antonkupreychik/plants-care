package com.plantcare.bot.state.impl;

import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.ConversationState;
import com.plantcare.bot.service.LocationMenuService;
import com.plantcare.core.service.LocationService;
import com.plantcare.core.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-тесты для AwaitingLocationEmojiStateHandler")
class AwaitingLocationEmojiStateHandlerTest {

    @Mock
    private UserService userService;

    @Mock
    private LocationService locationService;

    @Mock
    private LocationMenuService locationMenuService;

    @Mock
    private TelegramClient telegramClient;

    private AwaitingLocationEmojiStateHandler handler;
    private User user;

    @BeforeEach
    void setUp() {
        handler = new AwaitingLocationEmojiStateHandler(
                userService,
                locationService,
                locationMenuService
        );

        Map<String, Object> stateData = new HashMap<>();
        stateData.put("location_name", "Кухня");

        user = User.builder()
                .telegramChatId(123L)
                .username("test_user")
                .stateData(stateData)
                .build();
    }

    @Test
    @DisplayName("Поддерживает состояние AWAITING_LOCATION_EMOJI")
    void shouldSupportAwaitingLocationEmojiState() {
        assertThat(handler.getSupportedState())
                .isEqualTo(ConversationState.AWAITING_LOCATION_EMOJI);
    }

    @Test
    @DisplayName("Создаёт комнату по названию из stateData и выбранному emoji")
    void shouldCreateLocationAndReturnToLocationsMenu() throws TelegramApiException {
        Update update = textUpdate("🍳");

        Location savedLocation = Location.builder()
                .user(user)
                .name("Кухня")
                .emoji("🍳")
                .defaultLocation(false)
                .build();

        when(locationService.createLocation(user, "Кухня", "🍳"))
                .thenReturn(savedLocation);

        handler.handle(user, update, telegramClient);

        verify(locationService).createLocation(user, "Кухня", "🍳");
        verify(userService).resetToIdle(user);
        verify(locationMenuService).sendLocationsMenu(user, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        SendMessage message = captor.getValue();

        assertThat(message.getChatId()).isEqualTo("123");
        assertThat(message.getText()).contains("Комната создана");
        assertThat(message.getText()).contains("🍳 Кухня");
    }

    @Test
    @DisplayName("Отклоняет пустой emoji")
    void shouldRejectBlankEmoji() throws TelegramApiException {
        Update update = textUpdate("   ");

        handler.handle(user, update, telegramClient);

        verify(locationService, never()).createLocation(any(), anyString(), anyString());
        verify(userService, never()).resetToIdle(any());
        verify(locationMenuService, never()).sendLocationsMenu(any(), any());

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        assertThat(captor.getValue().getText())
                .contains("Emoji должен быть одним символом");
    }

    @Test
    @DisplayName("Отклоняет слишком длинный emoji")
    void shouldRejectTooLongEmoji() throws TelegramApiException {
        Update update = textUpdate("очень_длинный_emoji");

        handler.handle(user, update, telegramClient);

        verify(locationService, never()).createLocation(any(), anyString(), anyString());
        verify(userService, never()).resetToIdle(any());
        verify(locationMenuService, never()).sendLocationsMenu(any(), any());

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        assertThat(captor.getValue().getText())
                .contains("Emoji должен быть одним символом");
    }

    @Test
    @DisplayName("При ошибке создания комнаты отправляет текст ошибки и не открывает меню комнат")
    void shouldSendErrorWhenCreateLocationFails() throws TelegramApiException {
        Update update = textUpdate("🍳");

        when(locationService.createLocation(user, "Кухня", "🍳"))
                .thenThrow(new IllegalArgumentException("Такая комната уже есть"));

        handler.handle(user, update, telegramClient);

        verify(locationService).createLocation(user, "Кухня", "🍳");
        verify(locationMenuService, never()).sendLocationsMenu(any(), any());
        verify(userService, never()).resetToIdle(any());

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        assertThat(captor.getValue().getText())
                .contains("Такая комната уже есть");
    }

    @Test
    @DisplayName("Игнорирует update без текстового сообщения")
    void shouldIgnoreUpdateWithoutTextMessage() {
        Update update = mock(Update.class);

        when(update.hasMessage()).thenReturn(false);

        handler.handle(user, update, telegramClient);

        verifyNoInteractions(locationService);
        verifyNoInteractions(userService);
        verifyNoInteractions(locationMenuService);
        verifyNoInteractions(telegramClient);
    }

    private Update textUpdate(String text) {
        Update update = mock(Update.class);
        Message message = mock(Message.class);

        when(update.hasMessage()).thenReturn(true);
        when(update.getMessage()).thenReturn(message);
        when(message.hasText()).thenReturn(true);
        when(message.getText()).thenReturn(text);

        return update;
    }
}