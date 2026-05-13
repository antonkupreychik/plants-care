package com.plantcare.bot.service;

import com.plantcare.bot.domain.Species;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
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
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-тесты для MenuCallbackService")
class MenuCallbackServiceTest {

    @Mock private UserService userService;
    @Mock private PlantService plantService;
    @Mock private LocationMenuService locationMenuService;
    @Mock private LocationService locationService;
    @Mock private MainMenuService mainMenuService;
    @Mock private PlantMenuService plantMenuService;
    @Mock private PlantCardService plantCardService;
    @Mock private TelegramClient telegramClient;
    @Mock private CallbackQuery callbackQuery;
    @Mock private Message message;

    @InjectMocks
    private MenuCallbackService service;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .telegramChatId(100L)
                .timezone("UTC")
                .build();

        lenient().when(callbackQuery.getId()).thenReturn("cb-1");
        lenient().when(callbackQuery.getMessage()).thenReturn(message);
        lenient().when(message.getChatId()).thenReturn(100L);
        lenient().when(message.getMessageId()).thenReturn(42);
    }

    @Test
    @DisplayName("MENU:ADD_PLANT переводит пользователя в AWAITING_PLANT_SPECIES_CHOICE")
    void shouldStartAddPlantFlow() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("MENU:ADD_PLANT");
        when(plantService.getPopularSpecies(6)).thenReturn(List.of());

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(userService).updateState(testUser, ConversationState.AWAITING_PLANT_SPECIES_CHOICE);
    }

    @Test
    @DisplayName("MENU:ADD_PLANT отправляет сообщение с клавиатурой видов")
    void shouldSendSpeciesKeyboardOnAddPlant() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("MENU:ADD_PLANT");
        List<Species> species = List.of(
                Species.builder().name("Монстера").popularity(100).build()
        );
        when(plantService.getPopularSpecies(6)).thenReturn(species);

        service.handleCallback(callbackQuery, telegramClient, testUser);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        SendMessage sent = captor.getValue();
        assertThat(sent.getText()).contains("Давай добавим новое растение");
        assertThat(sent.getReplyMarkup()).isInstanceOf(InlineKeyboardMarkup.class);
    }

    @Test
    @DisplayName("MENU:ALL_PLANTS открывает список «Мои растения» новым сообщением")
    void shouldOpenMyPlantsListForAllPlants() {
        when(callbackQuery.getData()).thenReturn("MENU:ALL_PLANTS");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        // Новое сообщение: messageId=null (мы пришли из главного меню).
        verify(plantMenuService).sendMyPlantsList(testUser, null, telegramClient);
        verify(userService, never()).updateState(any(), any());
    }

    @Test
    @DisplayName("MENU:SETTINGS показывает заглушку")
    void shouldShowStubForSettings() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("MENU:SETTINGS");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());

        assertThat(captor.getValue().getText()).contains("Скоро");
    }

    @Test
    @DisplayName("Неизвестное действие — отправляет ошибку")
    void shouldHandleUnknownAction() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("MENU:UNKNOWN_ACTION");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());

        assertThat(captor.getValue().getText()).contains("❌");
    }

    // ==================== PLANT: callbacks (issue #26) ====================

    @Test
    @DisplayName("PLANT:VIEW:<id> открывает карточку с back=LIST в том же сообщении")
    void shouldOpenPlantCardFromList() {
        when(callbackQuery.getData()).thenReturn("PLANT:VIEW:7");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(plantCardService).showPlantCard(
                testUser, 7L, 42, PlantCardService.BACK_TO_LIST, telegramClient
        );
    }

    @Test
    @DisplayName("PLANT:VIEW:<id>:LOC:<locId> открывает карточку с back в комнату")
    void shouldOpenPlantCardWithLocationBackTarget() {
        when(callbackQuery.getData()).thenReturn("PLANT:VIEW:7:LOC:5");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(plantCardService).showPlantCard(
                testUser, 7L, 42,
                PlantCardService.BACK_TO_LOCATION_PREFIX + "5",
                telegramClient
        );
    }

    @Test
    @DisplayName("PLANT:LIST редактирует то же сообщение в список «Мои растения»")
    void shouldRenderPlantsListInPlace() {
        when(callbackQuery.getData()).thenReturn("PLANT:LIST");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(plantMenuService).sendMyPlantsList(testUser, 42, telegramClient);
    }

    @Test
    @DisplayName("PLANT:CARE:<id>:WATERING отмечает уход и обновляет карточку")
    void shouldMarkCareDoneAndRefreshCard() {
        when(callbackQuery.getData()).thenReturn("PLANT:CARE:7:WATERING");

        com.plantcare.bot.domain.CareSchedule schedule =
                com.plantcare.bot.domain.CareSchedule.builder()
                        .nextDueAt(java.time.LocalDateTime.now().plusDays(7))
                        .build();
        PlantService.MarkCareDoneResult result =
                new PlantService.MarkCareDoneResult(false, schedule, null, java.time.LocalDateTime.now());

        when(plantService.markCareDone(testUser.getId(), 7L,
                com.plantcare.bot.domain.enums.TaskType.WATERING))
                .thenReturn(result);

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(plantService).markCareDone(testUser.getId(), 7L,
                com.plantcare.bot.domain.enums.TaskType.WATERING);
        verify(plantCardService).showPlantCard(
                testUser, 7L, 42, PlantCardService.BACK_TO_LIST, telegramClient
        );
    }

    @Test
    @DisplayName("PLANT:CARE с дубликатом — карточка не перерисовывается, только alert")
    void shouldNotRerenderOnDuplicateCare() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("PLANT:CARE:7:WATERING");

        PlantService.MarkCareDoneResult duplicate =
                new PlantService.MarkCareDoneResult(true, null, null, java.time.LocalDateTime.now());
        when(plantService.markCareDone(any(), any(), any())).thenReturn(duplicate);

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(plantCardService, never()).showPlantCard(any(), any(), any(), any(), any());

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Уже отмечено");
    }

    @Test
    @DisplayName("PLANT:CARE для растения без активного расписания — алёрт, карточка не трогается")
    void shouldHandleMissingScheduleOnCare() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("PLANT:CARE:7:MISTING");
        when(plantService.markCareDone(any(), any(), any())).thenReturn(null);

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(plantCardService, never()).showPlantCard(any(), any(), any(), any(), any());

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Расписание не настроено");
    }

    @Test
    @DisplayName("PLANT:PHOTO:<id> делегирует sendPlantPhoto, не трогает карточку")
    void shouldSendPlantPhoto() {
        when(callbackQuery.getData()).thenReturn("PLANT:PHOTO:7");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(plantCardService).sendPlantPhoto(testUser, 7L, "cb-1", telegramClient);
        verify(plantCardService, never()).showPlantCard(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("PLANT:SETTINGS:<id> открывает экран настроек в том же сообщении")
    void shouldOpenSettingsScreen() {
        when(callbackQuery.getData()).thenReturn("PLANT:SETTINGS:7");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(plantCardService).showSettingsScreen(
                testUser, 7L, 42, PlantCardService.BACK_TO_LIST, telegramClient
        );
    }

    @Test
    @DisplayName("PLANT:SETTINGS с битым ID — алёрт об ошибке, карточка не трогается")
    void shouldRejectMalformedSettingsCallback() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("PLANT:SETTINGS:not-a-number");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(plantCardService, never())
                .showSettingsScreen(any(), any(), any(), any(), any());

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("❌");
    }

    @Test
    @DisplayName("PLANT:MOVE_CONFIRM перемещает и сразу показывает карточку в новой комнате")
    void shouldRefreshCardAfterMoveConfirm() {
        when(callbackQuery.getData()).thenReturn("PLANT:MOVE_CONFIRM:7:5");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(plantService).movePlantToLocation(testUser.getId(), 7L, 5L);
        verify(plantCardService).showPlantCard(
                testUser, 7L, 42,
                PlantCardService.BACK_TO_LOCATION_PREFIX + "5",
                telegramClient
        );
    }
}