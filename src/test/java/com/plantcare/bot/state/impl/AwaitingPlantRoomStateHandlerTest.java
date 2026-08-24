package com.plantcare.bot.state.impl;

import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.ConversationState;
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
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Unit-тесты для AwaitingPlantRoomStateHandler")
class AwaitingPlantRoomStateHandlerTest {

    @Mock
    private UserService userService;

    @Mock
    private PlantService plantService;

    @Mock
    private TelegramClient client;

    private AwaitingPlantRoomStateHandler handler;
    private User user;
    private Plant savedPlant;

    private final Long chatId = 555L;
    private final Long plantId = 99L;

    @BeforeEach
    void setUp() {
        handler = new AwaitingPlantRoomStateHandler(userService, plantService);

        Map<String, Object> stateData = new HashMap<>();
        stateData.put("species_id", "3");
        stateData.put("interval_days", "7");
        stateData.put("plant_name", "Фикус");
        stateData.put("next_due_at", "2026-09-01T10:00:00");
        stateData.put("acquired_at", "");

        user = User.builder()
                .telegramChatId(chatId)
                .stateData(stateData)
                .build();

        savedPlant = Plant.builder().name("Фикус").build();
        ReflectionTestUtils.setField(savedPlant, "id", plantId);
    }

    @Test
    @DisplayName("Поддерживает состояние AWAITING_PLANT_ROOM")
    void shouldSupportState() {
        assertThat(handler.getSupportedState())
                .isEqualTo(ConversationState.AWAITING_PLANT_ROOM);
    }

    @Test
    @DisplayName("Update без callback-запроса игнорируется")
    void shouldIgnoreUpdateWithoutCallback() {
        Update update = new Update();

        handler.handle(user, update, client);

        verifyNoInteractions(plantService, userService, client);
    }

    @Test
    @DisplayName("ADD_PLANT_LOCATION_CREATE переводит в создание новой комнаты")
    void shouldSwitchToLocationCreation() throws TelegramApiException {
        Update update = callbackUpdate("ADD_PLANT_LOCATION_CREATE");

        handler.handle(user, update, client);

        verify(userService).updateState(user, ConversationState.AWAITING_PLANT_LOCATION_NAME);
        verify(client).execute(any(AnswerCallbackQuery.class));

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Как назовём комнату?");
    }

    @Test
    @DisplayName("Callback с неизвестным префиксом игнорируется без ответа")
    void shouldIgnoreUnrelatedCallback() throws TelegramApiException {
        Update update = callbackUpdate("SOMETHING_ELSE");

        handler.handle(user, update, client);

        verify(client, never()).execute(any(SendMessage.class));
        verifyNoInteractions(plantService);
    }

    @Test
    @DisplayName("ADD_PLANT_LOCATION:SKIP создаёт растение без локации и спрашивает про опрыскивание")
    void shouldCreatePlantWithoutLocationAndAskMisting() throws TelegramApiException {
        Update update = callbackUpdate("ADD_PLANT_LOCATION:SKIP");

        when(plantService.createPlantWithWateringSchedule(
                eq(user), eq(3L), eq("Фикус"), eq(7), any(LocalDateTime.class), isNull()))
                .thenReturn(savedPlant);

        handler.handle(user, update, client);

        verify(plantService).createPlantWithWateringSchedule(
                eq(user), eq(3L), eq("Фикус"), eq(7), any(LocalDateTime.class), isNull());
        verify(userService).setStateData(user, "plant_id", plantId.toString());
        verify(userService).updateState(user, ConversationState.AWAITING_PLANT_MISTING_SETUP);
        verify(client).execute(any(AnswerCallbackQuery.class));

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Нужно ли опрыскивать").contains("Фикус");
    }

    @Test
    @DisplayName("ADD_PLANT_LOCATION:<id> создаёт растение в выбранной локации")
    void shouldCreatePlantInChosenLocation() throws TelegramApiException {
        Update update = callbackUpdate("ADD_PLANT_LOCATION:5");

        when(plantService.createPlantWithWateringSchedule(
                eq(user), eq(3L), eq("Фикус"), eq(7), any(LocalDateTime.class), eq(5L)))
                .thenReturn(savedPlant);

        handler.handle(user, update, client);

        verify(plantService).createPlantWithWateringSchedule(
                eq(user), eq(3L), eq("Фикус"), eq(7), any(LocalDateTime.class), eq(5L));
        verify(userService).setStateData(user, "plant_id", plantId.toString());
    }

