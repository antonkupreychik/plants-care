package com.plantcare.bot.state.impl;

import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.ConversationState;
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
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-тесты для AwaitingLocationNameStateHandler")
class AwaitingLocationNameStateHandlerTest {

    @Mock
    private UserService userService;

    @Mock
    private LocationService locationService;

    @Mock
    private TelegramClient telegramClient;

    private AwaitingLocationNameStateHandler handler;
    private User user;

    @BeforeEach
    void setUp() {
        handler = new AwaitingLocationNameStateHandler(userService, locationService);

        user = User.builder()
                .telegramChatId(123L)
                .username("test_user")
                .stateData(new HashMap<>())
                .build();
    }

    @Test
    @DisplayName("Поддерживает состояние AWAITING_LOCATION_NAME")
    void shouldSupportAwaitingLocationNameState() {
        assertThat(handler.getSupportedState())
                .isEqualTo(ConversationState.AWAITING_LOCATION_NAME);
    }

    @Test
    @DisplayName("Сохраняет валидное название комнаты и переводит к выбору emoji")
    void shouldSaveValidLocationNameAndAskEmoji() throws TelegramApiException {
        Update update = textUpdate("Кухня");

        when(locationService.hasReachedLocationsLimit(user.getId())).thenReturn(false);
        when(locationService.getUserLocations(user.getId())).thenReturn(List.of());

        handler.handle(user, update, telegramClient);

        verify(userService).setStateData(user, "location_name", "Кухня");
        verify(userService).updateState(user, ConversationState.AWAITING_LOCATION_EMOJI);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        SendMessage message = captor.getValue();

        assertThat(message.getChatId()).isEqualTo("123");
        assertThat(message.getText()).contains("Выбери emoji");
        assertThat(message.getReplyMarkup()).isInstanceOf(InlineKeyboardMarkup.class);
    }

    @Test
    @DisplayName("Отклоняет пустое название комнаты")
    void shouldRejectBlankLocationName() throws TelegramApiException {
        Update update = textUpdate("   ");

        handler.handle(user, update, telegramClient);

        verify(userService, never()).setStateData(any(), anyString(), any());
        verify(userService, never()).updateState(any(), any());

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        assertThat(captor.getValue().getText())
                .contains("Название комнаты должно быть от 1 до 30 символов");
    }

    @Test
    @DisplayName("Отклоняет название длиннее 30 символов")
    void shouldRejectTooLongLocationName() throws TelegramApiException {
        Update update = textUpdate("А".repeat(31));

        handler.handle(user, update, telegramClient);

        verify(userService, never()).setStateData(any(), anyString(), any());
        verify(userService, never()).updateState(any(), any());

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        assertThat(captor.getValue().getText())
                .contains("Название комнаты должно быть от 1 до 30 символов");
    }

    @Test
    @DisplayName("Отклоняет создание комнаты при лимите 20 комнат")
    void shouldRejectWhenLocationsLimitReached() throws TelegramApiException {
        Update update = textUpdate("Балкон");

        when(locationService.hasReachedLocationsLimit(user.getId())).thenReturn(true);
        when(locationService.getMaxLocationsPerUser()).thenReturn(20);

        handler.handle(user, update, telegramClient);

        verify(userService, never()).setStateData(any(), anyString(), any());
        verify(userService, never()).updateState(any(), eq(ConversationState.AWAITING_LOCATION_EMOJI));
        verify(userService).resetToIdle(user);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        assertThat(captor.getValue().getText())
                .contains("Можно создать максимум 20 комнат");
    }

    @Test
    @DisplayName("Отклоняет дубликат названия комнаты без учёта регистра")
    void shouldRejectDuplicateLocationNameIgnoringCase() throws TelegramApiException {
        Update update = textUpdate("кухня");

        Location existingLocation = Location.builder()
                .user(user)
                .name("Кухня")
                .emoji("🍳")
                .defaultLocation(false)
                .build();

        when(locationService.hasReachedLocationsLimit(user.getId())).thenReturn(false);
        when(locationService.getUserLocations(user.getId())).thenReturn(List.of(existingLocation));

        handler.handle(user, update, telegramClient);

        verify(userService, never()).setStateData(any(), anyString(), any());
        verify(userService, never()).updateState(any(), eq(ConversationState.AWAITING_LOCATION_EMOJI));

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

        verifyNoInteractions(userService);
        verifyNoInteractions(locationService);
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