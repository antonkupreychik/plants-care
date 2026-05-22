package com.plantcare.bot.service;

import com.plantcare.bot.domain.CareSchedule;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.TaskType;
import com.plantcare.bot.repository.CareScheduleRepository;
import com.plantcare.bot.repository.PlantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-тесты для MainMenuService")
class MainMenuServiceTest {

    @Mock
    private PlantRepository plantRepository;

    @Mock
    private CareScheduleRepository careScheduleRepository;

    @Mock
    private LocationService locationService;

    @Mock
    private CareHistoryService careHistoryService;

    @Mock
    private TelegramClient telegramClient;

    private MainMenuService mainMenuService;

    private User testUser;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-05-22T12:00:00Z"), ZoneOffset.UTC);
        mainMenuService = new MainMenuService(
                plantRepository,
                careScheduleRepository,
                locationService,
                careHistoryService,
                fixedClock
        );

        // По умолчанию стрик 0 — не появится в шапке меню.
        lenient().when(careHistoryService.computeUserStreak(any(), any())).thenReturn(0);

        testUser = User.builder()
                .telegramChatId(123L)
                .timezone("Europe/Riga")
                .build();

        when(locationService.getUserLocations(any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("Показывает количество растений")
    void shouldDisplayPlantCount() throws TelegramApiException {
        when(plantRepository.countByUserIdAndArchivedAtIsNull(any())).thenReturn(5L);
        when(careScheduleRepository.findUserSchedulesDueBefore(any(), any())).thenReturn(List.of());

        mainMenuService.sendMainMenu(testUser, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        assertThat(captor.getValue().getText()).contains("Растений: 5");
    }

    @Test
    @DisplayName("Показывает 'Сегодня всё в порядке', если задач нет")
    void shouldShowAllClearIfNoTasksToday() throws TelegramApiException {
        when(plantRepository.countByUserIdAndArchivedAtIsNull(any())).thenReturn(2L);
        when(careScheduleRepository.findUserSchedulesDueBefore(any(), any())).thenReturn(List.of());

        mainMenuService.sendMainMenu(testUser, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        assertThat(captor.getValue().getText()).contains("Сегодня всё в порядке 🌱");
    }

    @Test
    @DisplayName("Показывает список задач на сегодня")
    void shouldShowTodayTasks() throws TelegramApiException {
        Plant plant1 = Plant.builder()
                .user(testUser)
                .name("Монстера")
                .build();

        Plant plant2 = Plant.builder()
                .user(testUser)
                .name("Фикус")
                .build();

        CareSchedule watering = CareSchedule.builder()
                .plant(plant1)
                .taskType(TaskType.WATERING)
                .intervalDays(7)
                .nextDueAt(LocalDateTime.now().minusHours(1))
                .active(true)
                .build();

        CareSchedule fertilizing = CareSchedule.builder()
                .plant(plant2)
                .taskType(TaskType.FERTILIZING)
                .intervalDays(30)
                .nextDueAt(LocalDateTime.now().minusHours(2))
                .active(true)
                .build();

        when(plantRepository.countByUserIdAndArchivedAtIsNull(any())).thenReturn(2L);
        when(careScheduleRepository.findUserSchedulesDueBefore(any(), any()))
                .thenReturn(List.of(watering, fertilizing));

        mainMenuService.sendMainMenu(testUser, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        String text = captor.getValue().getText();

        assertThat(text).contains("Сегодня нужно сделать");
        assertThat(text).contains("Монстера").contains("полить");
        assertThat(text).contains("Фикус").contains("удобрить");
        assertThat(text).doesNotContain("Сегодня всё в порядке");
    }

    @Test
    @DisplayName("Содержит основные inline-кнопки")
    void shouldContainMenuButtons() throws TelegramApiException {
        when(plantRepository.countByUserIdAndArchivedAtIsNull(any())).thenReturn(0L);
        when(careScheduleRepository.findUserSchedulesDueBefore(any(), any())).thenReturn(List.of());

        mainMenuService.sendMainMenu(testUser, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) captor.getValue().getReplyMarkup();

        List<String> buttonTexts = keyboard.getKeyboard().stream()
                .flatMap(Collection::stream)
                .map(InlineKeyboardButton::getText)
                .toList();

        assertThat(buttonTexts).contains("➕ Добавить растение");
        assertThat(buttonTexts).contains("📋 Все растения");
        assertThat(buttonTexts).contains("📍 Комнаты");
        assertThat(buttonTexts).contains("⚙️ Настройки");
    }

    @Test
    @DisplayName("callback_data кнопок корректные")
    void shouldHaveCorrectCallbackData() throws TelegramApiException {
        when(plantRepository.countByUserIdAndArchivedAtIsNull(any())).thenReturn(0L);
        when(careScheduleRepository.findUserSchedulesDueBefore(any(), any())).thenReturn(List.of());

        mainMenuService.sendMainMenu(testUser, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) captor.getValue().getReplyMarkup();

        List<String> callbacks = keyboard.getKeyboard().stream()
                .flatMap(Collection::stream)
                .map(InlineKeyboardButton::getCallbackData)
                .toList();

        assertThat(callbacks).contains(
                "MENU:ADD_PLANT",
                "MENU:ALL_PLANTS",
                "MENU:LOCATIONS",
                "MENU:SETTINGS"
        );
    }

    @Test
    @DisplayName("Используется parseMode Markdown")
    void shouldUseMarkdownParseMode() throws TelegramApiException {
        when(plantRepository.countByUserIdAndArchivedAtIsNull(any())).thenReturn(0L);
        when(careScheduleRepository.findUserSchedulesDueBefore(any(), any())).thenReturn(List.of());

        mainMenuService.sendMainMenu(testUser, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        assertThat(captor.getValue().getParseMode()).isEqualTo("Markdown");
    }

    @Test
    @DisplayName("TelegramApiException не выбрасывается наружу")
    void shouldHandleTelegramApiException() throws TelegramApiException {
        when(plantRepository.countByUserIdAndArchivedAtIsNull(any())).thenReturn(0L);
        when(careScheduleRepository.findUserSchedulesDueBefore(any(), any())).thenReturn(List.of());

        when(telegramClient.execute(any(SendMessage.class)))
                .thenThrow(new TelegramApiException("API Error"));

        mainMenuService.sendMainMenu(testUser, telegramClient);

        verify(telegramClient).execute(any(SendMessage.class));
    }

    @Test
    @DisplayName("Стрик ≥ 3 дня — показывается в шапке меню")
    void shouldShowStreakWhenAtLeastThreeDays() throws TelegramApiException {
        when(plantRepository.countByUserIdAndArchivedAtIsNull(any())).thenReturn(2L);
        when(careScheduleRepository.findUserSchedulesDueBefore(any(), any())).thenReturn(List.of());
        when(careHistoryService.computeUserStreak(any(), any())).thenReturn(5);

        mainMenuService.sendMainMenu(testUser, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        assertThat(captor.getValue().getText())
                .contains("🔥 Твой стрик: 5 дней");
    }

    @Test
    @DisplayName("Стрик < 3 дней — не показывается")
    void shouldHideStreakWhenBelowThreshold() throws TelegramApiException {
        when(plantRepository.countByUserIdAndArchivedAtIsNull(any())).thenReturn(2L);
        when(careScheduleRepository.findUserSchedulesDueBefore(any(), any())).thenReturn(List.of());
        when(careHistoryService.computeUserStreak(any(), any())).thenReturn(2);

        mainMenuService.sendMainMenu(testUser, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        assertThat(captor.getValue().getText()).doesNotContain("Твой стрик");
    }

    @Test
    @DisplayName("Склонение 'день' для разных значений стрика")
    void shouldPluralizeDaysCorrectly() throws TelegramApiException {
        when(plantRepository.countByUserIdAndArchivedAtIsNull(any())).thenReturn(0L);
        when(careScheduleRepository.findUserSchedulesDueBefore(any(), any())).thenReturn(List.of());

        // 21 → "день"
        when(careHistoryService.computeUserStreak(any(), any())).thenReturn(21);
        mainMenuService.sendMainMenu(testUser, telegramClient);

        // 22 → "дня"
        when(careHistoryService.computeUserStreak(any(), any())).thenReturn(22);
        mainMenuService.sendMainMenu(testUser, telegramClient);

        // 25 → "дней"
        when(careHistoryService.computeUserStreak(any(), any())).thenReturn(25);
        mainMenuService.sendMainMenu(testUser, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient, times(3)).execute(captor.capture());

        List<SendMessage> sent = captor.getAllValues();
        assertThat(sent.get(0).getText()).contains("21 день");
        assertThat(sent.get(1).getText()).contains("22 дня");
        assertThat(sent.get(2).getText()).contains("25 дней");
    }
}