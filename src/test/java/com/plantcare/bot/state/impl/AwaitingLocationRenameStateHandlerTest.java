package com.plantcare.bot.state.impl;

import com.plantcare.bot.service.LocationMenuService;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
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
@DisplayName("Unit-тесты для AwaitingLocationRenameStateHandler")
class AwaitingLocationRenameStateHandlerTest {

    @Mock
    private UserService userService;

    @Mock
    private LocationService locationService;

    @Mock
    private LocationMenuService locationMenuService;

    @Mock
    private TelegramClient telegramClient;

    @InjectMocks
    private AwaitingLocationRenameStateHandler handler;

    private User user;

    @BeforeEach
    void setUp() {
        Map<String, Object> stateData = new HashMap<>();
        stateData.put("editing_location_id", "9");

        user = User.builder()
                .telegramChatId(700L)
                .stateData(stateData)
                .build();
        ReflectionTestUtils.setField(user, "id", 1L);
    }

    @Test
    @DisplayName("Поддерживает состояние AWAITING_LOCATION_RENAME")
    void shouldSupportExpectedState() {
        assertThat(handler.getSupportedState())
                .isEqualTo(ConversationState.AWAITING_LOCATION_RENAME);
    }

    @Test
    @DisplayName("Переименовывает комнату и открывает экран комнаты при валидном имени")
    void shouldRenameLocationAndShowLocationScreen() throws TelegramApiException {
        Update update = textUpdate("Спальня");
        Location renamed = Location.builder().name("Спальня").emoji("🛏️").build();
        ReflectionTestUtils.setField(renamed, "id", 9L);

        when(locationService.renameLocation(1L, 9L, "Спальня")).thenReturn(renamed);

        handler.handle(user, update, telegramClient);

        verify(userService).resetToIdle(user);
        verify(locationMenuService).sendLocationScreen(user, 9L, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Комната переименована");
        assertThat(captor.getValue().getText()).contains("🛏️ Спальня");
    }

    @Test
    @DisplayName("Игнорирует апдейт без текста")
    void shouldIgnoreUpdateWithoutText() {
        Update update = mock(Update.class);
        when(update.hasMessage()).thenReturn(false);

        handler.handle(user, update, telegramClient);

        verifyNoInteractions(locationService, locationMenuService, userService, telegramClient);
    }

    @Test
    @DisplayName("Сбрасывает в IDLE и шлёт ошибку, если editing_location_id отсутствует")
    void shouldResetToIdleAndSendErrorWhenLocationIdMissing() throws TelegramApiException {
        user.setStateData(new HashMap<>());
        Update update = textUpdate("Спальня");

        handler.handle(user, update, telegramClient);

        verify(userService).resetToIdle(user);
        verifyNoInteractions(locationService, locationMenuService);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Не получилось переименовать комнату");
    }

    @Test
    @DisplayName("Сбрасывает в IDLE и шлёт ошибку, если сервис бросает исключение (напр. дубликат имени)")
    void shouldResetToIdleAndSendErrorWhenServiceThrows() throws TelegramApiException {
        Update update = textUpdate("Спальня");
        when(locationService.renameLocation(1L, 9L, "Спальня"))
                .thenThrow(new IllegalArgumentException("Комната с таким именем уже есть"));

        handler.handle(user, update, telegramClient);

        verify(userService).resetToIdle(user);
        verifyNoInteractions(locationMenuService);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Комната с таким именем уже есть");
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
