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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Coverage-тесты для AwaitingPlantFertilizingSetupStateHandler")
class AwaitingPlantFertilizingSetupStateHandlerCoverageTest {

    @Mock
    private UserService userService;

    @Mock
    private PlantService plantService;

    @Mock
    private PlantRepository plantRepository;

    @Mock
    private TelegramClient client;

    private AwaitingPlantFertilizingSetupStateHandler handler;

    private User user;
    private Plant plant;

    private final Long userId = 7L;
    private final Long chatId = 123L;
    private final Long plantId = 42L;

    @BeforeEach
    void setUp() {
        handler = new AwaitingPlantFertilizingSetupStateHandler(userService, plantService, plantRepository);

        Map<String, Object> stateData = new HashMap<>();
        stateData.put("plant_id", plantId.toString());

        user = User.builder()
                .telegramChatId(chatId)
                .stateData(stateData)
                .build();
        ReflectionTestUtils.setField(user, "id", userId);

        plant = Plant.builder()
                .name("Монстера")
                .build();
        ReflectionTestUtils.setField(plant, "id", plantId);
    }

    @Test
    @DisplayName("Поддерживает состояние AWAITING_PLANT_FERTILIZING_SETUP")
    void shouldSupportState() {
        assertThat(handler.getSupportedState())
                .isEqualTo(ConversationState.AWAITING_PLANT_FERTILIZING_SETUP);
    }

    @Test
    @DisplayName("FERTILIZING:DEFAULT создаёт расписание на 14 дней и переходит к фото")
    void shouldCreateDefaultScheduleAndAskForPhoto() throws TelegramApiException {
        Update update = callbackUpdate("FERTILIZING:DEFAULT");

        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(userId, plantId))
                .thenReturn(Optional.of(plant));

        handler.handle(user, update, client);