    @Test
    @DisplayName("Заданная acquired_at использует 7-аргументный overload")
    void shouldUseAcquiredAtOverloadWhenPresent() throws TelegramApiException {
        user.getStateData().put("acquired_at", "2024-05-01");
        Update update = callbackUpdate("ADD_PLANT_LOCATION:SKIP");

        when(plantService.createPlantWithWateringSchedule(
                eq(user), eq(3L), eq("Фикус"), eq(7), any(LocalDateTime.class), isNull(), eq(LocalDate.of(2024, 5, 1))))
                .thenReturn(savedPlant);

        handler.handle(user, update, client);

        verify(plantService).createPlantWithWateringSchedule(
                eq(user), eq(3L), eq("Фикус"), eq(7), any(LocalDateTime.class), isNull(), eq(LocalDate.of(2024, 5, 1)));
    }

    @Test
    @DisplayName("species_id отсутствует (custom без шаблона) — speciesId остаётся null")
    void shouldHandleNullSpeciesId() throws TelegramApiException {
        user.getStateData().put("species_id", "null");
        Update update = callbackUpdate("ADD_PLANT_LOCATION:SKIP");

        when(plantService.createPlantWithWateringSchedule(
                eq(user), isNull(), eq("Фикус"), eq(7), any(LocalDateTime.class), isNull()))
                .thenReturn(savedPlant);

        handler.handle(user, update, client);

        verify(plantService).createPlantWithWateringSchedule(
                eq(user), isNull(), eq("Фикус"), eq(7), any(LocalDateTime.class), isNull());
    }

    @Test
    @DisplayName("Ошибка при создании растения отправляет текст ошибки и сбрасывает состояние")
    void shouldSendErrorAndResetWhenCreationFails() throws TelegramApiException {
        Update update = callbackUpdate("ADD_PLANT_LOCATION:SKIP");

        when(plantService.createPlantWithWateringSchedule(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("db down"));

        handler.handle(user, update, client);

        verify(userService).resetToIdle(user);
        verify(client).execute(any(AnswerCallbackQuery.class));

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Ошибка при сохранении растения");
    }

    @Test
    @DisplayName("Некорректный locationId (не число) обрабатывается как ошибка")
    void shouldHandleInvalidLocationIdAsError() throws TelegramApiException {
        Update update = callbackUpdate("ADD_PLANT_LOCATION:not-a-number");

        handler.handle(user, update, client);

        verify(userService).resetToIdle(user);
        verifyNoInteractions(plantService);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Ошибка при сохранении растения");
    }

    @Test
    @DisplayName("TelegramApiException при отправке сообщения о новой комнате не валит хендлер")
    void shouldSwallowTelegramApiExceptionOnCreateLocationPrompt() throws TelegramApiException {
        doThrow(new TelegramApiException("boom")).when(client).execute(any(SendMessage.class));
        Update update = callbackUpdate("ADD_PLANT_LOCATION_CREATE");

        handler.handle(user, update, client);

        verify(userService).updateState(user, ConversationState.AWAITING_PLANT_LOCATION_NAME);
        verify(client).execute(any(AnswerCallbackQuery.class));
    }

    @Test
    @DisplayName("Ошибка ответа на callback не мешает продолжить обработку")
    void shouldContinueWhenAnswerCallbackFails() throws TelegramApiException {
        doThrow(new RuntimeException("callback failure")).when(client).execute(any(AnswerCallbackQuery.class));
        Update update = callbackUpdate("ADD_PLANT_LOCATION:SKIP");

        when(plantService.createPlantWithWateringSchedule(any(), any(), any(), any(), any(), any()))
                .thenReturn(savedPlant);

        handler.handle(user, update, client);

        verify(userService).updateState(user, ConversationState.AWAITING_PLANT_MISTING_SETUP);
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
