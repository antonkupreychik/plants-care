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
import static org.mockito.Mockito.*;import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.Collection;
import java.util.List;
import java.util.Map;

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
    @Mock private CalendarMenuService calendarMenuService;
    @Mock private PlantTemplateService plantTemplateService;
    @Mock private NotificationCallbackService notificationCallbackService;
    @Mock private PlantEventService plantEventService;
    @Mock private com.plantcare.bot.seasonal.service.SeasonalMenuService seasonalMenuService;
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
                .featureFlags(Map.of("calendar", true))
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
    @DisplayName("MENU:CALENDAR открывает календарь новым сообщением")
    void shouldOpenCalendarFromMenu() {
        when(callbackQuery.getData()).thenReturn("MENU:CALENDAR");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(calendarMenuService).sendCalendar(testUser, telegramClient);
        verify(userService, never()).updateState(any(), any());
    }

    @Test
    @DisplayName("cal:week:1 редактирует то же сообщение под следующую неделю")
    void shouldEditCalendarOnWeekNavigation() {
        when(callbackQuery.getData()).thenReturn("cal:week:1");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(calendarMenuService).sendCalendar(testUser, 1, 42, telegramClient);
    }

    @Test
    @DisplayName("cal:week:-2 — отрицательный offset для просмотра прошлых недель")
    void shouldHandleNegativeWeekOffset() {
        when(callbackQuery.getData()).thenReturn("cal:week:-2");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(calendarMenuService).sendCalendar(testUser, -2, 42, telegramClient);
    }

    @Test
    @DisplayName("cal:week:abc — битый offset, алёрт об ошибке")
    void shouldRejectMalformedCalendarOffset() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("cal:week:abc");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(calendarMenuService, never()).sendCalendar(any(), org.mockito.ArgumentMatchers.anyInt(), any(), any());
        ArgumentCaptor<AnswerCallbackQuery> cap = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(cap.capture());
        assertThat(cap.getValue().getText()).contains("❌");
    }

    @Test
    @DisplayName("SETTINGS показывает меню настроек с кнопкой изменения региона")
    void shouldShowSettingsMenu() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("MENU:SETTINGS");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        ArgumentCaptor<SendMessage> messageCaptor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(messageCaptor.capture());

        SendMessage message = messageCaptor.getValue();

        assertThat(message).isNotNull();
        assertThat(message.getChatId()).isEqualTo(testUser.getTelegramChatId().toString());
        assertThat(message.getText()).contains("⚙️ Настройки");
        assertThat(message.getText()).contains("Текущий часовой пояс");
        assertThat(message.getText()).contains(testUser.getTimezone());

        assertThat(message.getReplyMarkup()).isInstanceOf(InlineKeyboardMarkup.class);

        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) message.getReplyMarkup();

        List<String> buttonTexts = keyboard.getKeyboard().stream()
                .flatMap(Collection::stream)
                .map(InlineKeyboardButton::getText)
                .toList();

        List<String> callbackData = keyboard.getKeyboard().stream()
                .flatMap(Collection::stream)
                .map(InlineKeyboardButton::getCallbackData)
                .toList();

        assertThat(buttonTexts).contains("🌍 Изменить регион");
        assertThat(buttonTexts).contains("⬅️ Назад");

        assertThat(callbackData).contains("MENU:CHANGE_TZ");
        assertThat(callbackData).contains("MENU:BACK");

        verify(telegramClient).execute(any(AnswerCallbackQuery.class));
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
    @DisplayName("PLANT:CARE:<id>:MISTING — старая логика: отмечает уход и обновляет карточку")
    void shouldMarkCareDoneAndRefreshCard() {
        when(callbackQuery.getData()).thenReturn("PLANT:CARE:7:MISTING");

        com.plantcare.bot.domain.CareSchedule schedule =
                com.plantcare.bot.domain.CareSchedule.builder()
                        .nextDueAt(java.time.LocalDateTime.now().plusDays(7))
                        .build();
        PlantService.MarkCareDoneResult result =
                new PlantService.MarkCareDoneResult(false, schedule, null, java.time.LocalDateTime.now());

        when(plantService.markCareDone(testUser.getId(), 7L,
                com.plantcare.bot.domain.enums.TaskType.MISTING))
                .thenReturn(result);

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(plantService).markCareDone(testUser.getId(), 7L,
                com.plantcare.bot.domain.enums.TaskType.MISTING);
        verify(plantCardService).showPlantCard(
                testUser, 7L, 42, PlantCardService.BACK_TO_LIST, telegramClient
        );
    }

    @Test
    @DisplayName("PLANT:CARE:<id>:WATERING (issue #71) — стартует двухшаговый flow, history не пишется сразу")
    void shouldStartWateringDetailsFlowFromCard() {
        when(callbackQuery.getData()).thenReturn("PLANT:CARE:7:WATERING");

        com.plantcare.bot.domain.Plant plant =
                com.plantcare.bot.domain.Plant.builder().name("Монстера").build();
        com.plantcare.bot.domain.CareSchedule schedule =
                com.plantcare.bot.domain.CareSchedule.builder()
                        .plant(plant)
                        .taskType(com.plantcare.bot.domain.enums.TaskType.WATERING)
                        .intervalDays(7)
                        .nextDueAt(java.time.LocalDateTime.now().plusDays(7))
                        .active(true)
                        .build();
        org.springframework.test.util.ReflectionTestUtils.setField(schedule, "id", 99L);

        when(plantService.getPlantForUser(testUser.getId(), 7L))
                .thenReturn(java.util.Optional.of(plant));
        when(plantService.getActiveSchedules(7L)).thenReturn(java.util.List.of(schedule));

        service.handleCallback(callbackQuery, telegramClient, testUser);

        // markCareDone НЕ вызывается напрямую — flow начинается через NotificationCallbackService
        verify(plantService, never()).markCareDone(any(), any(), any());
        verify(notificationCallbackService).startWateringDetailsFlow(
                eq(99L), eq("Монстера"), eq(testUser.getTelegramChatId()), eq(telegramClient)
        );
    }

    @Test
    @DisplayName("PLANT:CARE с дубликатом (MISTING) — карточка не перерисовывается, только alert")
    void shouldNotRerenderOnDuplicateCare() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("PLANT:CARE:7:MISTING");

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
    @DisplayName("PLANT:PHOTO:<id> — если фото ушло, карточка дублируется новым сообщением вниз")
    void shouldRefreshCardAfterPhotoSent() {
        when(callbackQuery.getData()).thenReturn("PLANT:PHOTO:7");
        when(plantCardService.sendPlantPhoto(testUser, 7L, "cb-1", telegramClient))
                .thenReturn(true);

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(plantCardService).sendPlantPhoto(testUser, 7L, "cb-1", telegramClient);
        // messageId=null — карточка новым сообщением, не правит старое
        verify(plantCardService).showPlantCard(
                testUser, 7L, null, PlantCardService.BACK_TO_LIST, telegramClient);
    }

    @Test
    @DisplayName("PLANT:PHOTO:<id>:LOC:<locId> — back-context в комнату сохраняется при повторной карточке")
    void shouldPreserveLocationBackContextAfterPhoto() {
        when(callbackQuery.getData()).thenReturn("PLANT:PHOTO:7:LOC:9");
        when(plantCardService.sendPlantPhoto(testUser, 7L, "cb-1", telegramClient))
                .thenReturn(true);

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(plantCardService).showPlantCard(
                testUser, 7L, null,
                PlantCardService.BACK_TO_LOCATION_PREFIX + "9",
                telegramClient);
    }

    @Test
    @DisplayName("PLANT:PHOTO:<id> — если фото не отправилось (не загружено/ошибка), карточку не дублируем")
    void shouldNotRefreshCardWhenPhotoFails() {
        when(callbackQuery.getData()).thenReturn("PLANT:PHOTO:7");
        when(plantCardService.sendPlantPhoto(testUser, 7L, "cb-1", telegramClient))
                .thenReturn(false);

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(plantCardService).sendPlantPhoto(testUser, 7L, "cb-1", telegramClient);
        verify(plantCardService, never()).showPlantCard(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("PLANT:HISTORY:<id>:<page> открывает экран истории в том же сообщении")
    void shouldOpenHistoryScreen() {
        when(callbackQuery.getData()).thenReturn("PLANT:HISTORY:7:0");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(plantCardService).showHistoryScreen(
                testUser, 7L, 0, 42, PlantCardService.BACK_TO_LIST, telegramClient
        );
    }

    @Test
    @DisplayName("PLANT:HISTORY:<id>:<page>:LOC:<locId> сохраняет back-target в комнату")
    void shouldOpenHistoryWithLocationBack() {
        when(callbackQuery.getData()).thenReturn("PLANT:HISTORY:7:2:LOC:9");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(plantCardService).showHistoryScreen(
                testUser, 7L, 2, 42,
                PlantCardService.BACK_TO_LOCATION_PREFIX + "9",
                telegramClient
        );
    }

    @Test
    @DisplayName("PLANT:HISTORY с битым page — алёрт об ошибке")
    void shouldRejectMalformedHistoryPage() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("PLANT:HISTORY:7:abc");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(plantCardService, never()).showHistoryScreen(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any(), any());
        ArgumentCaptor<AnswerCallbackQuery> cap = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(cap.capture());
        assertThat(cap.getValue().getText()).contains("❌");
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

    // ==================== Edit mode (issue #27) ====================

    @Test
    @DisplayName("PLANT:EDIT:NAME:<id> — переводит в AWAITING_PLANT_RENAME и пишет stateData")
    void shouldStartRenameFlow() {
        when(callbackQuery.getData()).thenReturn("PLANT:EDIT:NAME:7");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(userService).updateState(testUser, ConversationState.AWAITING_PLANT_RENAME);
        verify(userService).setStateData(testUser, "edit_plant_id", "7");
        verify(userService).setStateData(testUser, "edit_message_id", "42");
        verify(plantCardService).promptForNewName(
                testUser, 7L, 42, PlantCardService.BACK_TO_LIST, telegramClient
        );
    }

    @Test
    @DisplayName("PLANT:EDIT:NOTE_CLEAR:<id> — очищает заметку и сразу шлёт экран настроек")
    void shouldClearNoteImmediately() {
        when(callbackQuery.getData()).thenReturn("PLANT:EDIT:NOTE_CLEAR:7");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(plantService).updateNotes(testUser.getId(), 7L, null);
        verify(plantCardService).showSettingsScreen(
                testUser, 7L, null, PlantCardService.BACK_TO_LIST, telegramClient
        );
        verify(userService, never()).updateState(any(), any());
    }

    @Test
    @DisplayName("PLANT:EDIT:DELETE_CONFIRM — архивирует и возвращает на список")
    void shouldArchiveOnDeleteConfirm() {
        when(callbackQuery.getData()).thenReturn("PLANT:EDIT:DELETE_CONFIRM:7");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(plantService).archivePlant(testUser.getId(), 7L);
        verify(plantMenuService).sendMyPlantsList(testUser, 42, telegramClient);
    }

    @Test
    @DisplayName("PLANT:SCHED:POSTPONE:<id>:<type>:<offset> — переносит и обновляет экран")
    void shouldPostponeSchedule() {
        when(callbackQuery.getData()).thenReturn("PLANT:SCHED:POSTPONE:7:WATERING:3");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        ArgumentCaptor<java.time.LocalDateTime> dtCap =
                ArgumentCaptor.forClass(java.time.LocalDateTime.class);
        verify(plantService).rescheduleSchedule(
                org.mockito.ArgumentMatchers.eq(testUser.getId()),
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(com.plantcare.bot.domain.enums.TaskType.WATERING),
                dtCap.capture()
        );
        // +3 дня от сейчас (с некоторым допуском)
        assertThat(dtCap.getValue()).isAfter(java.time.LocalDateTime.now().plusDays(2));
        assertThat(dtCap.getValue()).isBefore(java.time.LocalDateTime.now().plusDays(4));

        verify(plantCardService).showScheduleEditByType(
                testUser, 7L, com.plantcare.bot.domain.enums.TaskType.WATERING,
                42, PlantCardService.BACK_TO_LIST, telegramClient
        );
    }

    @Test
    @DisplayName("PLANT:SCHED:TOGGLE:<id>:<type> — тоглит и шлёт экран care-types")
    void shouldToggleSchedule() {
        when(callbackQuery.getData()).thenReturn("PLANT:SCHED:TOGGLE:7:MISTING");

        com.plantcare.bot.domain.CareSchedule afterToggle =
                com.plantcare.bot.domain.CareSchedule.builder()
                        .active(true).build();
        when(plantService.toggleSchedule(any(), any(), any())).thenReturn(afterToggle);

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(plantService).toggleSchedule(
                testUser.getId(), 7L, com.plantcare.bot.domain.enums.TaskType.MISTING);
        verify(plantCardService).showCareTypesScreen(
                testUser, 7L, 42, PlantCardService.BACK_TO_LIST, telegramClient
        );
    }

    @Test
    @DisplayName("Callback во время edit-режима — сбрасывает state перед обработкой")
    void shouldResetEditStateOnAnyPlantCallback() {
        // Пользователь застрял в edit-режиме (например, передумал что-то вводить)
        testUser.setConversationState(ConversationState.AWAITING_PLANT_RENAME);

        when(callbackQuery.getData()).thenReturn("PLANT:LIST");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(userService).resetToIdle(testUser);
        verify(plantMenuService).sendMyPlantsList(testUser, 42, telegramClient);
    }

    // ===================================================================
    // Журнал событий (issue #76): PLANT:EVENT:ADD / SAVE / LIST
    // ===================================================================

    @Test
    @DisplayName("PLANT:EVENT:ADD:<id> открывает меню выбора типа")
    void shouldOpenEventTypeMenu() {
        when(callbackQuery.getData()).thenReturn("PLANT:EVENT:ADD:7");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(plantCardService).showEventTypeMenu(
                testUser, 7L, 42, PlantCardService.BACK_TO_LIST, telegramClient
        );
    }

    @Test
    @DisplayName("PLANT:EVENT:SAVE:<id>:<TYPE> сохраняет, alert и возвращает карточку")
    void shouldSaveEventAndShowCard() {
        when(callbackQuery.getData()).thenReturn("PLANT:EVENT:SAVE:7:PRUNING");
        com.plantcare.bot.domain.PlantEvent saved =
                com.plantcare.bot.domain.PlantEvent.builder()
                        .eventType(com.plantcare.bot.domain.enums.PlantEventType.PRUNING)
                        .eventDate(java.time.LocalDateTime.now())
                        .build();
        when(plantEventService.addEvent(eq(testUser), eq(7L),
                eq(com.plantcare.bot.domain.enums.PlantEventType.PRUNING)))
                .thenReturn(PlantEventService.AddResult.created(saved));
        when(plantCardService.eventShortLabel(
                com.plantcare.bot.domain.enums.PlantEventType.PRUNING))
                .thenReturn("Обрезка");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(plantEventService).addEvent(testUser, 7L,
                com.plantcare.bot.domain.enums.PlantEventType.PRUNING);

        ArgumentCaptor<AnswerCallbackQuery> acap = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        try {
            verify(telegramClient).execute(acap.capture());
        } catch (org.telegram.telegrambots.meta.exceptions.TelegramApiException e) {
            throw new RuntimeException(e);
        }
        org.assertj.core.api.Assertions.assertThat(acap.getValue().getText())
                .contains("Обрезка")
                .contains("сохранено");

        verify(plantCardService).showPlantCard(
                testUser, 7L, 42, PlantCardService.BACK_TO_LIST, telegramClient
        );
    }

    @Test
    @DisplayName("PLANT:EVENT:SAVE дубликат — alert «Уже отмечено», карточка всё равно показывается")
    void shouldHandleDuplicateOnSave() {
        when(callbackQuery.getData()).thenReturn("PLANT:EVENT:SAVE:7:PRUNING");
        when(plantEventService.addEvent(any(), any(), any()))
                .thenReturn(PlantEventService.AddResult.duplicate(null));

        service.handleCallback(callbackQuery, telegramClient, testUser);

        ArgumentCaptor<AnswerCallbackQuery> acap = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        try {
            verify(telegramClient).execute(acap.capture());
        } catch (org.telegram.telegrambots.meta.exceptions.TelegramApiException e) {
            throw new RuntimeException(e);
        }
        org.assertj.core.api.Assertions.assertThat(acap.getValue().getText()).contains("Уже отмечено");

        verify(plantCardService).showPlantCard(
                eq(testUser), eq(7L), eq(42), any(), eq(telegramClient)
        );
    }

    @Test
    @DisplayName("PLANT:EVENT:SAVE для чужого/архивного растения — alert «не найдено», карточка НЕ показывается")
    void shouldHandleNotFoundOnSave() {
        when(callbackQuery.getData()).thenReturn("PLANT:EVENT:SAVE:7:PRUNING");
        when(plantEventService.addEvent(any(), any(), any()))
                .thenReturn(PlantEventService.AddResult.notFound());

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(plantCardService, never()).showPlantCard(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("PLANT:EVENT:SAVE с неизвестным типом — alert об ошибке, save не зовётся")
    void shouldRejectUnknownEventType() {
        when(callbackQuery.getData()).thenReturn("PLANT:EVENT:SAVE:7:SOMETHING_WEIRD");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(plantEventService, never()).addEvent(any(), any(), any());
    }

    @Test
    @DisplayName("PLANT:EVENT:LIST:<id>:<page> открывает журнал на указанной странице")
    void shouldShowEventsList() {
        when(callbackQuery.getData()).thenReturn("PLANT:EVENT:LIST:7:2");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(plantCardService).showEventsScreen(
                testUser, 7L, 2, 42, PlantCardService.BACK_TO_LIST, telegramClient
        );
    }

    @Test
    @DisplayName("PLANT:EVENT:LIST с back-target LOC — пагинация сохраняет контекст комнаты")
    void shouldKeepLocationBackTargetInEventsList() {
        when(callbackQuery.getData()).thenReturn("PLANT:EVENT:LIST:7:0:LOC:5");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(plantCardService).showEventsScreen(
                eq(testUser), eq(7L), eq(0), eq(42),
                eq(PlantCardService.BACK_TO_LOCATION_PREFIX + "5"),
                eq(telegramClient)
        );
    }

    // ===== Сезонные интервалы (issue #67): wiring диспетчера =====

    @Test
    @DisplayName("MENU:SEASONAL открывает экран сезонных интервалов")
    void shouldOpenSeasonalScreen() {
        when(callbackQuery.getData()).thenReturn("MENU:SEASONAL");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(seasonalMenuService).sendScreen(testUser, 42, telegramClient);
    }

    @Test
    @DisplayName("SEASON:TOGGLE переключает сезонность")
    void shouldToggleSeasonal() {
        when(callbackQuery.getData()).thenReturn("SEASON:TOGGLE");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(seasonalMenuService).toggleEnabled(testUser, 42, telegramClient);
    }

    @Test
    @DisplayName("SEASON:MODE:FIXED переключает режим на FIXED")
    void shouldSetFixedMode() {
        when(callbackQuery.getData()).thenReturn("SEASON:MODE:FIXED");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(seasonalMenuService).setMode(
                testUser, com.plantcare.bot.domain.enums.SeasonalMode.FIXED, 42, telegramClient);
    }

    @Test
    @DisplayName("SEASON:MODE с мусором — alert, setMode не зовётся")
    void shouldRejectUnknownSeasonalMode() {
        when(callbackQuery.getData()).thenReturn("SEASON:MODE:NONSENSE");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(seasonalMenuService, never()).setMode(any(), any(), any(), any());
    }

    @Test
    @DisplayName("SEASON:MUL:SUMMER листает летний коэффициент")
    void shouldCycleSummerMultiplier() {
        when(callbackQuery.getData()).thenReturn("SEASON:MUL:SUMMER");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(seasonalMenuService).cycleMultiplier(
                testUser, com.plantcare.bot.domain.enums.Season.SUMMER, 42, telegramClient);
    }

    @Test
    @DisplayName("SEASON:INT:WINTER листает зимний фиксированный интервал")
    void shouldCycleWinterInterval() {
        when(callbackQuery.getData()).thenReturn("SEASON:INT:WINTER");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(seasonalMenuService).cycleInterval(
                testUser, com.plantcare.bot.domain.enums.Season.WINTER, 42, telegramClient);
    }

    @Test
    @DisplayName("SEASON:INT:SUMMER:CLEAR сбрасывает летний фиксированный интервал")
    void shouldClearSummerInterval() {
        when(callbackQuery.getData()).thenReturn("SEASON:INT:SUMMER:CLEAR");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(seasonalMenuService).clearInterval(
                testUser, com.plantcare.bot.domain.enums.Season.SUMMER, 42, telegramClient);
        verify(seasonalMenuService, never()).cycleInterval(any(), any(), any(), any());
    }

    @Test
    @DisplayName("PLANT:SEASONAL:<id> циклит per-plant override")
    void shouldCyclePlantSeasonalOverride() {
        when(callbackQuery.getData()).thenReturn("PLANT:SEASONAL:7");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(plantCardService).cycleSeasonalOverride(
                testUser, 7L, 42, PlantCardService.BACK_TO_LIST, telegramClient);
    }

    @Test
    @DisplayName("PLANT:SEASONAL:<id>:LOC:<loc> сохраняет back-target комнаты")
    void shouldKeepLocationBackTargetInPlantSeasonal() {
        when(callbackQuery.getData()).thenReturn("PLANT:SEASONAL:7:LOC:5");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(plantCardService).cycleSeasonalOverride(
                testUser, 7L, 42,
                PlantCardService.BACK_TO_LOCATION_PREFIX + "5", telegramClient);
    }
}