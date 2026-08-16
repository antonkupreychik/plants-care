package com.plantcare.bot.state.impl;

import com.plantcare.bot.service.PlantCardService;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.ConversationState;
import com.plantcare.core.domain.enums.TaskType;
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
@DisplayName("Unit-тесты для AwaitingScheduleIntervalStateHandler")
class AwaitingScheduleIntervalStateHandlerTest {

    @Mock
    private UserService userService;

    @Mock
    private PlantService plantService;

    @Mock
    private PlantCardService plantCardService;

    @Mock
    private TelegramClient telegramClient;

    @InjectMocks
    private AwaitingScheduleIntervalStateHandler handler;

    private User user;

    @BeforeEach
    void setUp() {
        Map<String, Object> stateData = new HashMap<>();
        stateData.put("edit_plant_id", "3");
        stateData.put("edit_task_type", "WATERING");
        stateData.put("edit_back_target", "CARD");

        user = User.builder()
                .telegramChatId(500L)
                .stateData(stateData)
                .build();
        ReflectionTestUtils.setField(user, "id", 1L);
    }

    @Test
    @DisplayName("Поддерживает состояние AWAITING_NEW_INTERVAL")
    void shouldSupportExpectedState() {
        assertThat(handler.getSupportedState())
                .isEqualTo(ConversationState.AWAITING_NEW_INTERVAL);
    }

    @Test
    @DisplayName("Обновляет интервал и показывает экран расписания при валидном числе")
    void shouldUpdateIntervalAndShowScheduleScreen() throws TelegramApiException {
        Update update = textUpdate("7");

        handler.handle(user, update, telegramClient);

        verify(plantService).updateScheduleInterval(1L, 3L, TaskType.WATERING, 7);
        verify(userService).resetToIdle(user);
        verify(plantCardService).showScheduleEditByType(
                user, 3L, TaskType.WATERING, null, "CARD", telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("каждые 7 дн.");
    }

    @Test
    @DisplayName("Просит число, если апдейт без текста")
    void shouldPromptWhenUpdateHasNoText() throws TelegramApiException {
        Update update = mock(Update.class);
        when(update.hasMessage()).thenReturn(false);

        handler.handle(user, update, telegramClient);

        verifyNoInteractions(plantService, plantCardService, userService);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Пришли число от 1 до 365");
    }

    @Test
    @DisplayName("Отклоняет нечисловой ввод")
    void shouldRejectNonNumericInput() throws TelegramApiException {
        Update update = textUpdate("семь");

        handler.handle(user, update, telegramClient);

        verifyNoInteractions(plantService, plantCardService, userService);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Это не число");
    }

    @Test
    @DisplayName("Отклоняет число вне диапазона 1..365")
    void shouldRejectOutOfRangeNumber() throws TelegramApiException {
        Update update = textUpdate("400");

        handler.handle(user, update, telegramClient);

        verifyNoInteractions(plantService, plantCardService, userService);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("от 1 до 365 дней");
    }

    @Test
    @DisplayName("Сбрасывает в IDLE, если edit_plant_id отсутствует")
    void shouldResetToIdleWhenPlantIdMissing() throws TelegramApiException {
        user.getStateData().remove("edit_plant_id");
        Update update = textUpdate("7");

        handler.handle(user, update, telegramClient);

        verify(userService).resetToIdle(user);
        verifyNoInteractions(plantService, plantCardService);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Контекст редактирования утерян");
    }

    @Test
    @DisplayName("Сбрасывает в IDLE, если edit_task_type отсутствует")
    void shouldResetToIdleWhenTaskTypeMissing() throws TelegramApiException {
        user.getStateData().remove("edit_task_type");
        Update update = textUpdate("7");

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
        Update update = textUpdate("7");
        doThrow(new IllegalArgumentException("Растение не найдено"))
                .when(plantService).updateScheduleInterval(1L, 3L, TaskType.WATERING, 7);

        handler.handle(user, update, telegramClient);

        verify(userService, never()).resetToIdle(any());
        verifyNoInteractions(plantCardService);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Растение не найдено");
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
