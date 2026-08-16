package com.plantcare.bot.service;

import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.repository.CareScheduleRepository;
import com.plantcare.core.service.LocationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-тесты для LocationMenuService")
class LocationMenuServiceTest {

    @Mock private LocationService locationService;
    @Mock private CareScheduleRepository careScheduleRepository;
    @Mock private TelegramClient telegramClient;

    @InjectMocks
    private LocationMenuService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .telegramChatId(100L)
                .timezone("UTC")
                .build();
    }

    private static Location location(long id, String name, String emoji, boolean isDefault) {
        Location location = Location.builder()
                .name(name)
                .emoji(emoji)
                .defaultLocation(isDefault)
                .build();
        ReflectionTestUtils.setField(location, "id", id);
        return location;
    }

    private static Plant plant(long id, String name, Location location) {
        Plant plant = Plant.builder()
                .name(name)
                .location(location)
                .build();
        ReflectionTestUtils.setField(plant, "id", id);
        return plant;
    }

    private List<InlineKeyboardButton> buttons(SendMessage message) {
        InlineKeyboardMarkup markup = (InlineKeyboardMarkup) message.getReplyMarkup();
        return markup.getKeyboard().stream().flatMap(Collection::stream).toList();
    }

    // ==================== sendLocationsMenu ====================

    @Test
    @DisplayName("sendLocationsMenu — пустой список комнат показывает заглушку и только кнопки добавления/назад")
    void shouldShowEmptyStateWhenNoLocations() throws TelegramApiException {
        when(locationService.getUserLocations(user.getId())).thenReturn(List.of());

        service.sendLocationsMenu(user, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        SendMessage sent = captor.getValue();

        assertThat(sent.getText()).contains("Пока комнат нет.");
        List<String> callbackData = buttons(sent).stream()
                .map(InlineKeyboardButton::getCallbackData)
                .toList();
        assertThat(callbackData).containsExactly("LOCATION:CREATE", "MENU:BACK");
    }

    @Test
    @DisplayName("sendLocationsMenu — несколько комнат: плюрализация счётчика растений и маркер дефолтной комнаты")
    void shouldListLocationsWithPluralizedCountsAndDefaultMarker() throws TelegramApiException {
        Location def = location(1L, "Гостиная", "🛋", true);
        Location other = location(2L, "Спальня", null, false);
        Location edge = location(3L, "Кухня", "🍳", false);
        when(locationService.getUserLocations(user.getId())).thenReturn(List.of(def, other, edge));
        when(locationService.countPlantsInLocation(user.getId(), 1L)).thenReturn(1L);
        when(locationService.countPlantsInLocation(user.getId(), 2L)).thenReturn(2L);
        when(locationService.countPlantsInLocation(user.getId(), 3L)).thenReturn(11L);

        service.sendLocationsMenu(user, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        String text = captor.getValue().getText();

        assertThat(text).contains("🛋 Гостиная — 1 растение");
        assertThat(text).contains("· по умолчанию");
        assertThat(text).contains("Спальня — 2 растения");
        assertThat(text).contains("🍳 Кухня — 11 растений");

        List<String> callbackData = buttons(captor.getValue()).stream()
                .map(InlineKeyboardButton::getCallbackData)
                .toList();
        assertThat(callbackData).contains("LOCATION:VIEW:1", "LOCATION:VIEW:2", "LOCATION:VIEW:3");
    }

    @Test
    @DisplayName("sendLocationsMenu — плюрализация: 21 растение (единственное число на границе 21)")
    void shouldPluralizeSingularOnTwentyOne() throws TelegramApiException {
        Location single = location(9L, "Балкон", null, false);
        when(locationService.getUserLocations(user.getId())).thenReturn(List.of(single));
        when(locationService.countPlantsInLocation(user.getId(), 9L)).thenReturn(21L);

        service.sendLocationsMenu(user, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Балкон — 21 растение");
    }

    @Test
    @DisplayName("sendLocationsMenu — TelegramApiException при отправке не пробрасывается наружу")
    void shouldSwallowTelegramExceptionOnLocationsMenu() throws TelegramApiException {
        when(locationService.getUserLocations(user.getId())).thenReturn(List.of());
        doThrow(new TelegramApiException("boom")).when(telegramClient).execute(any(SendMessage.class));

        service.sendLocationsMenu(user, telegramClient);

        verify(telegramClient).execute(any(SendMessage.class));
    }

    // ==================== sendLocationScreen ====================

    @Test
    @DisplayName("sendLocationScreen — пустая комната без активного полива, дефолтная (без кнопки удаления)")
    void shouldShowEmptyLocationScreenForDefaultLocation() throws TelegramApiException {
        Location loc = location(4L, "Кухня", null, true);
        when(locationService.getLocation(user.getId(), 4L)).thenReturn(loc);
        when(locationService.getPlantsInLocation(user.getId(), 4L)).thenReturn(List.of());
        when(careScheduleRepository.hasActiveSchedulesInUserLocation(user.getId(), 4L, TaskType.WATERING))
                .thenReturn(false);

        service.sendLocationScreen(user, 4L, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        SendMessage sent = captor.getValue();

        assertThat(sent.getText()).contains("В этой комнате пока нет растений.");
        List<String> callbackData = buttons(sent).stream()
                .map(InlineKeyboardButton::getCallbackData)
                .toList();
        assertThat(callbackData).doesNotContain("v1:bulk_done:4");
        assertThat(callbackData).doesNotContain("LOCATION:DELETE:4");
        assertThat(callbackData).contains("LOCATION:SHARE:4", "LOCATION:PAUSE_MENU:4", "MENU:LOCATIONS");
    }

    @Test
    @DisplayName("sendLocationScreen — комната с растениями, активным поливом, на паузе, не дефолтная")
    void shouldShowFullLocationScreenWithBulkWaterAndResumeButton() throws TelegramApiException {
        Location loc = location(5L, "Балкон", "🌞", false);
        ReflectionTestUtils.setField(loc, "pausedUntil", java.time.Instant.now().plusSeconds(3600));
        Plant p1 = plant(10L, "Монстера", loc);
        Plant p2 = plant(11L, "Фикус", loc);
        when(locationService.getLocation(user.getId(), 5L)).thenReturn(loc);
        when(locationService.getPlantsInLocation(user.getId(), 5L)).thenReturn(List.of(p1, p2));
        when(careScheduleRepository.hasActiveSchedulesInUserLocation(user.getId(), 5L, TaskType.WATERING))
                .thenReturn(true);

        service.sendLocationScreen(user, 5L, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        SendMessage sent = captor.getValue();

        assertThat(sent.getText()).contains("*Растения:*").contains("• Монстера").contains("• Фикус");
        List<String> callbackData = buttons(sent).stream()
                .map(InlineKeyboardButton::getCallbackData)
                .toList();
        assertThat(callbackData).contains(
                "PLANT:VIEW:10:LOC:5",
                "PLANT:VIEW:11:LOC:5",
                "v1:bulk_done:5",
                "LOC_RESUME:5",
                "LOCATION:DELETE:5"
        );
        assertThat(callbackData).doesNotContain("LOCATION:PAUSE_MENU:5");
    }

    // ==================== sendMovePlantDialog ====================

    @Test
    @DisplayName("sendMovePlantDialog — растение без текущей комнаты: все комнаты предлагаются к переносу")
    void shouldOfferAllLocationsWhenPlantHasNoCurrentLocation() throws TelegramApiException {
        Plant plant = plant(20L, "Кактус", null);
        Location a = location(1L, "A", null, false);
        Location b = location(2L, "B", null, false);
        when(locationService.getUserPlant(user.getId(), 20L)).thenReturn(plant);
        when(locationService.getUserLocations(user.getId())).thenReturn(List.of(a, b));

        service.sendMovePlantDialog(user, 20L, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        SendMessage sent = captor.getValue();

        assertThat(sent.getText()).contains("Куда переместить растение *Кактус*?");
        List<String> callbackData = buttons(sent).stream()
                .map(InlineKeyboardButton::getCallbackData)
                .toList();
        assertThat(callbackData).containsExactly(
                "PLANT:MOVE_CONFIRM:20:1", "PLANT:MOVE_CONFIRM:20:2", "PLANT:VIEW:20"
        );
    }

    @Test
    @DisplayName("sendMovePlantDialog — текущая комната растения исключается из списка целей")
    void shouldExcludeCurrentLocationFromMoveTargets() throws TelegramApiException {
        Location current = location(1L, "Текущая", null, false);
        Location target = location(2L, "Целевая", null, false);
        Plant plant = plant(21L, "Пальма", current);
        when(locationService.getUserPlant(user.getId(), 21L)).thenReturn(plant);
        when(locationService.getUserLocations(user.getId())).thenReturn(List.of(current, target));

        service.sendMovePlantDialog(user, 21L, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        List<String> callbackData = buttons(captor.getValue()).stream()
                .map(InlineKeyboardButton::getCallbackData)
                .toList();

        assertThat(callbackData).containsExactly("PLANT:MOVE_CONFIRM:21:2", "PLANT:VIEW:21");
    }

    // ==================== sendDeleteLocationDialog ====================

    @Test
    @DisplayName("sendDeleteLocationDialog — дефолтную комнату удалить нельзя")
    void shouldRejectDeletingDefaultLocation() throws TelegramApiException {
        Location def = location(1L, "Гостиная", null, true);
        when(locationService.getLocation(user.getId(), 1L)).thenReturn(def);
        when(locationService.countPlantsInLocation(user.getId(), 1L)).thenReturn(0L);

        service.sendDeleteLocationDialog(user, 1L, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Дефолтную комнату удалить нельзя.");
        assertThat(captor.getValue().getReplyMarkup()).isNull();
        verify(locationService, never()).getUserLocations(any());
    }

    @Test
    @DisplayName("sendDeleteLocationDialog — единственная комната: некуда переносить растения")
    void shouldRejectDeletingWhenNoOtherLocationsExist() throws TelegramApiException {
        Location only = location(2L, "Спальня", null, false);
        when(locationService.getLocation(user.getId(), 2L)).thenReturn(only);
        when(locationService.countPlantsInLocation(user.getId(), 2L)).thenReturn(3L);
        when(locationService.getUserLocations(user.getId())).thenReturn(List.of(only));

        service.sendDeleteLocationDialog(user, 2L, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText())
                .contains("Некуда перенести растения. Сначала создай другую комнату.");
        assertThat(captor.getValue().getReplyMarkup()).isNull();
    }

    @Test
    @DisplayName("sendDeleteLocationDialog — есть куда перенести: показывает диалог с целевыми комнатами")
    void shouldShowDeleteDialogWithTargetLocations() throws TelegramApiException {
        Location toDelete = location(3L, "Кухня", null, false);
        Location target = location(4L, "Спальня", null, false);
        when(locationService.getLocation(user.getId(), 3L)).thenReturn(toDelete);
        when(locationService.countPlantsInLocation(user.getId(), 3L)).thenReturn(5L);
        when(locationService.getUserLocations(user.getId())).thenReturn(List.of(toDelete, target));

        service.sendDeleteLocationDialog(user, 3L, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        SendMessage sent = captor.getValue();

        assertThat(sent.getText()).contains("Удалить комнату *Кухня*?").contains("Растения из неё: *5*.");
        List<String> callbackData = buttons(sent).stream()
                .map(InlineKeyboardButton::getCallbackData)
                .toList();
        assertThat(callbackData).containsExactly("LOCATION:DELETE_CONFIRM:3:4", "LOCATION:VIEW:3");
    }
}
