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
import org.springframework.test.util.ReflectionTestUtils;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Покрытие верхнеуровневого диспетчера {@link MenuCallbackService#handleCallback} и
 * дополнительных {@code MENU:*} действий (настройки, тихие часы, отпуск, погода,
 * переключение локаций LOC_SWITCH/LOC_PICK/LOC_PAUSE/LOC_RESUME), которых не было
 * в оригинальном {@code MenuCallbackServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MenuCallbackService — маршрутизация и дополнительные MENU-действия")
class MenuCallbackServiceRoutingTest {

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

    // ==================== Верхнеуровневый диспетчер ====================

    @Test
    @DisplayName("data == null — алёрт «Пустая команда», ни один хендлер не трогается")
    void shouldAnswerEmptyCommand_whenDataIsNull() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn(null);

        service.handleCallback(callbackQuery, telegramClient, testUser);

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Пустая команда");
        verify(userService, never()).updateState(any(), any());
    }

    @Test
    @DisplayName("data пустая строка из пробелов — тот же алёрт «Пустая команда»")
    void shouldAnswerEmptyCommand_whenDataIsBlank() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("   ");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Пустая команда");
    }

    @Test
    @DisplayName("Незнакомый верхнеуровневый префикс — «❌ Неизвестная команда»")
    void shouldAnswerUnknownCommand_whenTopLevelPrefixUnrecognized() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("SOMETHING_ELSE:123");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("❌").contains("Неизвестная команда");
    }

    // ==================== MENU: доп. действия ====================

    @Test
    @DisplayName("MENU:LOCATIONS открывает меню локаций")
    void shouldOpenLocationsMenu() {
        when(callbackQuery.getData()).thenReturn("MENU:LOCATIONS");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(locationMenuService).sendLocationsMenu(testUser, telegramClient);
    }

    @Test
    @DisplayName("MENU:WEATHER открывает экран погоды")
    void shouldOpenWeatherScreen() {
        when(callbackQuery.getData()).thenReturn("MENU:WEATHER");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(weatherMenuService).sendWeatherScreen(testUser, 42, telegramClient);
    }

    @Test
    @DisplayName("MENU:DISEASES открывает справочник болезней")
    void shouldOpenDiseasesList() {
        when(callbackQuery.getData()).thenReturn("MENU:DISEASES");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(diseaseMenuService).sendList(testUser, telegramClient);
    }

    @Test
    @DisplayName("MENU:SHOPPING_LIST открывает список покупок")
    void shouldOpenShoppingList() {
        when(callbackQuery.getData()).thenReturn("MENU:SHOPPING_LIST");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(shoppingListMenuService).sendShoppingList(testUser, telegramClient);
    }

    @Test
    @DisplayName("MENU:MY_TEMPLATES с пустым списком — сообщение «Пока нет шаблонов»")
    void shouldShowEmptyTemplatesList() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("MENU:MY_TEMPLATES");
        when(plantTemplateService.getUserTemplates(testUser.getId())).thenReturn(List.of());

        service.handleCallback(callbackQuery, telegramClient, testUser);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Пока нет шаблонов");
    }

    @Test
    @DisplayName("MENU:CHANGE_TZ переводит в AWAITING_TIMEZONE и шлёт reply-клавиатуру запроса локации")
    void shouldStartTimezoneChangeFlow() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("MENU:CHANGE_TZ");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(userService).updateState(testUser, ConversationState.AWAITING_TIMEZONE);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("установить регион");
        assertThat(captor.getValue().getReplyMarkup()).isInstanceOf(ReplyKeyboardMarkup.class);
    }

    @Test
    @DisplayName("MENU:QUIET_HOURS показывает текущие тихие часы и кнопки управления ими")
    void shouldShowQuietHoursMenu() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("MENU:QUIET_HOURS");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("22:00").contains("09:00");

        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) captor.getValue().getReplyMarkup();
        List<String> callbackData = keyboard.getKeyboard().stream()
                .flatMap(Collection::stream)
                .map(InlineKeyboardButton::getCallbackData)
                .toList();
        assertThat(callbackData).contains("MENU:QUIET_START", "MENU:QUIET_END", "MENU:QUIET_RESET");
    }

    @Test
    @DisplayName("MENU:QUIET_START переводит в AWAITING_QUIET_START")
    void shouldStartQuietHoursStartFlow() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("MENU:QUIET_START");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(userService).updateState(testUser, ConversationState.AWAITING_QUIET_START);
        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("начала тихих часов");
    }

    @Test
    @DisplayName("MENU:QUIET_END переводит в AWAITING_QUIET_END")
    void shouldStartQuietHoursEndFlow() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("MENU:QUIET_END");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(userService).updateState(testUser, ConversationState.AWAITING_QUIET_END);
        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("конца тихих часов");
    }

    @Test
    @DisplayName("MENU:QUIET_RESET сбрасывает тихие часы и повторно показывает меню")
    void shouldResetQuietHours() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("MENU:QUIET_RESET");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(userSettingsService).resetQuietHours(testUser);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient, times(2)).execute(captor.capture());
        assertThat(captor.getAllValues().get(0).getText()).contains("сброшены");
    }

    @Test
    @DisplayName("MENU:CALENDAR_EXPORT шлёт ссылку на .ics с токеном")
    void shouldSendCalendarExportLink() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("MENU:CALENDAR_EXPORT");
        when(calendarExportService.getOrCreateToken(testUser)).thenReturn("tok-123");
        when(calendarExportService.buildCalendarUrl("tok-123")).thenReturn("https://example.test/ics/tok-123");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("https://example.test/ics/tok-123");
    }

    @Test
    @DisplayName("MENU:VACATION показывает меню выбора числа дней отпуска")
    void shouldShowVacationDaysMenu() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("MENU:VACATION");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("На сколько дней уезжаешь");
    }

    @Test
    @DisplayName("MENU:VACATION_3 запускает отпуск на 3 дня и шлёт дату окончания")
    void shouldStartThreeDayVacation() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("MENU:VACATION_3");
        User updated = User.builder()
                .telegramChatId(100L)
                .timezone("UTC")
                .pausedUntil(LocalDateTime.of(2026, 8, 19, 10, 0))
                .build();
        when(userService.startVacation(testUser, 3)).thenReturn(updated);

        service.handleCallback(callbackQuery, telegramClient, testUser);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Отпуск до 19.08");
    }

    @Test
    @DisplayName("MENU:VACATION_7 запускает отпуск на 7 дней")
    void shouldStartSevenDayVacation() {
        when(callbackQuery.getData()).thenReturn("MENU:VACATION_7");
        when(userService.startVacation(testUser, 7)).thenReturn(testUser);

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(userService).startVacation(testUser, 7);
    }

    @Test
    @DisplayName("MENU:VACATION_14 запускает отпуск на 14 дней")
    void shouldStartFourteenDayVacation() {
        when(callbackQuery.getData()).thenReturn("MENU:VACATION_14");
        when(userService.startVacation(testUser, 14)).thenReturn(testUser);

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(userService).startVacation(testUser, 14);
    }

    @Test
    @DisplayName("Отпуск вне диапазона дней — алёрт с текстом ошибки сервиса")
    void shouldRejectVacationWhenDaysOutOfRange() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("MENU:VACATION_3");
        when(userService.startVacation(testUser, 3))
                .thenThrow(new IllegalArgumentException("Дней должно быть от 1 до 60"));

        service.handleCallback(callbackQuery, telegramClient, testUser);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("❌").contains("Дней должно быть от 1 до 60");
    }

    @Test
    @DisplayName("MENU:VACATION_OTHER переводит в AWAITING_VACATION_DAYS с указанием диапазона")
    void shouldPromptForCustomVacationDays() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("MENU:VACATION_OTHER");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(userService).updateState(testUser, ConversationState.AWAITING_VACATION_DAYS);
        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText())
                .contains(String.valueOf(UserService.MIN_VACATION_DAYS))
                .contains(String.valueOf(UserService.MAX_VACATION_DAYS));
    }

    @Test
    @DisplayName("MENU:VACATION_END завершает отпуск сообщением «С возвращением!»")
    void shouldEndVacation() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("MENU:VACATION_END");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(userService).endVacation(testUser);
        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("С возвращением");
    }

    @Test
    @DisplayName("MENU:VACATION_EXTEND_3 продлевает отпуск на 3 дня")
    void shouldExtendVacationByThreeDays() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("MENU:VACATION_EXTEND_3");
        User updated = User.builder()
                .telegramChatId(100L)
                .timezone("UTC")
                .pausedUntil(LocalDateTime.of(2026, 8, 22, 10, 0))
                .build();
        when(userService.extendVacation(testUser, 3)).thenReturn(updated);

        service.handleCallback(callbackQuery, telegramClient, testUser);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("продлён до 22.08");
    }

    @Test
    @DisplayName("MENU:VACATION_EXTEND_7 продлевает отпуск на 7 дней")
    void shouldExtendVacationBySevenDays() {
        when(callbackQuery.getData()).thenReturn("MENU:VACATION_EXTEND_7");
        when(userService.extendVacation(testUser, 7)).thenReturn(testUser);

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(userService).extendVacation(testUser, 7);
    }

    @Test
    @DisplayName("Продление отпуска у юзера не в отпуске — алёрт «Юзер не в отпуске»")
    void shouldRejectExtendVacationWhenNotInVacation() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("MENU:VACATION_EXTEND_3");
        when(userService.extendVacation(testUser, 3))
                .thenThrow(new IllegalStateException("Юзер не в отпуске"));

        service.handleCallback(callbackQuery, telegramClient, testUser);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("❌").contains("Юзер не в отпуске");
    }

    // ==================== WEATHER: ====================

    @Test
    @DisplayName("WEATHER:TOGGLE переключает флаг учёта погоды")
    void shouldToggleWeather() {
        when(callbackQuery.getData()).thenReturn("WEATHER:TOGGLE");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(weatherMenuService).toggleEnabled(testUser, 42, telegramClient);
    }

    @Test
    @DisplayName("WEATHER:LOCATION просит прислать геолокацию")
    void shouldPromptForWeatherLocation() {
        when(callbackQuery.getData()).thenReturn("WEATHER:LOCATION");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(weatherMenuService).promptForLocation(testUser, telegramClient);
    }

    @Test
    @DisplayName("Незнакомое действие WEATHER: — алёрт «Неизвестная команда»")
    void shouldRejectUnknownWeatherAction() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("WEATHER:BOGUS");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("❌");
        verify(weatherMenuService, never()).toggleEnabled(any(), any(), any());
    }

    // ==================== LOC_SWITCH_LIST / LOC_PICK / LOC_PAUSE / LOC_RESUME ====================

    @Test
    @DisplayName("LOC_SWITCH_LIST без локаций — просто текстовое сообщение без кнопок")
    void shouldTellNoLocations_whenSwitchListEmpty() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("LOC_SWITCH_LIST");
        when(locationService.getUserLocations(testUser.getId())).thenReturn(List.of());

        service.handleCallback(callbackQuery, telegramClient, testUser);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("нет локаций");
    }

    @Test
    @DisplayName("LOC_SWITCH_LIST с локациями — активная помечена галочкой")
    void shouldShowLocationPickList() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("LOC_SWITCH_LIST");
        Location active = Location.builder().name("Кухня").emoji("").build();
        ReflectionTestUtils.setField(active, "id", 1L);
        testUser.setActiveLocation(active);
        when(locationService.getUserLocations(testUser.getId())).thenReturn(List.of(active));

        service.handleCallback(callbackQuery, telegramClient, testUser);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) captor.getValue().getReplyMarkup();
        List<String> buttonTexts = keyboard.getKeyboard().stream()
                .flatMap(Collection::stream)
                .map(InlineKeyboardButton::getText)
                .toList();
        assertThat(buttonTexts).anyMatch(t -> t.startsWith("✅"));
    }

    @Test
    @DisplayName("LOC_PICK:<id> переключает активную локацию и обновляет главное меню")
    void shouldSwitchActiveLocation() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("LOC_PICK:5");
        Location loc = Location.builder().name("Балкон").emoji("").build();
        when(locationService.setActiveLocation(testUser, 5L)).thenReturn(loc);
        when(userService.getByIdOrThrow(testUser.getId())).thenReturn(testUser);

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(mainMenuService).sendMainMenu(testUser, telegramClient);
        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Балкон");
    }

    @Test
    @DisplayName("LOC_PICK с нечисловым ID — алёрт «Неверный ID»")
    void shouldRejectLocPick_whenIdMalformed() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("LOC_PICK:abc");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Неверный ID");
        verify(locationService, never()).setActiveLocation(any(), any());
    }

    @Test
    @DisplayName("LOC_PICK — setActiveLocation бросает исключение — алёрт с текстом ошибки")
    void shouldRejectLocPick_whenSetActiveLocationThrows() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("LOC_PICK:5");
        when(locationService.setActiveLocation(testUser, 5L))
                .thenThrow(new IllegalArgumentException("Локация не найдена"));

        service.handleCallback(callbackQuery, telegramClient, testUser);

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("❌").contains("Локация не найдена");
        verify(mainMenuService, never()).sendMainMenu(any(), any());
    }

    @Test
    @DisplayName("LOC_PAUSE:<id>:<days> ставит локацию на паузу через свитчер")
    void shouldPauseLocationFromSwitcher() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("LOC_PAUSE:5:7");
        when(userService.getByIdOrThrow(testUser.getId())).thenReturn(testUser);

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(locationService).pauseLocation(testUser, 5L, 7);
        verify(mainMenuService).sendMainMenu(testUser, telegramClient);
        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("⏸").contains("7 дн.");
    }

    @Test
    @DisplayName("LOC_PAUSE без второй части (дней) — алёрт «Неверная команда»")
    void shouldRejectLocPause_whenMissingDays() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("LOC_PAUSE:5");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Неверная команда");
        verify(locationService, never()).pauseLocation(any(), any(), anyInt());
    }

    @Test
    @DisplayName("LOC_PAUSE с нечисловыми id/днями — алёрт «Неверный ID или дни»")
    void shouldRejectLocPause_whenIdOrDaysInvalid() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("LOC_PAUSE:abc:xyz");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Неверный ID или дни");
    }

    @Test
    @DisplayName("LOC_PAUSE — pauseLocation бросает исключение — алёрт с текстом ошибки")
    void shouldRejectLocPause_whenPauseThrows() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("LOC_PAUSE:5:7");
        org.mockito.Mockito.doThrow(new IllegalArgumentException("Локация уже на паузе"))
                .when(locationService).pauseLocation(testUser, 5L, 7);

        service.handleCallback(callbackQuery, telegramClient, testUser);

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("❌").contains("Локация уже на паузе");
        verify(mainMenuService, never()).sendMainMenu(any(), any());
    }

    @Test
    @DisplayName("LOC_RESUME:<id> снимает паузу")
    void shouldResumeLocation() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("LOC_RESUME:5");
        when(userService.getByIdOrThrow(testUser.getId())).thenReturn(testUser);

        service.handleCallback(callbackQuery, telegramClient, testUser);

        verify(locationService).resumeLocation(testUser, 5L);
        verify(mainMenuService).sendMainMenu(testUser, telegramClient);
        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("возобновлена");
    }

    @Test
    @DisplayName("LOC_RESUME с нечисловым ID — алёрт «Неверный ID»")
    void shouldRejectLocResume_whenIdMalformed() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("LOC_RESUME:abc");

        service.handleCallback(callbackQuery, telegramClient, testUser);

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Неверный ID");
        verify(locationService, never()).resumeLocation(any(), any());
    }

    @Test
    @DisplayName("LOC_RESUME — resumeLocation бросает исключение — алёрт с текстом ошибки")
    void shouldRejectLocResume_whenResumeThrows() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("LOC_RESUME:5");
        org.mockito.Mockito.doThrow(new IllegalArgumentException("Локация не на паузе"))
                .when(locationService).resumeLocation(testUser, 5L);

        service.handleCallback(callbackQuery, telegramClient, testUser);

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("❌").contains("Локация не на паузе");
    }
}
