/*
package com.plantcare.bot.state.impl;

import com.plantcare.bot.domain.CareSchedule;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.domain.enums.TaskType;
import com.plantcare.bot.repository.PlantRepository;
import com.plantcare.bot.service.MainMenuService;
import com.plantcare.bot.service.PlantService;
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
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Unit-тесты для AwaitingPlantFertilizingSetupStateHandler")
class AwaitingPlantFertilizingSetupStateHandlerTest {

    @Mock
    private UserService userService;

    @Mock
    private PlantService plantService;

    @Mock
    private PlantRepository plantRepository;

    @Mock
    private MainMenuService mainMenuService;

    @Mock
    private TelegramClient client;

    @InjectMocks
    private AwaitingPlantFertilizingSetupStateHandler handler;

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
                .isEqualTo(ConversationState.AWAITING_PLANT_FERTILIZING_SETUP);
    }

    @Test
    @DisplayName("FERTILIZING:DEFAULT — создаёт расписание удобрения на 14 дней и завершает создание")
    void shouldCreateDefaultFertilizingScheduleAndFinishCreation() throws Exception {
        Update update = callbackUpdate("FERTILIZING:DEFAULT");

        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(userId, plantId))
                .thenReturn(Optional.of(plant));

        when(plantService.getActiveSchedules(plantId))
                .thenReturn(List.of());

        handler.handle(user, update, client);

        verify(client).execute(any(AnswerCallbackQuery.class));

        verify(plantRepository).findByUserIdAndIdAndArchivedAtIsNull(userId, plantId);

        verify(plantService).addCareSchedule(
                eq(plant),
                eq(TaskType.FERTILIZING),
                eq(14),
                any(LocalDateTime.class)
        );

        verify(userService).resetToIdle(user);
        verify(mainMenuService).sendMainMenu(user, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());

        assertThat(captor.getValue().getText())
                .contains("Растение добавлено")
                .contains(plantName);
    }

    @Test
    @DisplayName("FERTILIZING:CUSTOM — ставит флаг ожидания ввода и просит интервал")
    void shouldAskForCustomFertilizingInterval() throws Exception {
        Update update = callbackUpdate("FERTILIZING:CUSTOM");

        handler.handle(user, update, client);

        verify(client).execute(any(AnswerCallbackQuery.class));

        verify(userService).setStateData(
                user,
                "fertilizing_awaiting_input",
                "true"
        );

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());

        assertThat(captor.getValue().getText())
                .contains("Введи интервал удобрения");

        verifyNoInteractions(plantRepository);
        verify(plantService, never()).addCareSchedule(any(), any(), anyInt(), any());
        verify(userService, never()).resetToIdle(user);
    }

    @Test
    @DisplayName("FERTILIZING:SKIP — не создаёт расписание, но завершает создание растения")
    void shouldSkipFertilizingAndFinishCreation() throws Exception {
        Update update = callbackUpdate("FERTILIZING:SKIP");

        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(userId, plantId))
                .thenReturn(Optional.of(plant));

        when(plantService.getActiveSchedules(plantId))
                .thenReturn(List.of());

        handler.handle(user, update, client);

        verify(client).execute(any(AnswerCallbackQuery.class));

        verify(plantService, never()).addCareSchedule(any(), any(), anyInt(), any());

        verify(userService).resetToIdle(user);
        verify(mainMenuService).sendMainMenu(user, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());

        assertThat(captor.getValue().getText())
                .contains("Растение добавлено")
                .contains(plantName);
    }

    @Test
    @DisplayName("Ручной ввод валидного интервала — создаёт расписание и завершает создание")
    void shouldCreateCustomFertilizingScheduleAndFinishCreation() throws Exception {
        user.getStateData().put("fertilizing_awaiting_input", "true");

        Update update = textUpdate("30");

        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(userId, plantId))
                .thenReturn(Optional.of(plant));

        when(plantService.getActiveSchedules(plantId))
                .thenReturn(List.of());

        handler.handle(user, update, client);

        verify(userService).removeStateData(user, "fertilizing_awaiting_input");

        verify(plantService).addCareSchedule(
                eq(plant),
                eq(TaskType.FERTILIZING),
                eq(30),
                any(LocalDateTime.class)
        );

        verify(userService).resetToIdle(user);
        verify(mainMenuService).sendMainMenu(user, client);
    }

    @Test
    @DisplayName("Ручной ввод не числа — отправляет ошибку")
    void shouldSendErrorWhenCustomIntervalIsNotNumber() throws Exception {
        user.getStateData().put("fertilizing_awaiting_input", "true");

        Update update = textUpdate("abc");

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());

        assertThat(captor.getValue().getText())
                .contains("Введи число от 1 до 365");

        verifyNoInteractions(plantRepository);
        verify(plantService, never()).addCareSchedule(any(), any(), anyInt(), any());
        verify(userService, never()).resetToIdle(user);
    }

    @Test
    @DisplayName("Ручной ввод интервала меньше 1 — отправляет ошибку")
    void shouldSendErrorWhenCustomIntervalTooSmall() throws Exception {
        user.getStateData().put("fertilizing_awaiting_input", "true");

        Update update = textUpdate("0");

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());

        assertThat(captor.getValue().getText())
                .contains("Интервал должен быть от 1 до 365 дней");

        verifyNoInteractions(plantRepository);
        verify(plantService, never()).addCareSchedule(any(), any(), anyInt(), any());
        verify(userService, never()).resetToIdle(user);
    }

    @Test
    @DisplayName("Ручной ввод интервала больше 365 — отправляет ошибку")
    void shouldSendErrorWhenCustomIntervalTooBig() throws Exception {
        user.getStateData().put("fertilizing_awaiting_input", "true");

        Update update = textUpdate("366");

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());

        assertThat(captor.getValue().getText())
                .contains("Интервал должен быть от 1 до 365 дней");

        verifyNoInteractions(plantRepository);
        verify(plantService, never()).addCareSchedule(any(), any(), anyInt(), any());
        verify(userService, never()).resetToIdle(user);
    }

    @Test
    @DisplayName("Если пришёл обычный текст без режима ручного ввода — просит выбрать кнопку")
    void shouldAskToUseButtonWhenTextReceivedWithoutAwaitingInput() throws Exception {
        Update update = textUpdate("30");

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());

        assertThat(captor.getValue().getText())
                .contains("Пожалуйста, выбери вариант кнопкой");

        verifyNoInteractions(plantRepository);
        verify(plantService, never()).addCareSchedule(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("Update без callback и без ручного ввода — отправляет подсказку")
    void shouldSendHintWhenUpdateWithoutCallbackAndInput() throws Exception {
        Update update = new Update();

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());

        assertThat(captor.getValue().getText())
                .contains("Пожалуйста, выбери вариант кнопкой");
    }

    @Test
    @DisplayName("Callback с неверным префиксом — игнорируется")
    void shouldIgnoreCallbackWithWrongPrefix() throws Exception {
        Update update = callbackUpdate("WRONG_PREFIX:DEFAULT");

        handler.handle(user, update, client);

        verify(client, never()).execute(any(AnswerCallbackQuery.class));
        verifyNoInteractions(plantRepository);
        verify(plantService, never()).addCareSchedule(any(), any(), anyInt(), any());
        verify(userService, never()).resetToIdle(user);
    }

    @Test
    @DisplayName("Неизвестный FERTILIZING callback — отправляет ошибку")
    void shouldSendErrorWhenFertilizingActionUnknown() throws Exception {
        Update update = callbackUpdate("FERTILIZING:UNKNOWN");

        handler.handle(user, update, client);

        verify(client).execute(any(AnswerCallbackQuery.class));

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());

        assertThat(captor.getValue().getText())
                .contains("Неизвестный вариант удобрения");

        verifyNoInteractions(plantRepository);
        verify(plantService, never()).addCareSchedule(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("Если plant_id отсутствует — отправляет ошибку и resetToIdle")
    void shouldResetToIdleWhenPlantIdMissing() throws Exception {
        user.getStateData().remove("plant_id");

        Update update = callbackUpdate("FERTILIZING:DEFAULT");

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client, atLeastOnce()).execute(captor.capture());

        assertThat(captor.getAllValues().stream().map(SendMessage::getText).toList())
                .anyMatch(text -> text.contains("Не удалось найти растение"));

        verify(userService).resetToIdle(user);
        verifyNoInteractions(plantRepository);
        verify(plantService, never()).addCareSchedule(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("Если plant_id некорректный — отправляет ошибку и resetToIdle")
    void shouldResetToIdleWhenPlantIdInvalid() throws Exception {
        user.getStateData().put("plant_id", "abc");

        Update update = callbackUpdate("FERTILIZING:DEFAULT");

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client, atLeastOnce()).execute(captor.capture());

        assertThat(captor.getAllValues().stream().map(SendMessage::getText).toList())
                .anyMatch(text -> text.contains("Некорректные данные растения"));

        verify(userService).resetToIdle(user);
        verifyNoInteractions(plantRepository);
        verify(plantService, never()).addCareSchedule(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("Если растение не найдено — отправляет ошибку и resetToIdle")
    void shouldResetToIdleWhenPlantNotFound() throws Exception {
        Update update = callbackUpdate("FERTILIZING:DEFAULT");

        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(userId, plantId))
                .thenReturn(Optional.empty());

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client, atLeastOnce()).execute(captor.capture());

        assertThat(captor.getAllValues().stream().map(SendMessage::getText).toList())
                .anyMatch(text -> text.contains("Растение не найдено"));

        verify(userService).resetToIdle(user);
        verify(plantService, never()).addCareSchedule(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("finishCreation показывает активные расписания")
    void shouldShowActiveSchedulesInFinishMessage() throws Exception {
        Update update = callbackUpdate("FERTILIZING:SKIP");

        CareSchedule watering = mock(CareSchedule.class);
        when(watering.getTaskType()).thenReturn(TaskType.WATERING);
        when(watering.getIntervalDays()).thenReturn(3);

        CareSchedule misting = mock(CareSchedule.class);
        when(misting.getTaskType()).thenReturn(TaskType.MISTING);
        when(misting.getIntervalDays()).thenReturn(5);

        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(userId, plantId))
                .thenReturn(Optional.of(plant));

        when(plantService.getActiveSchedules(plantId))
                .thenReturn(List.of(watering, misting));

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());

        assertThat(captor.getValue().getText())
                .contains("Напоминания")
                .contains("Полив")
                .contains("3")
                .contains("Опрыскивание")
                .contains("5");
    }

    @Test
    @DisplayName("TelegramApiException при отправке сообщения не валит handler")
    void shouldHandleTelegramApiExceptionWhenSendingMessage() throws Exception {
        Update update = callbackUpdate("FERTILIZING:SKIP");

        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(userId, plantId))
                .thenReturn(Optional.of(plant));

        when(plantService.getActiveSchedules(plantId))
                .thenReturn(List.of());

        doThrow(new TelegramApiException("API error"))
                .when(client)
                .execute(any(SendMessage.class));

        handler.handle(user, update, client);

        verify(userService).resetToIdle(user);
        verify(mainMenuService).sendMainMenu(user, client);
    }

    @Test
    @DisplayName("Ошибка при answerCallback не мешает продолжить обработку")
    void shouldContinueWhenAnswerCallbackFails() throws Exception {
        Update update = callbackUpdate("FERTILIZING:SKIP");

        doThrow(new RuntimeException("callback error"))
                .when(client)
                .execute(any(AnswerCallbackQuery.class));

        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(userId, plantId))
                .thenReturn(Optional.of(plant));

        when(plantService.getActiveSchedules(plantId))
                .thenReturn(List.of());

        handler.handle(user, update, client);

        verify(userService).resetToIdle(user);
        verify(mainMenuService).sendMainMenu(user, client);
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
}*/
