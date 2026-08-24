package com.plantcare.bot.service;

import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.ConversationState;
import com.plantcare.core.service.CalendarExportService;
import com.plantcare.core.service.LocationService;
import com.plantcare.core.service.PhotoProgressService;
import com.plantcare.core.service.PlantAcclimationService;
import com.plantcare.core.service.PlantArchiveService;
import com.plantcare.core.service.PlantEventService;
import com.plantcare.core.service.PlantService;
import com.plantcare.core.service.PlantTemplateService;
import com.plantcare.core.service.ShoppingListService;
import com.plantcare.core.service.UserService;
import com.plantcare.core.service.UserSettingsService;

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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Покрытие {@code LOCATION:*} — создание/переименование/emoji/удаление/пауза комнат.
 * Отдельно от основного {@code MenuCallbackServiceTest}, который LOCATION: не трогает.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MenuCallbackService — LOCATION: callbacks")
class MenuCallbackServiceLocationTest {

    @Mock private UserService userService;
    @Mock private PlantService plantService;
    @Mock private LocationMenuService locationMenuService;
    @Mock private LocationSharingMenuService locationSharingMenuService;
    @Mock private LocationService locationService;
    @Mock private MainMenuService mainMenuService;
    @Mock private PlantMenuService plantMenuService;
    @Mock private PlantCardService plantCardService;
    @Mock private CalendarMenuService calendarMenuService;
    @Mock private CalendarExportService calendarExportService;
    @Mock private PlantTemplateService plantTemplateService;
    @Mock private NotificationCallbackService notificationCallbackService;
    @Mock private PlantEventService plantEventService;
    @Mock private PlantAcclimationService plantAcclimationService;
    @Mock private PhotoProgressService photoProgressService;
    @Mock private PhotoProgressCardService photoProgressCardService;
    @Mock private com.plantcare.bot.diagnosis.PlantDiagnosisService plantDiagnosisService;
    @Mock private com.plantcare.bot.weather.service.WeatherMenuService weatherMenuService;
    @Mock private PlantArchiveService plantArchiveService;
    @Mock private PlantArchiveMenuService plantArchiveMenuService;
    @Mock private ShoppingListService shoppingListService;
    @Mock private ShoppingListMenuService shoppingListMenuService;
    @Mock private DiseaseMenuService diseaseMenuService;
    @Mock private com.plantcare.bot.seasonal.service.SeasonalMenuService seasonalMenuService;
    @Mock private UserSettingsService userSettingsService;
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
                .featureFlags(Map.of())
                .build();