        verify(plantService).addCareSchedule(eq(plant), eq(TaskType.FERTILIZING), eq(14), any(LocalDateTime.class));
        verify(userService).updateState(user, ConversationState.AWAITING_PLANT_PHOTO);
        verify(client).execute(any(AnswerCallbackQuery.class));

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Пришли фото").contains("Монстера");
    }

    @Test
    @DisplayName("FERTILIZING:CUSTOM запрашивает интервал вводом")
    void shouldAskForCustomInterval() throws TelegramApiException {
        Update update = callbackUpdate("FERTILIZING:CUSTOM");

        handler.handle(user, update, client);

        verify(userService).setStateData(user, "fertilizing_awaiting_input", "true");

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Введи интервал удобрения");

        verify(plantService, never()).addCareSchedule(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("FERTILIZING:SKIP пропускает удобрение и переходит к фото")
    void shouldSkipAndAskForPhoto() throws TelegramApiException {
        Update update = callbackUpdate("FERTILIZING:SKIP");

        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(userId, plantId))
                .thenReturn(Optional.of(plant));

        handler.handle(user, update, client);

        verify(plantService, never()).addCareSchedule(any(), any(), anyInt(), any());
        verify(userService).updateState(user, ConversationState.AWAITING_PLANT_PHOTO);
    }

    @Test
    @DisplayName("Неизвестный FERTILIZING callback отправляет ошибку")
    void shouldSendErrorForUnknownAction() throws TelegramApiException {
        Update update = callbackUpdate("FERTILIZING:UNKNOWN");

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Неизвестный вариант удобрения");
    }

    @Test
    @DisplayName("Callback без префикса FERTILIZING: игнорируется")
    void shouldIgnoreUnrelatedCallback() throws TelegramApiException {
        Update update = callbackUpdate("WRONG:DEFAULT");

        handler.handle(user, update, client);

        verify(client, never()).execute(any(SendMessage.class));
        verifyNoInteractions(plantRepository);
    }

    @Test
    @DisplayName("Update без callback и без ожидания ввода — просит выбрать кнопку")
    void shouldPromptWhenNoCallbackAndNotAwaitingInput() throws TelegramApiException {
        Update update = new Update();

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Пожалуйста, выбери вариант кнопкой");
    }

    @Test
    @DisplayName("Ручной ввод валидного интервала создаёт расписание")
    void shouldCreateScheduleFromValidCustomInput() throws TelegramApiException {
        user.getStateData().put("fertilizing_awaiting_input", "true");
        Update update = textUpdate("30");

        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(userId, plantId))
                .thenReturn(Optional.of(plant));

        handler.handle(user, update, client);

        verify(userService).removeStateData(user, "fertilizing_awaiting_input");
        verify(plantService).addCareSchedule(eq(plant), eq(TaskType.FERTILIZING), eq(30), any(LocalDateTime.class));
        verify(userService).updateState(user, ConversationState.AWAITING_PLANT_PHOTO);
    }

    @Test
    @DisplayName("Ручной ввод не числа отправляет ошибку парсинга")
    void shouldRejectNonNumericCustomInput() throws TelegramApiException {
        user.getStateData().put("fertilizing_awaiting_input", "true");
        Update update = textUpdate("abc");

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Введи число от 1 до 365");
        verifyNoInteractions(plantRepository);
    }

    @Test
    @DisplayName("Ручной ввод интервала вне диапазона отправляет ошибку валидации")
    void shouldRejectOutOfRangeCustomInput() throws TelegramApiException {
        user.getStateData().put("fertilizing_awaiting_input", "true");
        Update update = textUpdate("366");

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Интервал должен быть от 1 до 365 дней");
        verifyNoInteractions(plantRepository);
    }

    @Test
    @DisplayName("Отсутствие plant_id в stateData сбрасывает пользователя в IDLE")
    void shouldResetWhenPlantIdMissing() throws TelegramApiException {
        user.getStateData().remove("plant_id");
        Update update = callbackUpdate("FERTILIZING:DEFAULT");

        handler.handle(user, update, client);

        verify(userService).resetToIdle(user);
        verifyNoInteractions(plantRepository);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Не удалось найти растение");
    }

    @Test
    @DisplayName("Некорректный plant_id сбрасывает пользователя в IDLE")
    void shouldResetWhenPlantIdInvalid() throws TelegramApiException {
        user.getStateData().put("plant_id", "not-a-number");
        Update update = callbackUpdate("FERTILIZING:DEFAULT");

        handler.handle(user, update, client);

        verify(userService).resetToIdle(user);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Некорректные данные растения");
    }

    @Test
    @DisplayName("Растение не найдено в БД сбрасывает пользователя в IDLE")
    void shouldResetWhenPlantNotFound() throws TelegramApiException {
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(userId, plantId))
                .thenReturn(Optional.empty());
        Update update = callbackUpdate("FERTILIZING:DEFAULT");

        handler.handle(user, update, client);

        verify(userService).resetToIdle(user);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Растение не найдено");
    }

    @Test
    @DisplayName("TelegramApiException при отправке фото-запроса не валит хендлер")
    void shouldSwallowTelegramApiExceptionOnPhotoPrompt() throws TelegramApiException {
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(userId, plantId))
                .thenReturn(Optional.of(plant));
        doThrow(new TelegramApiException("boom")).when(client).execute(any(SendMessage.class));

        Update update = callbackUpdate("FERTILIZING:SKIP");

        handler.handle(user, update, client);

        verify(userService).updateState(user, ConversationState.AWAITING_PLANT_PHOTO);
    }

    @Test
    @DisplayName("Ошибка ответа на callback не мешает продолжить обработку")
    void shouldContinueWhenAnswerCallbackFails() throws TelegramApiException {
        doThrow(new RuntimeException("callback failure")).when(client).execute(any(AnswerCallbackQuery.class));
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(userId, plantId))
                .thenReturn(Optional.of(plant));

        Update update = callbackUpdate("FERTILIZING:SKIP");

        handler.handle(user, update, client);

        verify(userService).updateState(user, ConversationState.AWAITING_PLANT_PHOTO);
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
        callbackQuery.setId("cb-id");
        callbackQuery.setData(data);
        update.setCallbackQuery(callbackQuery);
        return update;
    }
}
