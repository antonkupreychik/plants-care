package com.plantcare.bot.state.impl;

import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.Species;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.service.PlantService;
import com.plantcare.bot.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("State Handler Tests - Plant Creation Dialog")
class PlantCreationStateHandlerTests {

    @Mock
    private UserService userService;

    @Mock
    private PlantService plantService;

    @Mock
    private TelegramClient telegramClient;

    private User testUser;
    private Species testSpecies;
    private Plant testPlant;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .telegramChatId(123L)
                .username("testuser")
                .timezone("UTC")
                .stateData(new HashMap<>())
                .build();

        testSpecies = Species.builder()
                .name("Монстера")
                .wateringDays(7)
                .popularity(100)
                .build();

        testPlant = Plant.builder()
                .user(testUser)
                .name("Test Plant")
                .build();
    }

    // ==================== AwaitingPlantSpeciesChoiceStateHandler Tests ====================

    @DisplayName("Should handle SPECIES:ID callback and show preview")
    @Test
    void testSpeciesChoiceHandler_SelectSpecies() throws TelegramApiException {
        AwaitingPlantSpeciesChoiceStateHandler handler = new AwaitingPlantSpeciesChoiceStateHandler(
                userService, plantService);

        Update update = createCallbackUpdate("SPECIES:1");
        when(plantService.getSpeciesById(1L)).thenReturn(Optional.of(testSpecies));

        handler.handle(testUser, update, telegramClient);

        verify(userService).setStateData(testUser, "species_id", "1");
        verify(userService).setStateData(testUser, "interval_days", "7");
        verify(telegramClient).execute(any(SendMessage.class));
    }

    @DisplayName("Should handle SPECIES:CUSTOM and transition to interval input")
    @Test
    void testSpeciesChoiceHandler_SelectCustom() throws TelegramApiException {
        AwaitingPlantSpeciesChoiceStateHandler handler = new AwaitingPlantSpeciesChoiceStateHandler(
                userService, plantService);

        Update update = createCallbackUpdate("SPECIES:CUSTOM");

        handler.handle(testUser, update, telegramClient);

        verify(userService).setStateData(testUser, "species_id", "null");
        verify(userService).updateState(testUser, ConversationState.AWAITING_PLANT_WATERING_INTERVAL);
        verify(telegramClient).execute(any(SendMessage.class));
    }

    @DisplayName("Should handle SPECIES:SEARCH and transition to search state")
    @Test
    void testSpeciesChoiceHandler_SelectSearch() throws TelegramApiException {
        AwaitingPlantSpeciesChoiceStateHandler handler = new AwaitingPlantSpeciesChoiceStateHandler(
                userService, plantService);

        Update update = createCallbackUpdate("SPECIES:SEARCH");

        handler.handle(testUser, update, telegramClient);

        verify(userService).updateState(testUser, ConversationState.AWAITING_PLANT_SPECIES_SEARCH);
        verify(telegramClient).execute(any(SendMessage.class));
    }

    @DisplayName("Should handle invalid SPECIES:ID gracefully (NumberFormatException)")
    @Test
    void testSpeciesChoiceHandler_InvalidSpeciesId() {
        AwaitingPlantSpeciesChoiceStateHandler handler = new AwaitingPlantSpeciesChoiceStateHandler(
                userService, plantService);

        Update update = createCallbackUpdate("SPECIES:INVALID");

        // Long.parseLong("INVALID") выбросит NumberFormatException,
        // но handler ловит все исключения — не должен упасть
        assertDoesNotThrow(() -> handler.handle(testUser, update, telegramClient));

        // Не должны были ни сохранить state, ни перейти в другое состояние
        verify(userService, never()).setStateData(eq(testUser), eq("species_id"), anyString());
    }

    // ==================== AwaitingPlantNameStateHandler Tests ====================

    @DisplayName("Should accept valid plant name (1-100 chars)")
    @Test
    void testNameHandler_ValidName() throws TelegramApiException {
        AwaitingPlantNameStateHandler handler = new AwaitingPlantNameStateHandler(userService);

        Update update = createTextMessageUpdate("Монстера в гостиной");

        handler.handle(testUser, update, telegramClient);

        verify(userService).setStateData(testUser, "plant_name", "Монстера в гостиной");
        verify(userService).updateState(testUser, ConversationState.AWAITING_PLANT_LAST_WATERED);
        verify(telegramClient).execute(any(SendMessage.class));
    }

    @DisplayName("Should reject empty plant name")
    @Test
    void testNameHandler_EmptyName() throws TelegramApiException {
        AwaitingPlantNameStateHandler handler = new AwaitingPlantNameStateHandler(userService);

        Update update = createTextMessageUpdate("   ");

        handler.handle(testUser, update, telegramClient);

        // Имя не должно быть сохранено
        verify(userService, never()).setStateData(eq(testUser), eq("plant_name"), anyString());
        verify(userService, never()).updateState(any(), any());

        // Должно быть отправлено сообщение с ошибкой валидации
        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("от 1 до 100");
    }

    @DisplayName("Should reject too long plant name (>100 chars)")
    @Test
    void testNameHandler_TooLongName() throws TelegramApiException {
        AwaitingPlantNameStateHandler handler = new AwaitingPlantNameStateHandler(userService);

        String tooLong = "a".repeat(101);
        Update update = createTextMessageUpdate(tooLong);

        handler.handle(testUser, update, telegramClient);

        verify(userService, never()).setStateData(eq(testUser), eq("plant_name"), anyString());
        verify(telegramClient).execute(any(SendMessage.class));
    }

    // ==================== AwaitingPlantWateringIntervalStateHandler Tests ====================

    @DisplayName("Should accept valid watering interval (1-365)")
    @Test
    void testIntervalHandler_ValidInterval() throws TelegramApiException {
        AwaitingPlantWateringIntervalStateHandler handler = new AwaitingPlantWateringIntervalStateHandler(
                userService, plantService);

        Update update = createTextMessageUpdate("7");

        handler.handle(testUser, update, telegramClient);

        verify(userService).setStateData(testUser, "interval_days", "7");
        verify(userService).updateState(testUser, ConversationState.AWAITING_PLANT_NAME);
        verify(telegramClient).execute(any(SendMessage.class));
    }

    @DisplayName("Should reject interval > 365")
    @Test
    void testIntervalHandler_OutOfRangeInterval() throws TelegramApiException {
        AwaitingPlantWateringIntervalStateHandler handler = new AwaitingPlantWateringIntervalStateHandler(
                userService, plantService);

        Update update = createTextMessageUpdate("366");

        handler.handle(testUser, update, telegramClient);

        // Интервал не должен быть сохранён
        verify(userService, never()).setStateData(eq(testUser), eq("interval_days"), anyString());

        // Должно быть отправлено сообщение об ошибке
        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("от 1 до 365");
    }

    @DisplayName("Should reject interval < 1")
    @Test
    void testIntervalHandler_ZeroInterval() throws TelegramApiException {
        AwaitingPlantWateringIntervalStateHandler handler = new AwaitingPlantWateringIntervalStateHandler(
                userService, plantService);

        Update update = createTextMessageUpdate("0");

        handler.handle(testUser, update, telegramClient);

        verify(userService, never()).setStateData(eq(testUser), eq("interval_days"), anyString());

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("от 1 до 365");
    }

    @DisplayName("Should reject non-numeric interval")
    @Test
    void testIntervalHandler_NonNumericInterval() throws TelegramApiException {
        AwaitingPlantWateringIntervalStateHandler handler = new AwaitingPlantWateringIntervalStateHandler(
                userService, plantService);

        Update update = createTextMessageUpdate("abc");

        handler.handle(testUser, update, telegramClient);

        verify(userService, never()).setStateData(eq(testUser), eq("interval_days"), anyString());
        verify(telegramClient).execute(any(SendMessage.class));
    }

    // ==================== AwaitingPlantLastWateredStateHandler Tests ====================

    @DisplayName("Should handle TODAY callback correctly")
    @Test
    void testLastWateredHandler_Today() throws TelegramApiException {
        testUser.getStateData().put("species_id", "1");
        testUser.getStateData().put("interval_days", "7");
        testUser.getStateData().put("plant_name", "Test Plant");

        AwaitingPlantLastWateredStateHandler handler = new AwaitingPlantLastWateredStateHandler(
                userService, plantService);

        Update update = createCallbackUpdate("LAST_WATERED:TODAY");

        when(plantService.createPlantWithWateringSchedule(any(), any(), any(), anyInt(), any()))
                .thenReturn(testPlant);

        handler.handle(testUser, update, telegramClient);

        verify(plantService).createPlantWithWateringSchedule(
                eq(testUser),
                eq(1L),
                eq("Test Plant"),
                eq(7),
                argThat(date -> date.isAfter(java.time.LocalDateTime.now()))
        );
        verify(userService).resetToIdle(testUser);
        verify(telegramClient, atLeastOnce()).execute(any(SendMessage.class));
    }

    @DisplayName("Should handle YESTERDAY callback correctly")
    @Test
    void testLastWateredHandler_Yesterday() throws TelegramApiException {
        testUser.getStateData().put("species_id", "1");
        testUser.getStateData().put("interval_days", "7");
        testUser.getStateData().put("plant_name", "Test Plant");

        AwaitingPlantLastWateredStateHandler handler = new AwaitingPlantLastWateredStateHandler(
                userService, plantService);

        Update update = createCallbackUpdate("LAST_WATERED:YESTERDAY");

        when(plantService.createPlantWithWateringSchedule(any(), any(), any(), anyInt(), any()))
                .thenReturn(testPlant);

        handler.handle(testUser, update, telegramClient);

        verify(plantService).createPlantWithWateringSchedule(
                eq(testUser),
                eq(1L),
                eq("Test Plant"),
                eq(7),
                any()
        );
        verify(userService).resetToIdle(testUser);
    }

    @DisplayName("Should handle FORGOTTEN callback correctly")
    @Test
    void testLastWateredHandler_Forgotten() throws TelegramApiException {
        testUser.getStateData().put("species_id", "null");
        testUser.getStateData().put("interval_days", "7");
        testUser.getStateData().put("plant_name", "Test Plant");

        AwaitingPlantLastWateredStateHandler handler = new AwaitingPlantLastWateredStateHandler(
                userService, plantService);

        Update update = createCallbackUpdate("LAST_WATERED:FORGOTTEN");

        when(plantService.createPlantWithWateringSchedule(any(), any(), any(), anyInt(), any()))
                .thenReturn(testPlant);

        handler.handle(testUser, update, telegramClient);

        verify(plantService).createPlantWithWateringSchedule(
                eq(testUser),
                isNull(),
                eq("Test Plant"),
                eq(7),
                argThat(date -> date.isBefore(java.time.LocalDateTime.now().plusSeconds(1)))
        );
        verify(userService).resetToIdle(testUser);
    }

    @DisplayName("Should use default interval (7) if interval_days is null")
    @Test
    void testLastWateredHandler_DefaultInterval() throws TelegramApiException {
        testUser.getStateData().put("species_id", "1");
        testUser.getStateData().put("interval_days", null);
        testUser.getStateData().put("plant_name", "Test Plant");

        when(plantService.getSpeciesById(1L)).thenReturn(Optional.of(testSpecies));

        AwaitingPlantLastWateredStateHandler handler = new AwaitingPlantLastWateredStateHandler(
                userService, plantService);

        Update update = createCallbackUpdate("LAST_WATERED:TODAY");

        when(plantService.createPlantWithWateringSchedule(any(), any(), any(), anyInt(), any()))
                .thenReturn(testPlant);

        handler.handle(testUser, update, telegramClient);

        // Должен использовать дефолтный интервал 7
        verify(plantService).createPlantWithWateringSchedule(
                eq(testUser),
                eq(1L),
                eq("Test Plant"),
                eq(7),
                any()
        );
        verify(userService).resetToIdle(testUser);
    }

    // ==================== AwaitingPlantSpeciesSearchStateHandler Tests ====================

    @DisplayName("Should search species by query")
    @Test
    void testSearchHandler_FindSpecies() throws TelegramApiException {
        AwaitingPlantSpeciesSearchStateHandler handler = new AwaitingPlantSpeciesSearchStateHandler(
                userService, plantService);

        Update update = createTextMessageUpdate("монстера");
        List<Species> searchResults = List.of(testSpecies);
        when(plantService.searchSpecies("монстера", 10)).thenReturn(searchResults);

        handler.handle(testUser, update, telegramClient);

        verify(plantService).searchSpecies("монстера", 10);
        verify(telegramClient).execute(any(SendMessage.class));
    }

    @DisplayName("Should handle empty search results with 'Ничего не найдено'")
    @Test
    void testSearchHandler_NoResults() throws TelegramApiException {
        AwaitingPlantSpeciesSearchStateHandler handler = new AwaitingPlantSpeciesSearchStateHandler(
                userService, plantService);

        Update update = createTextMessageUpdate("неизвестное растение xyz");
        when(plantService.searchSpecies("неизвестное растение xyz", 10)).thenReturn(List.of());

        handler.handle(testUser, update, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Ничего не найдено");
    }

    @DisplayName("Should handle BACK_TO_SPECIES from search")
    @Test
    void testSearchHandler_BackToSpecies() throws TelegramApiException {
        AwaitingPlantSpeciesSearchStateHandler handler = new AwaitingPlantSpeciesSearchStateHandler(
                userService, plantService);

        Update update = createCallbackUpdate("BACK_TO_SPECIES");

        handler.handle(testUser, update, telegramClient);

        verify(userService).updateState(testUser, ConversationState.AWAITING_PLANT_SPECIES_CHOICE);
        verify(telegramClient).execute(any(SendMessage.class));
    }

    @DisplayName("Should handle CONFIRM_TEMPLATE from search results")
    @Test
    void testSearchHandler_ConfirmFromSearch() throws TelegramApiException {
        AwaitingPlantSpeciesSearchStateHandler handler = new AwaitingPlantSpeciesSearchStateHandler(
                userService, plantService);

        Update update = createCallbackUpdate("CONFIRM_TEMPLATE");

        handler.handle(testUser, update, telegramClient);

        verify(userService).updateState(testUser, ConversationState.AWAITING_PLANT_NAME);
        verify(telegramClient).execute(any(SendMessage.class));
    }

    // ==================== Helper Methods ====================

    private Update createCallbackUpdate(String callbackData) {
        Update update = new Update();
        update.setUpdateId(1);

        org.telegram.telegrambots.meta.api.objects.CallbackQuery callbackQuery =
                new org.telegram.telegrambots.meta.api.objects.CallbackQuery();
        callbackQuery.setId("callback_123");
        callbackQuery.setData(callbackData);
        callbackQuery.setFrom(createTelegramUser());

        Chat chat = new org.telegram.telegrambots.meta.api.objects.chat.ChatFullInfo().builder()
                .id(123L)
                .type("type")
                .build();

        Message message = new Message();
        message.setChat(chat);
        message.setMessageId(1);
        callbackQuery.setMessage(message);

        update.setCallbackQuery(callbackQuery);
        return update;
    }

    private Update createTextMessageUpdate(String text) {
        Update update = new Update();
        update.setUpdateId(1);

        Message message = new Message();
        message.setMessageId(1);
        message.setText(text);
        message.setFrom(createTelegramUser());

        Chat chat = new org.telegram.telegrambots.meta.api.objects.chat.ChatFullInfo().builder()
                .id(123L)
                .type("type")
                .build();
        message.setChat(chat);

        update.setMessage(message);
        return update;
    }

    private org.telegram.telegrambots.meta.api.objects.User createTelegramUser() {
        return new org.telegram.telegrambots.meta.api.objects.User(123L, "hello", false);
    }
}