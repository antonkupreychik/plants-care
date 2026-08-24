package com.plantcare.bot.state.impl;

import com.plantcare.bot.service.PlantCardService;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.ConversationState;
import com.plantcare.core.service.PlantService;
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
@DisplayName("Unit-тесты для AwaitingPlantNoteStateHandler")
class AwaitingPlantNoteStateHandlerTest {

    @Mock
    private UserService userService;

    @Mock
    private PlantService plantService;

    @Mock
    private PlantCardService plantCardService;

    @Mock
    private TelegramClient telegramClient;

    @InjectMocks
    private AwaitingPlantNoteStateHandler handler;

    private User user;

    @BeforeEach
    void setUp() {
        Map<String, Object> stateData = new HashMap<>();
        stateData.put("edit_plant_id", "8");
        stateData.put("edit_back_target", "CARD");

        user = User.builder()
                .telegramChatId(600L)
                .stateData(stateData)
                .build();
        ReflectionTestUtils.setField(user, "id", 1L);
    }

    @Test
    @DisplayName("Поддерживает состояние AWAITING_PLANT_NOTE")
    void shouldSupportExpectedState() {
        assertThat(handler.getSupportedState())
                .isEqualTo(ConversationState.AWAITING_PLANT_NOTE);
    }

    @Test
    @DisplayName("Сохраняет заметку и открывает экран настроек")
    void shouldSaveNoteAndShowSettingsScreen() throws TelegramApiException {
        Update update = textUpdate("Поливать реже зимой");

        handler.handle(user, update, telegramClient);

        verify(plantService).updateNotes(1L, 8L, "Поливать реже зимой");
        verify(userService).resetToIdle(user);
        verify(plantCardService).showSettingsScreen(user, 8L, null, "CARD", telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Заметка сохранена");
    }

    @Test
    @DisplayName("Просит текст заметки, если апдейт без текста")
    void shouldPromptWhenUpdateHasNoText() throws TelegramApiException {
        Update update = mock(Update.class);
        when(update.hasMessage()).thenReturn(false);

        handler.handle(user, update, telegramClient);

        verifyNoInteractions(plantService, plantCardService, userService);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Пришли текст заметки");
    }

    @Test
    @DisplayName("Отклоняет заметку длиннее 2000 символов")
    void shouldRejectTooLongNote() throws TelegramApiException {
        Update update = textUpdate("а".repeat(2001));

        handler.handle(user, update, telegramClient);

        verifyNoInteractions(plantService, plantCardService, userService);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("слишком длинная");
        assertThat(captor.getValue().getText()).contains("2000");
    }

    @Test
    @DisplayName("Сбрасывает в IDLE, если edit_plant_id отсутствует")
    void shouldResetToIdleWhenPlantIdMissing() throws TelegramApiException {
        user.setStateData(new HashMap<>());
        Update update = textUpdate("Заметка");

        handler.handle(user, update, telegramClient);

        verify(userService).resetToIdle(user);
        verifyNoInteractions(plantService, plantCardService);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Контекст редактирования утерян");
    }

    @Test
    @DisplayName("Показывает ошибку и не сбрасывает state, если сервис бросает IllegalArgumentException")
    void shouldShowErrorAndKeepStateWhenServiceThrows() throws TelegramApiException {
        Update update = textUpdate("Заметка");
        doThrow(new IllegalArgumentException("Растение не найдено"))
                .when(plantService).updateNotes(1L, 8L, "Заметка");

        handler.handle(user, update, telegramClient);

        verify(userService, never()).resetToIdle(any());
        verifyNoInteractions(plantCardService);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Растение не найдено");
    }

    @Test
    @DisplayName("Не бросает исключение наружу, если отправка подсказки падает")
    void shouldNotThrowWhenHintSendFails() throws TelegramApiException {
        Update update = mock(Update.class);
        when(update.hasMessage()).thenReturn(false);
        when(telegramClient.execute(any(SendMessage.class)))
                .thenThrow(new TelegramApiException("boom"));

        handler.handle(user, update, telegramClient);

        verify(telegramClient).execute(any(SendMessage.class));
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
