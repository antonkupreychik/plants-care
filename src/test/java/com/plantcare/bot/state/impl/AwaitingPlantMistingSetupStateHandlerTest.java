package com.plantcare.bot.state.impl;

import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.ConversationState;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.repository.PlantRepository;
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
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-тесты для AwaitingPlantMistingSetupStateHandler")
class AwaitingPlantMistingSetupStateHandlerTest {

    @Mock
    private UserService userService;

    @Mock
    private PlantService plantService;

    @Mock
    private PlantRepository plantRepository;

    @Mock
    private TelegramClient client;

    @InjectMocks
    private AwaitingPlantMistingSetupStateHandler handler;

    private User user;
    private Plant plant;

    private final Long userId = 7L;
    private final Long chatId = 123L;
    private final Long plantId = 42L;
    private final String plantName = "Монстера";

    @BeforeEach
    void setUp() {
        Map<String, Object> stateData = new HashMap<>();
        stateData.put("plant_id", plantId.toString());

        user = User.builder()
                .telegramChatId(chatId)
                .stateData(stateData)
                .build();

        plant = Plant.builder()
                .name(plantName)
                .build();

        setPrivateField(user, "id", userId);
        setPrivateField(plant, "id", plantId);
    }

    @Test
    @DisplayName("Возвращает корректное поддерживаемое состояние")
    void shouldReturnSupportedState() {
        assertThat(handler.getSupportedState())
                .isEqualTo(ConversationState.AWAITING_PLANT_MISTING_SETUP);
    }

    @Test
    @DisplayName("MISTING:DEFAULT создаёт расписание опрыскивания на 3 дня и переводит к удобрению")
    void shouldCreateDefaultMistingScheduleAndProceedToFertilizing() throws Exception {
        Update update = callbackUpdate("MISTING:DEFAULT");

        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(userId, plantId))
                .thenReturn(Optional.of(plant));

        handler.handle(user, update, client);

        verify(client).execute(any(AnswerCallbackQuery.class));

        ArgumentCaptor<LocalDateTime> nextDueAtCaptor = ArgumentCaptor.forClass(LocalDateTime.class);

        verify(plantService).addCareSchedule(
                eq(plant),
                eq(TaskType.MISTING),
                eq(3),
                nextDueAtCaptor.capture()
        );

        assertThat(nextDueAtCaptor.getValue()).isAfter(LocalDateTime.now());

        verify(userService).updateState(user, ConversationState.AWAITING_PLANT_FERTILIZING_SETUP);

        ArgumentCaptor<SendMessage> messageCaptor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(messageCaptor.capture());

        SendMessage message = messageCaptor.getValue();
        assertThat(message.getText())
                .contains("Нужно ли удобрять")
                .contains(plantName);