        org.mockito.Mockito.lenient().when(callbackQuery.getId()).thenReturn("cb-1");
        org.mockito.Mockito.lenient().when(callbackQuery.getMessage()).thenReturn(message);
        org.mockito.Mockito.lenient().when(message.getChatId()).thenReturn(100L);
        org.mockito.Mockito.lenient().when(message.getMessageId()).thenReturn(42);
    }

    @Test
    @DisplayName("LOCATION:CREATE выше лимита — алёрт с максимумом комнат, меню пресетов не шлётся")
    void shouldRejectCreate_whenLimitReached() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("LOCATION:CREATE");
        when(locationService.hasReachedLocationsLimit(testUser.getId())).thenReturn(true);
        when(locationService.getMaxLocationsPerUser()).thenReturn(10);

        service.handleCallback(callbackQuery, telegramClient, testUser);

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("максимум 10 комнат");
    }

    @Test
    @DisplayName("LOCATION:CREATE под лимитом — открывает меню пресетов комнат")
    void shouldOpenPresetMenu_whenUnderLimit() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("LOCATION:CREATE");
        when(locationService.hasReachedLocationsLimit(testUser.getId())).thenReturn(false);

        service.handleCallback(callbackQuery, telegramClient, testUser);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Выбери комнату");
    }

    @Test
    @DisplayName("LOCATION:PRESET:KITCHEN создаёт комнату и открывает меню локаций")
    void shouldCreateLocationFromPreset() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("LOCATION:PRESET:KITCHEN");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(locationService).createLocation(eq(testUser), eq("Кухня"), any());
        verify(locationMenuService).sendLocationsMenu(testUser, telegramClient);
        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Комната создана").contains("Кухня");
    }

    @Test
    @DisplayName("LOCATION:PRESET с неизвестным ключом — алёрт «Неизвестный пресет комнаты»")
    void shouldRejectPreset_whenUnknownKey() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("LOCATION:PRESET:GARAGE");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("❌").contains("Неизвестный пресет комнаты");
        verify(locationService, never()).createLocation(any(), any(), any());
    }

    @Test
    @DisplayName("LOCATION:PRESET — createLocation бросает неожиданное исключение — алёрт «Не удалось создать комнату»")
    void shouldRejectPreset_whenCreateThrowsUnexpectedException() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("LOCATION:PRESET:OFFICE");
        when(locationService.createLocation(any(), any(), any()))
                .thenThrow(new RuntimeException("db down"));

        service.handleCallback(callbackQuery, telegramClient, testUser);

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("❌").contains("Не удалось создать комнату");
        verify(locationMenuService, never()).sendLocationsMenu(any(), any());
    }

    @Test
    @DisplayName("LOCATION:CUSTOM_NAME переводит в AWAITING_LOCATION_NAME")
    void shouldStartCustomNameFlow() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("LOCATION:CUSTOM_NAME");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(userService).updateState(testUser, ConversationState.AWAITING_LOCATION_NAME);
        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Как назовём комнату");
    }

    @Test
    @DisplayName("LOCATION:VIEW:<id> открывает экран локации")
    void shouldOpenLocationScreen() {
        when(callbackQuery.getData()).thenReturn("LOCATION:VIEW:9");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(locationMenuService).sendLocationScreen(testUser, 9L, telegramClient);
    }

    @Test
    @DisplayName("LOCATION:SHARE:<id> генерирует одноразовую ссылку приглашения")
    void shouldSendInviteLink() {
        when(callbackQuery.getData()).thenReturn("LOCATION:SHARE:9");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(locationSharingMenuService).sendInviteLink(testUser, 9L, telegramClient);
    }

    @Test
    @DisplayName("LOCATION:RENAME:<id> сохраняет editing_location_id и переводит в AWAITING_LOCATION_RENAME")
    void shouldStartRenameFlow() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("LOCATION:RENAME:9");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(userService).setStateData(testUser, "editing_location_id", "9");
        verify(userService).updateState(testUser, ConversationState.AWAITING_LOCATION_RENAME);
        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("новое название комнаты");
    }

    @Test
    @DisplayName("LOCATION:EMOJI:<id> шлёт клавиатуру emoji с префиксом LOCATION_CHANGE_EMOJI:")
    void shouldStartEmojiChangeFlow() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("LOCATION:EMOJI:9");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(userService).setStateData(testUser, "editing_location_id", "9");
        verify(userService).updateState(testUser, ConversationState.AWAITING_LOCATION_CHANGE_EMOJI);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) captor.getValue().getReplyMarkup();
        boolean hasHeartButton = keyboard.getKeyboard().stream()
                .flatMap(java.util.Collection::stream)
                .anyMatch(b -> "LOCATION_CHANGE_EMOJI:❤️".equals(b.getCallbackData()));
        assertThat(hasHeartButton).isTrue();
    }

    @Test
    @DisplayName("LOCATION:DELETE_CONFIRM:<id>:<targetId> удаляет комнату и переносит растения")
    void shouldDeleteLocationOnConfirm() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("LOCATION:DELETE_CONFIRM:9:1");
        when(locationService.countPlantsInLocation(testUser.getId(), 9L)).thenReturn(3L);

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(locationService).deleteLocation(testUser.getId(), 9L, 1L);
        verify(locationMenuService).sendLocationsMenu(testUser, telegramClient);
        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("удалена").contains("3");
    }

    @Test
    @DisplayName("LOCATION:DELETE_CONFIRM — deleteLocation бросает исключение — алёрт с текстом ошибки")
    void shouldRejectDeleteConfirm_whenDeleteThrows() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("LOCATION:DELETE_CONFIRM:9:1");
        when(locationService.countPlantsInLocation(testUser.getId(), 9L)).thenReturn(0L);
        doThrow(new IllegalArgumentException("Комната не найдена"))
                .when(locationService).deleteLocation(testUser.getId(), 9L, 1L);

        service.handleCallback(callbackQuery, telegramClient, testUser);

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("❌").contains("Комната не найдена");
        verify(locationMenuService, never()).sendLocationsMenu(any(), any());
    }

    @Test
    @DisplayName("LOCATION:DELETE:<id> открывает диалог подтверждения удаления")
    void shouldOpenDeleteDialog() {
        when(callbackQuery.getData()).thenReturn("LOCATION:DELETE:9");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(locationMenuService).sendDeleteLocationDialog(testUser, 9L, telegramClient);
    }

    @Test
    @DisplayName("LOCATION:PAUSE_MENU:<id> шлёт меню выбора длительности паузы")
    void shouldOpenPauseMenu() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("LOCATION:PAUSE_MENU:9");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("на паузу");
        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) captor.getValue().getReplyMarkup();
        boolean has14Days = keyboard.getKeyboard().stream()
                .flatMap(java.util.Collection::stream)
                .anyMatch(b -> "LOCATION:PAUSE_CONFIRM:9:14".equals(b.getCallbackData()));
        assertThat(has14Days).isTrue();
    }

    @Test
    @DisplayName("LOCATION:PAUSE_MENU с нечисловым ID — алёрт «Неверный ID»")
    void shouldRejectPauseMenu_whenIdMalformed() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("LOCATION:PAUSE_MENU:abc");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Неверный ID");
    }

    @Test
    @DisplayName("LOCATION:PAUSE_CONFIRM:<id>:<days> ставит на паузу и открывает экран локации")
    void shouldPauseLocationOnConfirm() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("LOCATION:PAUSE_CONFIRM:9:14");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(locationService).pauseLocation(testUser, 9L, 14);
        verify(locationMenuService).sendLocationScreen(testUser, 9L, telegramClient);
        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Пауза 14 дн.");
    }

    @Test
    @DisplayName("LOCATION:PAUSE_CONFIRM без дней — алёрт «Неверная команда»")
    void shouldRejectPauseConfirm_whenMissingDays() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("LOCATION:PAUSE_CONFIRM:9");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Неверная команда");
        verify(locationService, never()).pauseLocation(any(), any(), anyInt());
    }

    @Test
    @DisplayName("LOCATION:PAUSE_CONFIRM с нечисловыми id/днями — алёрт «Неверный ID или дни»")
    void shouldRejectPauseConfirm_whenIdOrDaysInvalid() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("LOCATION:PAUSE_CONFIRM:abc:xyz");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Неверный ID или дни");
    }

    @Test
    @DisplayName("LOCATION:PAUSE_CONFIRM — pauseLocation бросает исключение — алёрт с текстом ошибки")
    void shouldRejectPauseConfirm_whenPauseThrows() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("LOCATION:PAUSE_CONFIRM:9:14");
        doThrow(new IllegalArgumentException("Уже на паузе"))
                .when(locationService).pauseLocation(testUser, 9L, 14);

        service.handleCallback(callbackQuery, telegramClient, testUser);

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("❌").contains("Уже на паузе");
        verify(locationMenuService, never()).sendLocationScreen(any(), any(), any());
    }

    @Test
    @DisplayName("Незнакомый LOCATION:-callback — алёрт «Неизвестная команда»")
    void shouldRejectUnknownLocationCommand() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("LOCATION:BOGUS");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("❌").contains("Неизвестная команда");
    }
}
