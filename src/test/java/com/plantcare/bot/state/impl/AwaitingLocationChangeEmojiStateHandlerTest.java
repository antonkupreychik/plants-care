package com.plantcare.bot.state.impl;

import com.plantcare.bot.domain.Location;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.service.LocationMenuService;
import com.plantcare.bot.service.LocationService;
import com.plantcare.bot.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-тесты для AwaitingLocationChangeEmojiStateHandler")
class AwaitingLocationChangeEmojiStateHandlerTest {

    @Mock
    private UserService userService;

    @Mock
    private LocationService locationService;

    @Mock
    private LocationMenuService locationMenuService;

    @Mock
    private TelegramClient telegramClient;

    private AwaitingLocationChangeEmojiStateHandler handler;

    private User user;

    private final Long userId = 10L;
    private final Long chatId = 12345L;
    private final Long locationId = 55L;

    @BeforeEach
    void setUp() {
        handler = new AwaitingLocationChangeEmojiStateHandler(
                userService,
                locationService,
                locationMenuService
        );

        user = User.builder()
                .telegramChatId(chatId)
                .build();

        ReflectionTestUtils.setField(user, "id", userId);

        Map<String, Object> stateData = new HashMap<>();
        stateData.put("editing_location_id", String.valueOf(locationId));
        user.setStateData(stateData);
    }

    @Test
    @DisplayName("Поддерживает состояние AWAITING_LOCATION_CHANGE_EMOJI")
    void shouldSupportAwaitingLocationChangeEmojiState() {
        assertThat(handler.getSupportedState())
                .isEqualTo(ConversationState.AWAITING_LOCATION_CHANGE_EMOJI);
    }

    @Test
    @DisplayName("Меняет emoji через callback и возвращает экран комнаты")
    void shouldChangeEmojiFromCallback() throws TelegramApiException {
        Update update = mock(Update.class);
        CallbackQuery callbackQuery = mock(CallbackQuery.class);

        when(update.hasCallbackQuery()).thenReturn(true);
        when(update.getCallbackQuery()).thenReturn(callbackQuery);
        when(callbackQuery.getData()).thenReturn("LOCATION_CHANGE_EMOJI:🌿");
        when(callbackQuery.getId()).thenReturn("callback-1");

        Location updatedLocation = Location.builder()
                .user(user)
                .name("Балкон")
                .emoji("🌿")
                .defaultLocation(false)
                .build();

        ReflectionTestUtils.setField(updatedLocation, "id", locationId);

        when(locationService.changeEmoji(userId, locationId, "🌿"))
                .thenReturn(updatedLocation);

        handler.handle(user, update, telegramClient);

        verify(locationService).changeEmoji(userId, locationId, "🌿");
        verify(userService).resetToIdle(user);
        verify(locationMenuService).sendLocationScreen(user, locationId, telegramClient);

        verify(telegramClient).execute(any(AnswerCallbackQuery.class));

        ArgumentCaptor<SendMessage> messageCaptor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(messageCaptor.capture());

        SendMessage message = messageCaptor.getValue();

        assertThat(message.getChatId()).isEqualTo(chatId.toString());
        assertThat(message.getText()).contains("✅ Emoji изменён");
        assertThat(message.getText()).contains("🌿 Балкон");
    }

    @Test
    @DisplayName("Меняет emoji через текстовое сообщение")
    void shouldChangeEmojiFromTextMessage() throws TelegramApiException {
        Update update = mock(Update.class);
        Message message = mock(Message.class);

        when(update.hasCallbackQuery()).thenReturn(false);
        when(update.hasMessage()).thenReturn(true);
        when(update.getMessage()).thenReturn(message);
        when(message.hasText()).thenReturn(true);
        when(message.getText()).thenReturn("☀️");

        Location updatedLocation = Location.builder()
                .user(user)
                .name("Окно")
                .emoji("☀️")
                .defaultLocation(false)
                .build();

        ReflectionTestUtils.setField(updatedLocation, "id", locationId);

        when(locationService.changeEmoji(userId, locationId, "☀️"))
                .thenReturn(updatedLocation);

        handler.handle(user, update, telegramClient);

        verify(locationService).changeEmoji(userId, locationId, "☀️");
        verify(userService).resetToIdle(user);
        verify(locationMenuService).sendLocationScreen(user, locationId, telegramClient);

        verify(telegramClient, never()).execute(any(AnswerCallbackQuery.class));

        ArgumentCaptor<SendMessage> messageCaptor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(messageCaptor.capture());

        assertThat(messageCaptor.getValue().getText())
                .contains("✅ Emoji изменён")
                .contains("☀️ Окно");
    }

