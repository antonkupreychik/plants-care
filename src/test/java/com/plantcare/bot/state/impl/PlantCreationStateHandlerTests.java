package com.plantcare.bot.state.impl;

import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.Species;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.ConversationState;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.repository.PlantRepository;
import com.plantcare.core.service.*;
import com.plantcare.bot.service.*;
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
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
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
    private LocationService locationService;

    @Mock
    private TelegramClient telegramClient;

    @Mock
    private PlantTemplateService plantTemplateService;

    @Mock
    private MainMenuService mainMenuService;

    private User testUser;
    private Species testSpecies;
    private Plant testPlant;
    private Location defaultLocation;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .telegramChatId(123L)
                .username("testuser")
                .timezone("UTC")
                .stateData(new HashMap<>())
                .build();

        ReflectionTestUtils.setField(testUser, "id", 123L);

        testSpecies = Species.builder()
                .name("Монстера")
                .wateringDays(7)
                .popularity(100)
                .build();

        ReflectionTestUtils.setField(testSpecies, "id", 1L);

        defaultLocation = Location.builder()
                .user(testUser)
                .name("Мои растения")
                .emoji("🪴")
                .defaultLocation(true)
                .build();

        ReflectionTestUtils.setField(defaultLocation, "id", 10L);

        testPlant = Plant.builder()
                .user(testUser)
                .name("Test Plant")
                .location(defaultLocation)
                .build();

        ReflectionTestUtils.setField(testPlant, "id", 42L);

        when(locationService.getUserLocations(testUser.getId()))
                .thenReturn(List.of(defaultLocation));
    }

    @DisplayName("Should handle SPECIES:ID callback and show preview")
    @Test
    void testSpeciesChoiceHandler_SelectSpecies() throws TelegramApiException {
        AwaitingPlantSpeciesChoiceStateHandler handler =
                new AwaitingPlantSpeciesChoiceStateHandler(userService, plantService, plantTemplateService);

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
        AwaitingPlantSpeciesChoiceStateHandler handler =
                new AwaitingPlantSpeciesChoiceStateHandler(userService, plantService, plantTemplateService);

        Update update = createCallbackUpdate("SPECIES:CUSTOM");

        handler.handle(testUser, update, telegramClient);

        verify(userService).setStateData(testUser, "species_id", "null");
        verify(userService).updateState(testUser, ConversationState.AWAITING_PLANT_WATERING_INTERVAL);
        verify(telegramClient).execute(any(SendMessage.class));
    }

    @DisplayName("Should handle SPECIES:SEARCH and transition to search state")
    @Test
    void testSpeciesChoiceHandler_SelectSearch() throws TelegramApiException {
        AwaitingPlantSpeciesChoiceStateHandler handler =
                new AwaitingPlantSpeciesChoiceStateHandler(userService, plantService, plantTemplateService);

        Update update = createCallbackUpdate("SPECIES:SEARCH");

        handler.handle(testUser, update, telegramClient);

        verify(userService).updateState(testUser, ConversationState.AWAITING_PLANT_SPECIES_SEARCH);
        verify(telegramClient).execute(any(SendMessage.class));
    }

    @DisplayName("Should handle invalid SPECIES:ID gracefully")
    @Test
    void testSpeciesChoiceHandler_InvalidSpeciesId() {
        AwaitingPlantSpeciesChoiceStateHandler handler =
                new AwaitingPlantSpeciesChoiceStateHandler(userService, plantService, plantTemplateService);

        Update update = createCallbackUpdate("SPECIES:INVALID");

        assertDoesNotThrow(() -> handler.handle(testUser, update, telegramClient));

        verify(userService, never()).setStateData(eq(testUser), eq("species_id"), anyString());
    }

    @DisplayName("Should accept valid plant name")
    @Test
    void testNameHandler_ValidName() throws TelegramApiException {
        AwaitingPlantNameStateHandler handler = new AwaitingPlantNameStateHandler(userService);

        Update update = createTextMessageUpdate("Монстера в гостиной");

        handler.handle(testUser, update, telegramClient);

        verify(userService).setStateData(testUser, "plant_name", "Монстера в гостиной");
        // issue #117: после имени флоу идёт через шаг «когда завёл?», а не сразу
        // на последний полив.
        verify(userService).updateState(testUser, ConversationState.AWAITING_PLANT_ACQUIRED_CHOICE);
        verify(telegramClient).execute(any(SendMessage.class));
    }

    @DisplayName("Should reject empty plant name")
    @Test
    void testNameHandler_EmptyName() throws TelegramApiException {
        AwaitingPlantNameStateHandler handler = new AwaitingPlantNameStateHandler(userService);

        Update update = createTextMessageUpdate("   ");

        handler.handle(testUser, update, telegramClient);

        verify(userService, never()).setStateData(eq(testUser), eq("plant_name"), anyString());
        verify(userService, never()).updateState(any(), any());

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("от 1 до 100");
    }

    @DisplayName("Should reject too long plant name")
    @Test
    void testNameHandler_TooLongName() throws TelegramApiException {
        AwaitingPlantNameStateHandler handler = new AwaitingPlantNameStateHandler(userService);

        String tooLong = "a".repeat(101);
        Update update = createTextMessageUpdate(tooLong);

        handler.handle(testUser, update, telegramClient);

        verify(userService, never()).setStateData(eq(testUser), eq("plant_name"), anyString());
        verify(telegramClient).execute(any(SendMessage.class));
    }

    @DisplayName("Should accept valid watering interval")
    @Test
    void testIntervalHandler_ValidInterval() throws TelegramApiException {
        AwaitingPlantWateringIntervalStateHandler handler =
                new AwaitingPlantWateringIntervalStateHandler(userService, plantService);

        Update update = createTextMessageUpdate("7");

        handler.handle(testUser, update, telegramClient);

        verify(userService).setStateData(testUser, "interval_days", "7");
        verify(userService).updateState(testUser, ConversationState.AWAITING_PLANT_NAME);
        verify(telegramClient).execute(any(SendMessage.class));
    }

    @DisplayName("Should reject interval > 365")
    @Test
    void testIntervalHandler_OutOfRangeInterval() throws TelegramApiException {
        AwaitingPlantWateringIntervalStateHandler handler =
                new AwaitingPlantWateringIntervalStateHandler(userService, plantService);

        Update update = createTextMessageUpdate("366");

        handler.handle(testUser, update, telegramClient);

        verify(userService, never()).setStateData(eq(testUser), eq("interval_days"), anyString());

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("от 1 до 365");
    }

    @DisplayName("Should reject interval < 1")
    @Test
    void testIntervalHandler_ZeroInterval() throws TelegramApiException {
        AwaitingPlantWateringIntervalStateHandler handler =
                new AwaitingPlantWateringIntervalStateHandler(userService, plantService);

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
        AwaitingPlantWateringIntervalStateHandler handler =
                new AwaitingPlantWateringIntervalStateHandler(userService, plantService);

        Update update = createTextMessageUpdate("abc");

        handler.handle(testUser, update, telegramClient);

        verify(userService, never()).setStateData(eq(testUser), eq("interval_days"), anyString());
        verify(telegramClient).execute(any(SendMessage.class));
    }

    @DisplayName("LAST_WATERED:TODAY should save watering data and ask location")
    @Test
    void testLastWateredHandler_Today() throws TelegramApiException {
        testUser.getStateData().put("species_id", "1");
        testUser.getStateData().put("interval_days", "7");
        testUser.getStateData().put("plant_name", "Test Plant");

        AwaitingPlantLastWateredStateHandler handler =
                new AwaitingPlantLastWateredStateHandler(userService, plantService, locationService);

        Update update = createCallbackUpdate("LAST_WATERED:TODAY");

        handler.handle(testUser, update, telegramClient);

        verify(userService).setStateData(testUser, "interval_days", "7");
        verify(userService).setStateData(eq(testUser), eq("next_due_at"), anyString());
        verify(userService).updateState(testUser, ConversationState.AWAITING_PLANT_ROOM);

        verify(plantService, never()).createPlantWithWateringSchedule(
                any(User.class),
                any(),
                anyString(),
                any(Integer.class),
                any()
        );

        verify(userService, never()).resetToIdle(testUser);
        verify(telegramClient, atLeastOnce()).execute(any(SendMessage.class));
    }

    @DisplayName("LAST_WATERED:YESTERDAY should save watering data and ask location")
    @Test
    void testLastWateredHandler_Yesterday() throws TelegramApiException {
        testUser.getStateData().put("species_id", "1");
        testUser.getStateData().put("interval_days", "7");
        testUser.getStateData().put("plant_name", "Test Plant");

        AwaitingPlantLastWateredStateHandler handler =
                new AwaitingPlantLastWateredStateHandler(userService, plantService, locationService);

        Update update = createCallbackUpdate("LAST_WATERED:YESTERDAY");

        handler.handle(testUser, update, telegramClient);

        verify(userService).setStateData(testUser, "interval_days", "7");
        verify(userService).setStateData(eq(testUser), eq("next_due_at"), anyString());
        verify(userService).updateState(testUser, ConversationState.AWAITING_PLANT_ROOM);

        verify(plantService, never()).createPlantWithWateringSchedule(
                any(User.class),
                any(),
                anyString(),
                any(Integer.class),
                any()
        );

        verify(userService, never()).resetToIdle(testUser);
    }

    @DisplayName("LAST_WATERED:FORGOTTEN should save watering data and ask location")
    @Test
    void testLastWateredHandler_Forgotten() throws TelegramApiException {
        testUser.getStateData().put("species_id", "null");
        testUser.getStateData().put("interval_days", "7");
        testUser.getStateData().put("plant_name", "Test Plant");

        AwaitingPlantLastWateredStateHandler handler =
                new AwaitingPlantLastWateredStateHandler(userService, plantService, locationService);

        Update update = createCallbackUpdate("LAST_WATERED:FORGOTTEN");

        handler.handle(testUser, update, telegramClient);

        verify(userService).setStateData(testUser, "interval_days", "7");
        verify(userService).setStateData(eq(testUser), eq("next_due_at"), anyString());
        verify(userService).updateState(testUser, ConversationState.AWAITING_PLANT_ROOM);

        verify(plantService, never()).createPlantWithWateringSchedule(
                any(User.class),
                any(),
                anyString(),
                any(Integer.class),
                any()
        );

        verify(userService, never()).resetToIdle(testUser);
    }

    @DisplayName("Should use species default interval if interval_days is null")
    @Test
    void testLastWateredHandler_DefaultInterval() throws TelegramApiException {
        testUser.getStateData().put("species_id", "1");
        testUser.getStateData().put("interval_days", null);
        testUser.getStateData().put("plant_name", "Test Plant");

        when(plantService.getSpeciesById(1L)).thenReturn(Optional.of(testSpecies));

        AwaitingPlantLastWateredStateHandler handler =
                new AwaitingPlantLastWateredStateHandler(userService, plantService, locationService);

        Update update = createCallbackUpdate("LAST_WATERED:TODAY");

        handler.handle(testUser, update, telegramClient);

        verify(userService).setStateData(testUser, "interval_days", "7");
        verify(userService).setStateData(eq(testUser), eq("next_due_at"), anyString());
        verify(userService).updateState(testUser, ConversationState.AWAITING_PLANT_ROOM);

        verify(plantService, never()).createPlantWithWateringSchedule(
                any(User.class),
                any(),
                anyString(),
                any(Integer.class),
                any()
        );

        verify(userService, never()).resetToIdle(testUser);
    }

    @DisplayName("Should search species by query")
    @Test
    void testSearchHandler_FindSpecies() throws TelegramApiException {
        AwaitingPlantSpeciesSearchStateHandler handler =
                new AwaitingPlantSpeciesSearchStateHandler(userService, plantService);

        Update update = createTextMessageUpdate("монстера");
        List<Species> searchResults = List.of(testSpecies);

        when(plantService.searchSpecies("монстера", 10)).thenReturn(searchResults);

        handler.handle(testUser, update, telegramClient);

        verify(plantService).searchSpecies("монстера", 10);
        verify(telegramClient).execute(any(SendMessage.class));
    }

    @DisplayName("Should handle empty search results")
    @Test
    void testSearchHandler_NoResults() throws TelegramApiException {
        AwaitingPlantSpeciesSearchStateHandler handler =
                new AwaitingPlantSpeciesSearchStateHandler(userService, plantService);

        Update update = createTextMessageUpdate("неизвестное растение xyz");

        when(plantService.searchSpecies("неизвестное растение xyz", 10))
                .thenReturn(List.of());

        handler.handle(testUser, update, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Ничего не найдено");
    }

    @DisplayName("Should handle BACK_TO_SPECIES from search")
    @Test
    void testSearchHandler_BackToSpecies() throws TelegramApiException {
        AwaitingPlantSpeciesSearchStateHandler handler =
                new AwaitingPlantSpeciesSearchStateHandler(userService, plantService);

        Update update = createCallbackUpdate("BACK_TO_SPECIES");

        handler.handle(testUser, update, telegramClient);

        verify(userService).updateState(testUser, ConversationState.AWAITING_PLANT_SPECIES_CHOICE);
        verify(telegramClient).execute(any(SendMessage.class));
    }

    @DisplayName("Should handle CONFIRM_TEMPLATE from search results")
    @Test
    void testSearchHandler_ConfirmFromSearch() throws TelegramApiException {
        AwaitingPlantSpeciesSearchStateHandler handler =
                new AwaitingPlantSpeciesSearchStateHandler(userService, plantService);

        Update update = createCallbackUpdate("CONFIRM_TEMPLATE");

        handler.handle(testUser, update, telegramClient);

        verify(userService).updateState(testUser, ConversationState.AWAITING_PLANT_NAME);
        verify(telegramClient).execute(any(SendMessage.class));
    }

    @DisplayName("MISTING:DEFAULT should create schedule and go to fertilizing")
    @Test
    void testMistingHandler_Default() throws TelegramApiException {
        testUser.getStateData().put("plant_id", "42");

        PlantRepository plantRepository = mock(PlantRepository.class);

        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(123L, 42L))
                .thenReturn(Optional.of(testPlant));

        AwaitingPlantMistingSetupStateHandler handler =
                new AwaitingPlantMistingSetupStateHandler(userService, plantService, plantRepository);

        Update update = createCallbackUpdate("MISTING:DEFAULT");

        handler.handle(testUser, update, telegramClient);

        verify(plantService).addCareSchedule(
                eq(testPlant),
                eq(TaskType.MISTING),
                eq(3),
                any()
        );

        verify(userService).updateState(testUser, ConversationState.AWAITING_PLANT_FERTILIZING_SETUP);
        verify(telegramClient, atLeastOnce()).execute(any(SendMessage.class));
    }

    @DisplayName("MISTING:SKIP should not create schedule and go to fertilizing")
    @Test
    void testMistingHandler_Skip() throws TelegramApiException {
        testUser.getStateData().put("plant_id", "42");

        PlantRepository plantRepository = mock(PlantRepository.class);

        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(123L, 42L))
                .thenReturn(Optional.of(testPlant));

        AwaitingPlantMistingSetupStateHandler handler =
                new AwaitingPlantMistingSetupStateHandler(userService, plantService, plantRepository);

        Update update = createCallbackUpdate("MISTING:SKIP");

        handler.handle(testUser, update, telegramClient);

        verify(plantService, never()).addCareSchedule(any(), any(), anyInt(), any());
        verify(userService).updateState(testUser, ConversationState.AWAITING_PLANT_FERTILIZING_SETUP);
    }

    @DisplayName("MISTING:CUSTOM should ask for interval")
    @Test
    void testMistingHandler_CustomAsksForInput() throws TelegramApiException {
        testUser.getStateData().put("plant_id", "42");

        PlantRepository plantRepository = mock(PlantRepository.class);

        AwaitingPlantMistingSetupStateHandler handler =
                new AwaitingPlantMistingSetupStateHandler(userService, plantService, plantRepository);

        Update update = createCallbackUpdate("MISTING:CUSTOM");

        handler.handle(testUser, update, telegramClient);

        verify(userService).setStateData(testUser, "misting_awaiting_input", "true");
        verify(userService, never()).updateState(any(), eq(ConversationState.AWAITING_PLANT_FERTILIZING_SETUP));
        verify(telegramClient).execute(any(SendMessage.class));
    }

    @DisplayName("MISTING custom interval should create schedule and go to fertilizing")
    @Test
    void testMistingHandler_CustomIntervalInput() throws TelegramApiException {
        testUser.getStateData().put("plant_id", "42");
        testUser.getStateData().put("misting_awaiting_input", "true");

        PlantRepository plantRepository = mock(PlantRepository.class);

        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(123L, 42L))
                .thenReturn(Optional.of(testPlant));

        AwaitingPlantMistingSetupStateHandler handler =
                new AwaitingPlantMistingSetupStateHandler(userService, plantService, plantRepository);

        Update update = createTextMessageUpdate("5");

        handler.handle(testUser, update, telegramClient);

        verify(userService).removeStateData(testUser, "misting_awaiting_input");

        verify(plantService).addCareSchedule(
                eq(testPlant),
                eq(TaskType.MISTING),
                eq(5),
                any()
        );

        verify(userService).updateState(testUser, ConversationState.AWAITING_PLANT_FERTILIZING_SETUP);
    }

    /*@DisplayName("FERTILIZING:DEFAULT should create schedule and finish")
    @Test
    void testFertilizingHandler_Default() throws TelegramApiException {
        testUser.getStateData().put("plant_id", "42");

        PlantRepository plantRepository = mock(PlantRepository.class);

        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(123L, 42L))
                .thenReturn(Optional.of(testPlant));

        when(plantService.getActiveSchedules(42L))
                .thenReturn(List.of());

        AwaitingPlantFertilizingSetupStateHandler handler =
                new AwaitingPlantFertilizingSetupStateHandler(
                        userService,
                        plantService,
                        plantRepository,
                        mainMenuService
                );

        Update update = createCallbackUpdate("FERTILIZING:DEFAULT");

        handler.handle(testUser, update, telegramClient);

        verify(plantService).addCareSchedule(
                eq(testPlant),
                eq(TaskType.FERTILIZING),
                eq(14),
                any()
        );

        verify(userService).resetToIdle(testUser);
        verify(mainMenuService).sendMainMenu(testUser, telegramClient);
        verify(telegramClient, atLeastOnce()).execute(any(SendMessage.class));
    }*/

    /*@DisplayName("FERTILIZING:SKIP should not create schedule and finish")
    @Test
    void testFertilizingHandler_Skip() throws TelegramApiException {
        testUser.getStateData().put("plant_id", "42");

        PlantRepository plantRepository = mock(PlantRepository.class);

        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(123L, 42L))
                .thenReturn(Optional.of(testPlant));

        when(plantService.getActiveSchedules(42L))
                .thenReturn(List.of());

        AwaitingPlantFertilizingSetupStateHandler handler =
                new AwaitingPlantFertilizingSetupStateHandler(
                        userService,
                        plantService,
                        plantRepository,
                        mainMenuService
                );

        Update update = createCallbackUpdate("FERTILIZING:SKIP");

        handler.handle(testUser, update, telegramClient);

        verify(plantService, never()).addCareSchedule(any(), any(), anyInt(), any());
        verify(userService).resetToIdle(testUser);
        verify(mainMenuService).sendMainMenu(testUser, telegramClient);
    }*/

   /* @DisplayName("FERTILIZING custom interval should create schedule and finish")
    @Test
    void testFertilizingHandler_CustomIntervalInput() throws TelegramApiException {
        testUser.getStateData().put("plant_id", "42");
        testUser.getStateData().put("fertilizing_awaiting_input", "true");

        PlantRepository plantRepository = mock(PlantRepository.class);

        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(123L, 42L))
                .thenReturn(Optional.of(testPlant));

        when(plantService.getActiveSchedules(42L))
                .thenReturn(List.of());

        AwaitingPlantFertilizingSetupStateHandler handler =
                new AwaitingPlantFertilizingSetupStateHandler(
                        userService,
                        plantService,
                        plantRepository,
                        mainMenuService
                );

        Update update = createTextMessageUpdate("21");

        handler.handle(testUser, update, telegramClient);

        verify(userService).removeStateData(testUser, "fertilizing_awaiting_input");

        verify(plantService).addCareSchedule(
                eq(testPlant),
                eq(TaskType.FERTILIZING),
                eq(21),
                any()
        );

        verify(userService).resetToIdle(testUser);
        verify(mainMenuService).sendMainMenu(testUser, telegramClient);
    }*/

    @DisplayName("FERTILIZING invalid input should send error and stay in state")
    @Test
    void testFertilizingHandler_InvalidInput() throws TelegramApiException {
        testUser.getStateData().put("plant_id", "42");
        testUser.getStateData().put("fertilizing_awaiting_input", "true");

        PlantRepository plantRepository = mock(PlantRepository.class);

        AwaitingPlantFertilizingSetupStateHandler handler =
                new AwaitingPlantFertilizingSetupStateHandler(
                        userService,
                        plantService,
                        plantRepository
                );

        Update update = createTextMessageUpdate("abc");

        handler.handle(testUser, update, telegramClient);

        verify(plantService, never()).addCareSchedule(any(), any(), anyInt(), any());
        verify(userService, never()).resetToIdle(any());
        verify(mainMenuService, never()).sendMainMenu(any(), any());
        verify(telegramClient).execute(any(SendMessage.class));
    }

    private Update createCallbackUpdate(String callbackData) {
        Update update = new Update();
        update.setUpdateId(1);

        CallbackQuery callbackQuery = new CallbackQuery();
        callbackQuery.setId("callback_123");
        callbackQuery.setData(callbackData);
        callbackQuery.setFrom(createTelegramUser());

        Chat chat = new org.telegram.telegrambots.meta.api.objects.chat.ChatFullInfo().builder()
                .id(123L)
                .type("private")
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
                .type("private")
                .build();

        message.setChat(chat);
        update.setMessage(message);

        return update;
    }

    private org.telegram.telegrambots.meta.api.objects.User createTelegramUser() {
        return new org.telegram.telegrambots.meta.api.objects.User(123L, "testuser", false);
    }
}