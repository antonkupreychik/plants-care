package com.plantcare.bot.command.impl;

import com.plantcare.bot.domain.CareSchedule;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.TaskType;
import com.plantcare.bot.repository.CareScheduleRepository;
import com.plantcare.bot.repository.PlantRepository;
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
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Unit-тесты для MenuCommand (/menu)")
class MenuCommandTest {

    @Mock private TelegramClient telegramClient;
    @Mock private Update update;
    @Mock private Message message;
    @Mock private UserService userService;
    @Mock private PlantRepository plantRepository;
    @Mock private CareScheduleRepository careScheduleRepository;

    @InjectMocks
    private MenuCommand menuCommand;

    private final Long chatId = 123L;
    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .telegramChatId(chatId)
                .timezone("Europe/Moscow")
                .build();

        when(update.getMessage()).thenReturn(message);
        when(message.getChatId()).thenReturn(chatId);
        when(userService.findByChatId(chatId)).thenReturn(Optional.of(testUser));
    }

    @Test
    @DisplayName("Команда зарегистрирована как /menu")
    void shouldHaveCorrectCommandName() {
        assertThat(menuCommand.getCommandName()).isEqualTo("/menu");
    }

    @Test
    @DisplayName("Показывает количество растений")
    void shouldDisplayPlantCount() throws TelegramApiException {
        when(plantRepository.countByUserIdAndArchivedAtIsNull(any())).thenReturn(5L);
        when(careScheduleRepository.findUserSchedulesDueBefore(any(), any())).thenReturn(List.of());

        menuCommand.execute(update, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        assertThat(captor.getValue().getText()).contains("Растений: 5");
    }

    @Test
    @DisplayName("Показывает 'Сегодня всё в порядке' если задач нет")
    void shouldShowAllClearIfNoTasksToday() throws TelegramApiException {
        when(plantRepository.countByUserIdAndArchivedAtIsNull(any())).thenReturn(2L);
        when(careScheduleRepository.findUserSchedulesDueBefore(any(), any())).thenReturn(List.of());

        menuCommand.execute(update, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        assertThat(captor.getValue().getText()).contains("Сегодня всё в порядке 🌱");
    }

    @Test
    @DisplayName("Показывает список задач на сегодня")
    void shouldShowTodayTasks() throws TelegramApiException {
        Plant plant1 = Plant.builder().user(testUser).name("Монстера").build();
        Plant plant2 = Plant.builder().user(testUser).name("Фикус").build();

        CareSchedule s1 = CareSchedule.builder()
                .plant(plant1).taskType(TaskType.WATERING).intervalDays(7)
                .nextDueAt(LocalDateTime.now().minusHours(1)).active(true).build();
        CareSchedule s2 = CareSchedule.builder()
                .plant(plant2).taskType(TaskType.FERTILIZING).intervalDays(30)
                .nextDueAt(LocalDateTime.now().minusHours(2)).active(true).build();

        when(plantRepository.countByUserIdAndArchivedAtIsNull(any())).thenReturn(2L);
        when(careScheduleRepository.findUserSchedulesDueBefore(any(), any())).thenReturn(List.of(s1, s2));

        menuCommand.execute(update, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        String text = captor.getValue().getText();
        assertThat(text).contains("Сегодня нужно сделать");
        assertThat(text).contains("Монстера").contains("полить");
        assertThat(text).contains("Фикус").contains("удобрить");
        assertThat(text).doesNotContain("Сегодня всё в порядке");
    }

    @Test
    @DisplayName("Содержит inline-кнопки: Добавить растение, Все растения, Настройки")
    void shouldContainMenuButtons() throws TelegramApiException {
        when(plantRepository.countByUserIdAndArchivedAtIsNull(any())).thenReturn(0L);
        when(careScheduleRepository.findUserSchedulesDueBefore(any(), any())).thenReturn(List.of());

        menuCommand.execute(update, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) captor.getValue().getReplyMarkup();
        List<String> buttonTexts = keyboard.getKeyboard().stream()
                .flatMap(Collection::stream)
                .map(InlineKeyboardButton::getText)
                .toList();

        assertThat(buttonTexts).contains("➕ Добавить растение");
        assertThat(buttonTexts).contains("📋 Все растения");
        assertThat(buttonTexts).contains("⚙️ Настройки");
    }

    @Test
    @DisplayName("callback_data кнопок начинаются с MENU:")
    void shouldHaveCorrectCallbackData() throws TelegramApiException {
        when(plantRepository.countByUserIdAndArchivedAtIsNull(any())).thenReturn(0L);
        when(careScheduleRepository.findUserSchedulesDueBefore(any(), any())).thenReturn(List.of());

        menuCommand.execute(update, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) captor.getValue().getReplyMarkup();
        List<String> callbacks = keyboard.getKeyboard().stream()
                .flatMap(Collection::stream)
                .map(InlineKeyboardButton::getCallbackData)
                .toList();

        assertThat(callbacks).contains("MENU:ADD_PLANT", "MENU:ALL_PLANTS", "MENU:SETTINGS");
    }

    @Test
    @DisplayName("Используется parseMode Markdown")
    void shouldUseMarkdownParseMode() throws TelegramApiException {
        when(plantRepository.countByUserIdAndArchivedAtIsNull(any())).thenReturn(0L);
        when(careScheduleRepository.findUserSchedulesDueBefore(any(), any())).thenReturn(List.of());

        menuCommand.execute(update, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        assertThat(captor.getValue().getParseMode()).isEqualTo("Markdown");
    }

    @Test
    @DisplayName("Обрабатывает TelegramApiException без выброса наружу")
    void shouldHandleTelegramApiException() throws TelegramApiException {
        when(plantRepository.countByUserIdAndArchivedAtIsNull(any())).thenReturn(0L);
        when(careScheduleRepository.findUserSchedulesDueBefore(any(), any())).thenReturn(List.of());
        when(telegramClient.execute(any(SendMessage.class)))
                .thenThrow(new TelegramApiException("API Error"));

        menuCommand.execute(update, telegramClient);

        verify(telegramClient).execute(any(SendMessage.class));
    }
}