    @Test
    @DisplayName("Игнорирует callback с неправильным prefix")
    void shouldIgnoreCallbackWithWrongPrefix() throws TelegramApiException {
        Update update = mock(Update.class);
        CallbackQuery callbackQuery = mock(CallbackQuery.class);

        when(update.hasCallbackQuery()).thenReturn(true);
        when(update.getCallbackQuery()).thenReturn(callbackQuery);
        when(callbackQuery.getData()).thenReturn("WRONG_PREFIX:🌿");

        handler.handle(user, update, telegramClient);

        verifyNoInteractions(locationService);
        verifyNoInteractions(userService);
        verifyNoInteractions(locationMenuService);
        verifyNoInteractions(telegramClient);
    }

    @Test
    @DisplayName("Отправляет ошибку от LocationService и не сбрасывает состояние")
    void shouldSendValidationErrorWhenLocationServiceThrowsIllegalArgumentException() throws TelegramApiException {
        Update update = mock(Update.class);
        Message message = mock(Message.class);

        when(update.hasCallbackQuery()).thenReturn(false);
        when(update.hasMessage()).thenReturn(true);
        when(update.getMessage()).thenReturn(message);
        when(message.hasText()).thenReturn(true);
        when(message.getText()).thenReturn("abc");

        when(locationService.changeEmoji(userId, locationId, "abc"))
                .thenThrow(new IllegalArgumentException("Emoji должен быть одним emoji-символом"));

        handler.handle(user, update, telegramClient);

        verify(locationService).changeEmoji(userId, locationId, "abc");
        verify(userService, never()).resetToIdle(user);
        verify(locationMenuService, never()).sendLocationScreen(any(), any(), any());

        ArgumentCaptor<SendMessage> messageCaptor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(messageCaptor.capture());

        assertThat(messageCaptor.getValue().getText())
                .contains("❌ Emoji должен быть одним emoji-символом");
    }

    @Test
    @DisplayName("При неожиданной ошибке отправляет сообщение и сбрасывает состояние")
    void shouldResetToIdleWhenUnexpectedExceptionHappens() throws TelegramApiException {
        Update update = mock(Update.class);
        Message message = mock(Message.class);

        when(update.hasCallbackQuery()).thenReturn(false);
        when(update.hasMessage()).thenReturn(true);
        when(update.getMessage()).thenReturn(message);
        when(message.hasText()).thenReturn(true);
        when(message.getText()).thenReturn("🌸");

        when(locationService.changeEmoji(userId, locationId, "🌸"))
                .thenThrow(new RuntimeException("DB error"));

        handler.handle(user, update, telegramClient);

        verify(locationService).changeEmoji(userId, locationId, "🌸");
        verify(userService).resetToIdle(user);
        verify(locationMenuService, never()).sendLocationScreen(any(), any(), any());

        ArgumentCaptor<SendMessage> messageCaptor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(messageCaptor.capture());

        assertThat(messageCaptor.getValue().getText())
                .contains("❌ Не получилось сменить emoji");
    }

    @Test
    @DisplayName("Игнорирует update без callback и без текстового сообщения")
    void shouldIgnoreUpdateWithoutCallbackAndTextMessage() throws TelegramApiException {
        Update update = mock(Update.class);

        when(update.hasCallbackQuery()).thenReturn(false);
        when(update.hasMessage()).thenReturn(false);

        handler.handle(user, update, telegramClient);

        verifyNoInteractions(locationService);
        verifyNoInteractions(userService);
        verifyNoInteractions(locationMenuService);
        verifyNoInteractions(telegramClient);
    }
}