        assertThat(message.getReplyMarkup()).isInstanceOf(InlineKeyboardMarkup.class);
    }

    @Test
    @DisplayName("MISTING:CUSTOM включает ожидание ввода интервала и отправляет просьбу ввести число")
    void shouldAskForCustomMistingInterval() throws Exception {
        Update update = callbackUpdate("MISTING:CUSTOM");

        handler.handle(user, update, client);

        verify(client).execute(any(AnswerCallbackQuery.class));
        verify(userService).setStateData(user, "misting_awaiting_input", "true");

        ArgumentCaptor<SendMessage> messageCaptor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(messageCaptor.capture());

        assertThat(messageCaptor.getValue().getText())
                .contains("Введи интервал опрыскивания")
                .contains("от 1 до 365");

        verifyNoInteractions(plantService);
        verify(plantRepository, never()).findByUserIdAndIdAndArchivedAtIsNull(any(), any());
    }

    @Test
    @DisplayName("MISTING:SKIP не создаёт расписание и переводит к удобрению")
    void shouldSkipMistingAndProceedToFertilizing() throws Exception {
        Update update = callbackUpdate("MISTING:SKIP");

        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(userId, plantId))
                .thenReturn(Optional.of(plant));

        handler.handle(user, update, client);

        verify(client).execute(any(AnswerCallbackQuery.class));
        verify(plantService, never()).addCareSchedule(any(), any(), anyInt(), any());
        verify(userService).updateState(user, ConversationState.AWAITING_PLANT_FERTILIZING_SETUP);

        ArgumentCaptor<SendMessage> messageCaptor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(messageCaptor.capture());

        assertThat(messageCaptor.getValue().getText())
                .contains("Нужно ли удобрять")
                .contains(plantName);
    }

    @Test
    @DisplayName("Неизвестный callback только отвечает на callback и ничего не делает")
    void shouldIgnoreUnknownCallback() throws Exception {
        Update update = callbackUpdate("MISTING:UNKNOWN");

        handler.handle(user, update, client);

        verify(client).execute(any(AnswerCallbackQuery.class));
        verifyNoInteractions(userService);
        verifyNoInteractions(plantService);
        verifyNoInteractions(plantRepository);
        verify(client, never()).execute(any(SendMessage.class));
    }

    @Test
    @DisplayName("Текстовый ввод игнорируется, если нет флага misting_awaiting_input")
    void shouldIgnoreTextWhenNotAwaitingCustomInput() throws Exception {
        Update update = textUpdate("5");

        handler.handle(user, update, client);

        verifyNoInteractions(userService);
        verifyNoInteractions(plantService);
        verifyNoInteractions(plantRepository);
        verify(client, never()).execute(any(SendMessage.class));
    }

    @Test
    @DisplayName("Корректный кастомный интервал создаёт расписание и переводит к удобрению")
    void shouldCreateCustomMistingScheduleAndProceedToFertilizing() throws Exception {
        user.getStateData().put("misting_awaiting_input", "true");

        Update update = textUpdate("5");

        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(userId, plantId))
                .thenReturn(Optional.of(plant));

        handler.handle(user, update, client);

        verify(userService).removeStateData(user, "misting_awaiting_input");

        verify(plantService).addCareSchedule(
                eq(plant),
                eq(TaskType.MISTING),
                eq(5),
                any(LocalDateTime.class)
        );

        verify(userService).updateState(user, ConversationState.AWAITING_PLANT_FERTILIZING_SETUP);

        ArgumentCaptor<SendMessage> messageCaptor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(messageCaptor.capture());

        assertThat(messageCaptor.getValue().getText())
                .contains("Нужно ли удобрять")
                .contains(plantName);
    }

    @Test
    @DisplayName("Нечисловой кастомный интервал отправляет ошибку")
    void shouldSendErrorWhenCustomIntervalIsNotNumber() throws Exception {
        user.getStateData().put("misting_awaiting_input", "true");

        Update update = textUpdate("abc");

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> messageCaptor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(messageCaptor.capture());

        assertThat(messageCaptor.getValue().getText())
                .contains("Введи число от 1 до 365");

        verifyNoInteractions(plantRepository);
        verifyNoInteractions(plantService);
        verify(userService, never()).removeStateData(user, "misting_awaiting_input");
        verify(userService, never()).updateState(user, ConversationState.AWAITING_PLANT_FERTILIZING_SETUP);
    }

    @Test
    @DisplayName("Кастомный интервал меньше 1 отправляет ошибку")
    void shouldSendErrorWhenCustomIntervalIsTooSmall() throws Exception {
        user.getStateData().put("misting_awaiting_input", "true");

        Update update = textUpdate("0");

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> messageCaptor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(messageCaptor.capture());

        assertThat(messageCaptor.getValue().getText())
                .contains("Интервал должен быть от 1 до 365");

        verifyNoInteractions(plantRepository);
        verifyNoInteractions(plantService);
        verify(userService, never()).removeStateData(user, "misting_awaiting_input");
    }

    @Test
    @DisplayName("Кастомный интервал больше 365 отправляет ошибку")
    void shouldSendErrorWhenCustomIntervalIsTooBig() throws Exception {
        user.getStateData().put("misting_awaiting_input", "true");

        Update update = textUpdate("366");

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> messageCaptor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(messageCaptor.capture());

        assertThat(messageCaptor.getValue().getText())
                .contains("Интервал должен быть от 1 до 365");

        verifyNoInteractions(plantRepository);
        verifyNoInteractions(plantService);
        verify(userService, never()).removeStateData(user, "misting_awaiting_input");
    }

    @Test
    @DisplayName("Если растение не найдено при создании MISTING, расписание не создаётся, но переход к удобрению происходит")
    void shouldProceedToFertilizingWhenPlantNotFoundDuringMistingScheduleCreation() throws Exception {
        Update update = callbackUpdate("MISTING:DEFAULT");

        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(userId, plantId))
                .thenReturn(Optional.empty());

        handler.handle(user, update, client);

        verify(plantService, never()).addCareSchedule(any(), any(), anyInt(), any());
        verify(userService).updateState(user, ConversationState.AWAITING_PLANT_FERTILIZING_SETUP);

        ArgumentCaptor<SendMessage> messageCaptor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(messageCaptor.capture());

        assertThat(messageCaptor.getValue().getText())
                .contains("Нужно ли удобрять")
                .contains("растение");
    }

    @Test
    @DisplayName("Update без сообщения и callback игнорируется")
    void shouldIgnoreUpdateWithoutMessageAndCallback() throws Exception {
        Update update = new Update();

        handler.handle(user, update, client);

        verifyNoInteractions(userService);
        verifyNoInteractions(plantService);
        verifyNoInteractions(plantRepository);
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("TelegramApiException при отправке сообщения не валит handler")
    void shouldHandleTelegramApiExceptionWhenSendingMessage() throws Exception {
        Update update = callbackUpdate("MISTING:SKIP");

        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(userId, plantId))
                .thenReturn(Optional.of(plant));

        doThrow(new TelegramApiException("API error"))
                .when(client)
                .execute(any(SendMessage.class));

        handler.handle(user, update, client);

        verify(userService).updateState(user, ConversationState.AWAITING_PLANT_FERTILIZING_SETUP);
    }

    @Test
    @DisplayName("Ошибка при ответе на callback не валит handler")
    void shouldHandleAnswerCallbackExceptionAndContinue() throws Exception {
        Update update = callbackUpdate("MISTING:SKIP");

        doThrow(new RuntimeException("callback error"))
                .when(client)
                .execute(any(AnswerCallbackQuery.class));

        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(userId, plantId))
                .thenReturn(Optional.of(plant));

        handler.handle(user, update, client);

        verify(userService).updateState(user, ConversationState.AWAITING_PLANT_FERTILIZING_SETUP);
        verify(client).execute(any(SendMessage.class));
    }

    private Update textUpdate(String text) {
        Update update = new Update();

        Message message = new Message();
        message.setText(text);

        update.setMessage(message);

        return update;
    }

    private Update callbackUpdate(String data) {
        Update update = new Update();

        CallbackQuery callbackQuery = new CallbackQuery();
        callbackQuery.setId("callback-id");
        callbackQuery.setData(data);

        update.setCallbackQuery(callbackQuery);

        return update;
    }

    private void setPrivateField(Object target, String fieldName, Object value) {
        try {
            Class<?> clazz = target.getClass();
            Field field = null;

            while (clazz != null) {
                try {
                    field = clazz.getDeclaredField(fieldName);
                    break;
                } catch (NoSuchFieldException e) {
                    clazz = clazz.getSuperclass();
                }
            }

            if (field == null) {
                throw new RuntimeException("Field not found: " + fieldName);
            }

            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field: " + fieldName, e);
        }
    }
}