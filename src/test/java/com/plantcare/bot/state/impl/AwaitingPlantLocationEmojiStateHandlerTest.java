package com.plantcare.bot.state.impl;

import com.plantcare.bot.domain.Location;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.service.LocationService;
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
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Unit-тесты для AwaitingPlantLocationEmojiStateHandler")
class AwaitingPlantLocationEmojiStateHandlerTest {

    @Mock private UserService userService;
    @Mock private LocationService locationService;
    @Mock private PlantService plantService;
    @Mock private TelegramClient client;

    @InjectMocks
    private AwaitingPlantLocationEmojiStateHandler handler;

    private User user;
    private Location location;
    private Plant plant;

    private final Long chatId = 123L;
    private final Long locationId = 10L;
    private final Long plantId = 42L;
    private final Long speciesId = 5L;

    private final String locationName = "Кухня";
    private final String locationEmoji = "🌿";
    private final String plantName = "Монстера";
    private final Integer intervalDays = 3;
    private final LocalDateTime nextDueAt = LocalDateTime.of(2026, 5, 10, 12, 0);

    @BeforeEach
    void setUp() {
        Map<String, Object> stateData = new HashMap<>();
        stateData.put("new_plant_location_name", locationName);
        stateData.put("species_id", speciesId.toString());
        stateData.put("interval_days", intervalDays.toString());
        stateData.put("plant_name", plantName);
        stateData.put("next_due_at", nextDueAt.toString());

        user = User.builder()
                .telegramChatId(chatId)
                .stateData(stateData)
                .build();

        location = Location.builder()
                .name(locationName)
                .emoji(locationEmoji)
                .build();

        plant = Plant.builder()
                .name(plantName)
                .build();

        setPrivateField(location, "id", locationId);
        setPrivateField(plant, "id", plantId);
    }

    // ==================== getSupportedState ====================

    @Test
    @DisplayName("Возвращает корректное поддерживаемое состояние")
    void shouldReturnSupportedState() {
        assertThat(handler.getSupportedState())
                .isEqualTo(ConversationState.AWAITING_PLANT_LOCATION_EMOJI);
    }

    // ==================== Happy path: text emoji ====================

    @Test
    @DisplayName("Текстовое emoji: создаёт Location, Plant, переводит в AWAITING_PLANT_MISTING_SETUP")
    void shouldCreateLocationAndPlantWhenTextEmojiIsValid() throws Exception {
        Update update = textUpdate(locationEmoji);
        when(locationService.createLocation(user, locationName, locationEmoji))
                .thenReturn(location);
        when(plantService.createPlantWithWateringSchedule(
                user, speciesId, plantName, intervalDays, nextDueAt, locationId))
                .thenReturn(plant);

        handler.handle(user, update, client);

        verify(locationService).createLocation(user, locationName, locationEmoji);
        verify(plantService).createPlantWithWateringSchedule(
                user, speciesId, plantName, intervalDays, nextDueAt, locationId);
        verify(userService).setStateData(user, "plant_id", plantId.toString());
        verify(userService).updateState(user, ConversationState.AWAITING_PLANT_MISTING_SETUP);
        verify(userService, never()).resetToIdle(user);
    }

    @Test
    @DisplayName("Отправляет два сообщения: подтверждение и вопрос про опрыскивание")
    void shouldSendConfirmationAndMistingQuestion() throws Exception {
        Update update = textUpdate(locationEmoji);
        when(locationService.createLocation(any(), any(), any())).thenReturn(location);
        when(plantService.createPlantWithWateringSchedule(any(), any(), any(), any(), any(), any()))
                .thenReturn(plant);

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client, times(2)).execute(captor.capture());

        // Первое сообщение — подтверждение создания комнаты
        assertThat(captor.getAllValues().get(0).getText()).contains("Комната создана");

        // Второе — вопрос об опрыскивании с inline-клавиатурой
        SendMessage mistingMsg = captor.getAllValues().get(1);
        assertThat(mistingMsg.getText()).contains("опрыскивать").contains(plantName);
        assertThat(mistingMsg.getReplyMarkup()).isInstanceOf(InlineKeyboardMarkup.class);
    }

    // ==================== Happy path: callback emoji ====================

    @Test
    @DisplayName("Callback с PLANT_LOCATION_EMOJI: создаёт Location, Plant, отвечает на callback")
    void shouldCreateLocationAndPlantWhenCallbackEmojiIsValid() throws Exception {
        Update update = callbackUpdate("PLANT_LOCATION_EMOJI:" + locationEmoji);
        when(locationService.createLocation(user, locationName, locationEmoji))
                .thenReturn(location);
        when(plantService.createPlantWithWateringSchedule(
                user, speciesId, plantName, intervalDays, nextDueAt, locationId))
                .thenReturn(plant);

        handler.handle(user, update, client);

        verify(client).execute(any(AnswerCallbackQuery.class));
        verify(locationService).createLocation(user, locationName, locationEmoji);
        verify(plantService).createPlantWithWateringSchedule(
                user, speciesId, plantName, intervalDays, nextDueAt, locationId);
        verify(userService).setStateData(user, "plant_id", plantId.toString());
        verify(userService).updateState(user, ConversationState.AWAITING_PLANT_MISTING_SETUP);
    }

    // ==================== Edge cases: input validation ====================

    @Test
    @DisplayName("Callback с неверным префиксом — игнорируется")
    void shouldIgnoreCallbackWithWrongPrefix() throws Exception {
        Update update = callbackUpdate("WRONG_PREFIX:" + locationEmoji);

        handler.handle(user, update, client);

        verifyNoInteractions(locationService);
        verifyNoInteractions(plantService);
        verifyNoInteractions(userService);
        verify(client, never()).execute(any(SendMessage.class));
        verify(client, never()).execute(any(AnswerCallbackQuery.class));
    }

    @Test
    @DisplayName("Update без message и callback — игнорируется")
    void shouldIgnoreUpdateWithoutMessageAndCallback() throws Exception {
        Update update = new Update();

        handler.handle(user, update, client);

        verifyNoInteractions(locationService);
        verifyNoInteractions(plantService);
        verifyNoInteractions(userService);
        verify(client, never()).execute(any(SendMessage.class));
    }

    @Test
    @DisplayName("Message без текста — игнорируется")
    void shouldIgnoreMessageWithoutText() throws Exception {
        Update update = new Update();
        Message message = new Message();
        // text не выставлен → hasText() == false
        update.setMessage(message);

        handler.handle(user, update, client);

        verifyNoInteractions(locationService);
        verifyNoInteractions(plantService);
        verifyNoInteractions(userService);
        verify(client, never()).execute(any(SendMessage.class));
    }

    @Test
    @DisplayName("Невалидный emoji (обычный текст) — отправляет ошибку, не вызывает сервисы")
    void shouldSendErrorWhenEmojiInvalid() throws Exception {
        Update update = textUpdate("abc");

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Emoji должен быть одним emoji-символом");

        verifyNoInteractions(locationService);
        verifyNoInteractions(plantService);
        verifyNoInteractions(userService);
    }

    // ==================== Error handling ====================

    @Test
    @DisplayName("IllegalArgumentException от createLocation — отправляет текст ошибки, без resetToIdle")
    void shouldSendErrorWhenCreateLocationThrowsIllegalArgumentException() throws Exception {
        Update update = textUpdate(locationEmoji);
        when(locationService.createLocation(user, locationName, locationEmoji))
                .thenThrow(new IllegalArgumentException("Такая комната уже есть"));

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Такая комната уже есть");

        verify(plantService, never())
                .createPlantWithWateringSchedule(any(), any(), any(), any(), any(), any());
        verify(userService, never()).resetToIdle(user);
        verify(userService, never()).updateState(any(), any());
    }

    @Test
    @DisplayName("Неожиданное исключение от createLocation — resetToIdle и сообщение об ошибке")
    void shouldResetStateWhenUnexpectedCreateLocationExceptionHappens() throws Exception {
        Update update = textUpdate(locationEmoji);
        when(locationService.createLocation(user, locationName, locationEmoji))
                .thenThrow(new RuntimeException("DB error"));

        handler.handle(user, update, client);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Не получилось создать комнату");

        verify(userService).resetToIdle(user);
        verifyNoInteractions(plantService);
    }

    @Test
    @DisplayName("Исключение от createPlant — resetToIdle")
    void shouldResetStateWhenCreatePlantFails() throws Exception {
        Update update = textUpdate(locationEmoji);
        when(locationService.createLocation(user, locationName, locationEmoji))
                .thenReturn(location);
        when(plantService.createPlantWithWateringSchedule(
                user, speciesId, plantName, intervalDays, nextDueAt, locationId))
                .thenThrow(new RuntimeException("Plant error"));

        handler.handle(user, update, client);

        verify(userService).resetToIdle(user);
        // setStateData("plant_id") и updateState не должны вызваться
        verify(userService, never()).setStateData(eq(user), eq("plant_id"), anyString());
        verify(userService, never()).updateState(user, ConversationState.AWAITING_PLANT_MISTING_SETUP);
    }

    // ==================== speciesId variations ====================

    @Test
    @DisplayName("species_id = 'null' (строка) → передаёт null в createPlant")
    void shouldSupportNullSpeciesIdAsString() throws Exception {
        user.getStateData().put("species_id", "null");
        Update update = textUpdate(locationEmoji);

        when(locationService.createLocation(any(), any(), any())).thenReturn(location);
        when(plantService.createPlantWithWateringSchedule(
                eq(user), eq(null), eq(plantName), eq(intervalDays), eq(nextDueAt), eq(locationId)))
                .thenReturn(plant);

        handler.handle(user, update, client);

        verify(plantService).createPlantWithWateringSchedule(
                user, null, plantName, intervalDays, nextDueAt, locationId);
    }

    @Test
    @DisplayName("species_id = '' → передаёт null в createPlant")
    void shouldSupportEmptySpeciesId() throws Exception {
        user.getStateData().put("species_id", "");
        Update update = textUpdate(locationEmoji);

        when(locationService.createLocation(any(), any(), any())).thenReturn(location);
        when(plantService.createPlantWithWateringSchedule(
                eq(user), eq(null), eq(plantName), eq(intervalDays), eq(nextDueAt), eq(locationId)))
                .thenReturn(plant);

        handler.handle(user, update, client);

        verify(plantService).createPlantWithWateringSchedule(
                user, null, plantName, intervalDays, nextDueAt, locationId);
    }

    @Test
    @DisplayName("species_id = null (отсутствует ключ) → передаёт null в createPlant")
    void shouldSupportMissingSpeciesId() throws Exception {
        user.getStateData().remove("species_id");
        Update update = textUpdate(locationEmoji);

        when(locationService.createLocation(any(), any(), any())).thenReturn(location);
        when(plantService.createPlantWithWateringSchedule(
                eq(user), eq(null), eq(plantName), eq(intervalDays), eq(nextDueAt), eq(locationId)))
                .thenReturn(plant);

        handler.handle(user, update, client);

        verify(plantService).createPlantWithWateringSchedule(
                user, null, plantName, intervalDays, nextDueAt, locationId);
    }

    // ==================== Telegram API errors ====================

    @Test
    @DisplayName("TelegramApiException при отправке сообщений — не валит handler, бизнес-логика выполнена")
    void shouldHandleTelegramApiExceptionWhenSendingMessages() throws Exception {
        Update update = textUpdate(locationEmoji);
        when(locationService.createLocation(user, locationName, locationEmoji))
                .thenReturn(location);
        when(plantService.createPlantWithWateringSchedule(
                user, speciesId, plantName, intervalDays, nextDueAt, locationId))
                .thenReturn(plant);
        doThrow(new TelegramApiException("API error"))
                .when(client).execute(any(SendMessage.class));

        handler.handle(user, update, client);

        // Бизнес-логика прошла успешно — изменения сохранены
        verify(locationService).createLocation(user, locationName, locationEmoji);
        verify(plantService).createPlantWithWateringSchedule(
                user, speciesId, plantName, intervalDays, nextDueAt, locationId);
        verify(userService).setStateData(user, "plant_id", plantId.toString());
        verify(userService).updateState(user, ConversationState.AWAITING_PLANT_MISTING_SETUP);
    }

    @Test
    @DisplayName("Ошибка при ответе на callback — handler продолжает работу")
    void shouldHandleAnswerCallbackExceptionAndContinue() throws Exception {
        Update update = callbackUpdate("PLANT_LOCATION_EMOJI:" + locationEmoji);
        doThrow(new RuntimeException("callback error"))
                .when(client).execute(any(AnswerCallbackQuery.class));
        when(locationService.createLocation(user, locationName, locationEmoji))
                .thenReturn(location);
        when(plantService.createPlantWithWateringSchedule(
                user, speciesId, plantName, intervalDays, nextDueAt, locationId))
                .thenReturn(plant);

        handler.handle(user, update, client);

        verify(locationService).createLocation(user, locationName, locationEmoji);
        verify(plantService).createPlantWithWateringSchedule(
                user, speciesId, plantName, intervalDays, nextDueAt, locationId);
        verify(userService).updateState(user, ConversationState.AWAITING_PLANT_MISTING_SETUP);
    }

    // ==================== Helpers ====================

    /**
     * Update с текстовым сообщением. Chat не создаётся — handler читает chatId из User.
     */
    private Update textUpdate(String text) {
        Update update = new Update();
        Message message = new Message();
        message.setText(text);
        update.setMessage(message);
        return update;
    }

    /**
     * Update с callback query. Message внутри callback тоже без Chat —
     * handler использует только callbackQuery.getId() и getData().
     */
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
            // Идём по иерархии классов вверх (Plant/Location могут наследовать BaseEntity)
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