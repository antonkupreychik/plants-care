package com.plantcare.bot.service;

import com.plantcare.bot.client.TelegramClientProvider;
import com.plantcare.bot.domain.CareSchedule;
import com.plantcare.bot.domain.NotificationDigest;
import com.plantcare.bot.domain.NotificationLog;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.NotificationType;
import com.plantcare.bot.domain.enums.TaskType;
import com.plantcare.bot.metrics.MetricsService;
import com.plantcare.bot.repository.CareScheduleRepository;
import com.plantcare.bot.repository.NotificationDigestRepository;
import com.plantcare.bot.repository.NotificationLogRepository;
import com.plantcare.bot.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Unit-тесты для NotificationSchedulerService")
class NotificationSchedulerServiceTest {

    @Mock
    private CareScheduleRepository careScheduleRepository;

    @Mock
    private NotificationLogRepository notificationLogRepository;

    @Mock
    private NotificationDigestRepository notificationDigestRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TelegramClientProvider telegramClientProvider;

    @Mock
    private TelegramClient telegramClient;

    @Mock
    private SchedulerHealthTracker schedulerHealthTracker;

    // Зависимости, добавленные после рефакторинга (issue #69 weather, #67 seasonal,
    // #118 quiet-hours/keyboard вынесены в отдельные компоненты). Моки нужны
    // потому что без них @InjectMocks вернёт null и тик упадёт в NPE на первом
    // же вызове isWeatherUsable/isQuiet/buildReminderKeyboard.
    @Mock
    private com.plantcare.bot.weather.service.WeatherService weatherService;

    @Mock
    private com.plantcare.bot.seasonal.service.SeasonalIntervalService seasonalIntervalService;

    @Mock
    private QuietHoursPolicy quietHoursPolicy;

    @Mock
    private MetricsService metricsService;

    /**
     * Реальный экземпляр, не мок: проверки в тестах смотрят на содержимое
     * клавиатуры (число кнопок, callback_data). Фабрика — pure-функция без
     * зависимостей, мокать смысла нет.
     */
    @org.mockito.Spy
    private ReminderKeyboardFactory reminderKeyboardFactory = new ReminderKeyboardFactory();

    /**
     * Реальный Clock с фиксированным моментом — quiet-hours проверка теперь
     * читает {@code clock.instant()} вместо передаваемого LocalDateTime
     * (фикс регрессии после #118, см. NotificationSchedulerService.shouldSend).
     */
    @org.mockito.Spy
    private Clock clock = Clock.fixed(Instant.parse("2026-05-24T12:00:00Z"), ZoneOffset.UTC);

    @InjectMocks
    private NotificationSchedulerService service;

    private User user;
    private Plant plant;
    private CareSchedule schedule;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .telegramChatId(100L)
                .timezone("Europe/Minsk")
                .quietHoursStart(LocalTime.of(0, 0))
                .quietHoursEnd(LocalTime.of(0, 0))
                .blocked(false)
                .build();

        plant = Plant.builder()
                .user(user)
                .name("Монстера")
                .build();

        schedule = CareSchedule.builder()
                .plant(plant)
                .taskType(TaskType.WATERING)
                .intervalDays(7)
                .nextDueAt(LocalDateTime.now().minusHours(1))
                .active(true)
                .build();

        ReflectionTestUtils.setField(user, "id", 1L);
        ReflectionTestUtils.setField(plant, "id", 10L);
        ReflectionTestUtils.setField(schedule, "id", 100L);

        // По умолчанию quiet-hours не активны (любой момент проходит фильтр).
        // Конкретные тесты QuietHours переопределяют через свой when(...).
        // Шедулер с #118-фикса вызывает Instant-перегрузку (см. shouldSend).
        when(quietHoursPolicy.isQuiet(any(User.class), any(Instant.class)))
                .thenReturn(false);
        // По умолчанию сезонная корректировка возвращает базовый интервал —
        // тесты, проверяющие сезонную логику, есть отдельно от этого набора.
        when(seasonalIntervalService.effectiveIntervalDays(any(), any(), any(Integer.class)))
                .thenAnswer(inv -> inv.getArgument(2, Integer.class));
    }

    @Nested
    @DisplayName("Одиночное уведомление")
    class SingleNotification {

        @Test
        @DisplayName("Если due-задача одна, отправляется обычное уведомление")
        void shouldSendSingleNotificationWhenOnlyOneDueSchedule() throws TelegramApiException {
            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                    .thenReturn(false);
            when(telegramClientProvider.getTelegramClient()).thenReturn(telegramClient);

            service.checkAndSendNotifications();

            ArgumentCaptor<SendMessage> messageCaptor = ArgumentCaptor.forClass(SendMessage.class);
            verify(telegramClient).execute(messageCaptor.capture());

            SendMessage sent = messageCaptor.getValue();

            assertThat(sent.getChatId()).isEqualTo("100");
            assertThat(sent.getText()).contains("Пора полить");
            assertThat(sent.getText()).contains("Монстера");
            assertThat(sent.getText()).doesNotContain("На сегодня:");

            verify(notificationDigestRepository, never()).save(any());
            verify(notificationLogRepository).save(any(NotificationLog.class));
            // Тик зафиксирован после успешной итерации (issue #28).
            verify(schedulerHealthTracker).recordTick();
        }

        @Test
        @DisplayName("recordTick вызывается даже когда расписаний нет (короткая итерация)")
        void shouldRecordTickEvenWhenNoSchedules() {
            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of());

            service.checkAndSendNotifications();

            verify(schedulerHealthTracker).recordTick();
        }

        @Test
        @DisplayName("recordTick НЕ вызывается, если findDueSchedules бросает (тик не доехал)")
        void shouldNotRecordTickWhenIterationFailsEarly() {
            when(careScheduleRepository.findDueSchedules(any()))
                    .thenThrow(new RuntimeException("DB hiccup"));

            try {
                service.checkAndSendNotifications();
            } catch (RuntimeException expected) {
                // ожидаемо, тик не завершился успешно
            }

            verify(schedulerHealthTracker, org.mockito.Mockito.never()).recordTick();
        }

        @Test
        @DisplayName("Текст уведомления зависит от типа задачи")
        void shouldUseCorrectTextForDifferentTaskTypes() throws TelegramApiException {
            schedule.setTaskType(TaskType.MISTING);

            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                    .thenReturn(false);
            when(telegramClientProvider.getTelegramClient()).thenReturn(telegramClient);

            service.checkAndSendNotifications();

            ArgumentCaptor<SendMessage> messageCaptor = ArgumentCaptor.forClass(SendMessage.class);
            verify(telegramClient).execute(messageCaptor.capture());

            assertThat(messageCaptor.getValue().getText()).contains("Пора опрыскать");
            assertThat(messageCaptor.getValue().getText()).contains("Монстера");
        }

        @Test
        @DisplayName("Кнопка done содержит правильный глагол для WATERING")
        void shouldUseWateringDoneButtonLabel() throws TelegramApiException {
            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                    .thenReturn(false);
            when(telegramClientProvider.getTelegramClient()).thenReturn(telegramClient);

            service.checkAndSendNotifications();

            ArgumentCaptor<SendMessage> messageCaptor = ArgumentCaptor.forClass(SendMessage.class);
            verify(telegramClient).execute(messageCaptor.capture());

            InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) messageCaptor.getValue().getReplyMarkup();

            List<String> buttonLabels = keyboard.getKeyboard().stream()
                    .flatMap(Collection::stream)
                    .map(InlineKeyboardButton::getText)
                    .toList();

            assertThat(buttonLabels).contains("✅ Полил");
        }

        @Test
        @DisplayName("Кнопка done содержит правильный глагол для MISTING")
        void shouldUseMistingDoneButtonLabel() throws TelegramApiException {
            schedule.setTaskType(TaskType.MISTING);

            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                    .thenReturn(false);
            when(telegramClientProvider.getTelegramClient()).thenReturn(telegramClient);

            service.checkAndSendNotifications();

            ArgumentCaptor<SendMessage> messageCaptor = ArgumentCaptor.forClass(SendMessage.class);
            verify(telegramClient).execute(messageCaptor.capture());

            InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) messageCaptor.getValue().getReplyMarkup();

            List<String> buttonLabels = keyboard.getKeyboard().stream()
                    .flatMap(Collection::stream)
                    .map(InlineKeyboardButton::getText)
                    .toList();

            assertThat(buttonLabels).contains("✅ Опрыскал");
        }

        @Test
        @DisplayName("Кнопка done содержит правильный глагол для FERTILIZING")
        void shouldUseFertilizingDoneButtonLabel() throws TelegramApiException {
            schedule.setTaskType(TaskType.FERTILIZING);

            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                    .thenReturn(false);
            when(telegramClientProvider.getTelegramClient()).thenReturn(telegramClient);

            service.checkAndSendNotifications();

            ArgumentCaptor<SendMessage> messageCaptor = ArgumentCaptor.forClass(SendMessage.class);
            verify(telegramClient).execute(messageCaptor.capture());

            InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) messageCaptor.getValue().getReplyMarkup();

            List<String> buttonLabels = keyboard.getKeyboard().stream()
                    .flatMap(Collection::stream)
                    .map(InlineKeyboardButton::getText)
                    .toList();

            assertThat(buttonLabels).contains("✅ Удобрил");
        }

        @Test
        @DisplayName("Обычное уведомление содержит кнопки done, snooze, skip")
        void shouldContainThreeInlineButtons() throws TelegramApiException {
            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                    .thenReturn(false);
            when(telegramClientProvider.getTelegramClient()).thenReturn(telegramClient);

            service.checkAndSendNotifications();

            ArgumentCaptor<SendMessage> messageCaptor = ArgumentCaptor.forClass(SendMessage.class);
            verify(telegramClient).execute(messageCaptor.capture());

            InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) messageCaptor.getValue().getReplyMarkup();

            List<String> callbackData = keyboard.getKeyboard().stream()
                    .flatMap(Collection::stream)
                    .map(InlineKeyboardButton::getCallbackData)
                    .toList();

            assertThat(callbackData).contains("v1:done:100");
            assertThat(callbackData).contains("v1:snooze:100");
            assertThat(callbackData).contains("v1:skip:100");
        }

        @Test
        @DisplayName("После отправки обычного уведомления создаётся запись в notifications_log")
        void shouldSaveNotificationLog() throws TelegramApiException {
            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                    .thenReturn(false);
            when(telegramClientProvider.getTelegramClient()).thenReturn(telegramClient);

            service.checkAndSendNotifications();

            ArgumentCaptor<NotificationLog> logCaptor = ArgumentCaptor.forClass(NotificationLog.class);
            verify(notificationLogRepository).save(logCaptor.capture());

            NotificationLog saved = logCaptor.getValue();

            assertThat(saved.getPlant()).isEqualTo(plant);
            assertThat(saved.getTaskType()).isEqualTo(TaskType.WATERING);
            assertThat(saved.getNotificationType()).isEqualTo(NotificationType.DUE);
        }
    }

    @Nested
    @DisplayName("Дайджест уведомлений")
    class DigestNotifications {

        @Test
        @DisplayName("Если у одного пользователя 3 due-задачи, отправляется один digest")
        void shouldSendDigestWhenUserHasSeveralDueSchedules() throws TelegramApiException {
            CareSchedule schedule2 = CareSchedule.builder()
                    .plant(plant)
                    .taskType(TaskType.MISTING)
                    .intervalDays(3)
                    .nextDueAt(LocalDateTime.now().minusHours(1))
                    .active(true)
                    .build();

            CareSchedule schedule3 = CareSchedule.builder()
                    .plant(plant)
                    .taskType(TaskType.FERTILIZING)
                    .intervalDays(30)
                    .nextDueAt(LocalDateTime.now().minusHours(1))
                    .active(true)
                    .build();

            ReflectionTestUtils.setField(schedule2, "id", 101L);
            ReflectionTestUtils.setField(schedule3, "id", 102L);

            NotificationDigest savedDigest = NotificationDigest.builder()
                    .userId(user.getId())
                    .plantTaskIds(List.of())
                    .build();

            ReflectionTestUtils.setField(savedDigest, "id", 500L);

            when(careScheduleRepository.findDueSchedules(any()))
                    .thenReturn(List.of(schedule, schedule2, schedule3));
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                    .thenReturn(false);
            when(notificationDigestRepository.save(any(NotificationDigest.class)))
                    .thenReturn(savedDigest);
            when(telegramClientProvider.getTelegramClient()).thenReturn(telegramClient);

            service.checkAndSendNotifications();

            ArgumentCaptor<SendMessage> messageCaptor = ArgumentCaptor.forClass(SendMessage.class);
            verify(telegramClient).execute(messageCaptor.capture());

            SendMessage sent = messageCaptor.getValue();

            assertThat(sent.getChatId()).isEqualTo("100");
            assertThat(sent.getText()).contains("На сегодня:");
            assertThat(sent.getText()).contains("Монстера — полить");
            assertThat(sent.getText()).contains("Монстера — опрыскать");
            assertThat(sent.getText()).contains("Монстера — удобрить");

            InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) sent.getReplyMarkup();

            List<String> callbacks = keyboard.getKeyboard().stream()
                    .flatMap(Collection::stream)
                    .map(InlineKeyboardButton::getCallbackData)
                    .toList();

            assertThat(callbacks).contains("digest:done_all:500");
            assertThat(callbacks).contains("digest:expand:500");

            verify(notificationDigestRepository).save(any(NotificationDigest.class));
            verify(notificationLogRepository, times(3)).save(any(NotificationLog.class));
        }

        @Test
        @DisplayName("В NotificationDigest сохраняются все задачи пользователя")
        void shouldSaveDigestWithAllTasks() throws TelegramApiException {
            CareSchedule schedule2 = CareSchedule.builder()
                    .plant(plant)
                    .taskType(TaskType.MISTING)
                    .intervalDays(3)
                    .nextDueAt(LocalDateTime.now().minusHours(1))
                    .active(true)
                    .build();

            ReflectionTestUtils.setField(schedule2, "id", 101L);

            NotificationDigest savedDigest = NotificationDigest.builder()
                    .userId(user.getId())
                    .plantTaskIds(List.of())
                    .build();

            ReflectionTestUtils.setField(savedDigest, "id", 500L);

            when(careScheduleRepository.findDueSchedules(any()))
                    .thenReturn(List.of(schedule, schedule2));
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                    .thenReturn(false);
            when(notificationDigestRepository.save(any(NotificationDigest.class)))
                    .thenReturn(savedDigest);
            when(telegramClientProvider.getTelegramClient()).thenReturn(telegramClient);

            service.checkAndSendNotifications();

            ArgumentCaptor<NotificationDigest> digestCaptor = ArgumentCaptor.forClass(NotificationDigest.class);
            verify(notificationDigestRepository).save(digestCaptor.capture());

            NotificationDigest digest = digestCaptor.getValue();

            assertThat(digest.getUserId()).isEqualTo(1L);
            assertThat(digest.getPlantTaskIds()).hasSize(2);
            assertThat(digest.getPlantTaskIds().get(0).scheduleId()).isEqualTo(100L);
            assertThat(digest.getPlantTaskIds().get(0).plantId()).isEqualTo(10L);
            assertThat(digest.getPlantTaskIds().get(0).plantName()).isEqualTo("Монстера");
            assertThat(digest.getPlantTaskIds().get(0).taskType()).isEqualTo(TaskType.WATERING);
            assertThat(digest.getPlantTaskIds().get(1).scheduleId()).isEqualTo(101L);
            assertThat(digest.getPlantTaskIds().get(1).taskType()).isEqualTo(TaskType.MISTING);
        }

        @Test
        @DisplayName("Если у разных пользователей по одной задаче, digest не создаётся")
        void shouldNotCreateDigestForDifferentUsersWithSingleTaskEach() throws TelegramApiException {
            User secondUser = User.builder()
                    .telegramChatId(200L)
                    .timezone("Europe/Minsk")
                    .quietHoursStart(LocalTime.of(0, 0))
                    .quietHoursEnd(LocalTime.of(0, 0))
                    .blocked(false)
                    .build();

            Plant secondPlant = Plant.builder()
                    .user(secondUser)
                    .name("Фикус")
                    .build();

            CareSchedule secondSchedule = CareSchedule.builder()
                    .plant(secondPlant)
                    .taskType(TaskType.MISTING)
                    .intervalDays(3)
                    .nextDueAt(LocalDateTime.now().minusHours(1))
                    .active(true)
                    .build();

            ReflectionTestUtils.setField(secondUser, "id", 2L);
            ReflectionTestUtils.setField(secondPlant, "id", 20L);
            ReflectionTestUtils.setField(secondSchedule, "id", 200L);

            when(careScheduleRepository.findDueSchedules(any()))
                    .thenReturn(List.of(schedule, secondSchedule));
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                    .thenReturn(false);
            when(telegramClientProvider.getTelegramClient()).thenReturn(telegramClient);

            service.checkAndSendNotifications();

            verify(telegramClient, times(2)).execute(any(SendMessage.class));
            verify(notificationDigestRepository, never()).save(any());
            verify(notificationLogRepository, times(2)).save(any(NotificationLog.class));
        }

        @Test
        @DisplayName("Если у первого пользователя 2 задачи, а у второго 1, первый получает digest, второй обычное уведомление")
        void shouldSendDigestForOneUserAndSingleNotificationForAnother() throws TelegramApiException {
            CareSchedule schedule2 = CareSchedule.builder()
                    .plant(plant)
                    .taskType(TaskType.MISTING)
                    .intervalDays(3)
                    .nextDueAt(LocalDateTime.now().minusHours(1))
                    .active(true)
                    .build();

            User secondUser = User.builder()
                    .telegramChatId(200L)
                    .timezone("Europe/Minsk")
                    .quietHoursStart(LocalTime.of(0, 0))
                    .quietHoursEnd(LocalTime.of(0, 0))
                    .blocked(false)
                    .build();

            Plant secondPlant = Plant.builder()
                    .user(secondUser)
                    .name("Фикус")
                    .build();

            CareSchedule secondUserSchedule = CareSchedule.builder()
                    .plant(secondPlant)
                    .taskType(TaskType.WATERING)
                    .intervalDays(5)
                    .nextDueAt(LocalDateTime.now().minusHours(1))
                    .active(true)
                    .build();

            ReflectionTestUtils.setField(schedule2, "id", 101L);
            ReflectionTestUtils.setField(secondUser, "id", 2L);
            ReflectionTestUtils.setField(secondPlant, "id", 20L);
            ReflectionTestUtils.setField(secondUserSchedule, "id", 200L);

            NotificationDigest savedDigest = NotificationDigest.builder()
                    .userId(user.getId())
                    .plantTaskIds(List.of())
                    .build();

            ReflectionTestUtils.setField(savedDigest, "id", 500L);

            when(careScheduleRepository.findDueSchedules(any()))
                    .thenReturn(List.of(schedule, schedule2, secondUserSchedule));
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                    .thenReturn(false);
            when(notificationDigestRepository.save(any(NotificationDigest.class)))
                    .thenReturn(savedDigest);
            when(telegramClientProvider.getTelegramClient()).thenReturn(telegramClient);

            service.checkAndSendNotifications();

            ArgumentCaptor<SendMessage> messageCaptor = ArgumentCaptor.forClass(SendMessage.class);
            verify(telegramClient, times(2)).execute(messageCaptor.capture());

            List<SendMessage> sentMessages = messageCaptor.getAllValues();

            assertThat(sentMessages)
                    .anyMatch(message -> message.getChatId().equals("100")
                            && message.getText().contains("На сегодня:"));

            assertThat(sentMessages)
                    .anyMatch(message -> message.getChatId().equals("200")
                            && message.getText().contains("Пора полить")
                            && message.getText().contains("Фикус"));

            verify(notificationDigestRepository).save(any(NotificationDigest.class));
            verify(notificationLogRepository, times(3)).save(any(NotificationLog.class));
        }
    }

    @Nested
    @DisplayName("Фильтрация: пауза пользователя")
    class PausedUser {

        @Test
        @DisplayName("Не отправляет уведомление, если pausedUntil > now")
        void shouldSkipPausedUser() throws TelegramApiException {
            user.setPausedUntil(LocalDateTime.now().plusHours(1));

            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));

            service.checkAndSendNotifications();

            verify(telegramClient, never()).execute(any(SendMessage.class));
            verify(notificationDigestRepository, never()).save(any());
            verify(notificationLogRepository, never()).save(any());
        }

        @Test
        @DisplayName("Отправляет уведомление, если pausedUntil уже прошло")
        void shouldSendIfPauseExpired() throws TelegramApiException {
            user.setPausedUntil(LocalDateTime.now().minusHours(1));

            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                    .thenReturn(false);
            when(telegramClientProvider.getTelegramClient()).thenReturn(telegramClient);

            service.checkAndSendNotifications();

            verify(telegramClient).execute(any(SendMessage.class));
        }
    }

    @Nested
    @DisplayName("Фильтрация: тихие часы")
    class QuietHours {

        @Test
        @DisplayName("Не отправляет уведомление во время тихих часов")
        void shouldSkipDuringQuietHours() throws TelegramApiException {
            user.setQuietHoursStart(LocalTime.of(0, 0));
            user.setQuietHoursEnd(LocalTime.of(23, 59));

            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));
            // С #118-фикса quiet-проверка идёт через Instant-перегрузку.
            when(quietHoursPolicy.isQuiet(any(User.class), any(Instant.class)))
                    .thenReturn(true);

            service.checkAndSendNotifications();

            verify(telegramClient, never()).execute(any(SendMessage.class));
            verify(notificationDigestRepository, never()).save(any());
            verify(notificationLogRepository, never()).save(any());
        }

        @Test
        @DisplayName("Отправляет уведомление, если quiet_hours_start == quiet_hours_end")
        void shouldSendWhenQuietHoursDisabled() throws TelegramApiException {
            user.setQuietHoursStart(LocalTime.of(0, 0));
            user.setQuietHoursEnd(LocalTime.of(0, 0));

            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                    .thenReturn(false);
            when(telegramClientProvider.getTelegramClient()).thenReturn(telegramClient);

            service.checkAndSendNotifications();

            verify(telegramClient).execute(any(SendMessage.class));
        }
    }

    @Nested
    @DisplayName("Фильтрация: дедупликация")
    class Deduplication {

        @Test
        @DisplayName("Не отправляет уведомление, если за последние 12 часов уже отправляли")
        void shouldSkipIfAlreadyNotifiedRecently() throws TelegramApiException {
            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                    .thenReturn(true);

            service.checkAndSendNotifications();

            verify(telegramClient, never()).execute(any(SendMessage.class));
            verify(notificationDigestRepository, never()).save(any());
            verify(notificationLogRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Ошибки Telegram")
    class TelegramErrors {

        @Test
        @DisplayName("При 403 помечает пользователя как заблокированного")
        void shouldMarkUserAsBlockedOn403() throws TelegramApiException {
            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                    .thenReturn(false);
            when(telegramClientProvider.getTelegramClient()).thenReturn(telegramClient);
            when(telegramClient.execute(any(SendMessage.class)))
                    .thenThrow(new TelegramApiException("Forbidden: bot was blocked by the user [403]"));

            service.checkAndSendNotifications();

            assertThat(user.isBlocked()).isTrue();

            verify(userRepository).save(user);
            verify(notificationLogRepository, never()).save(any());
        }

        @Test
        @DisplayName("При прочих ошибках пользователь не блокируется")
        void shouldNotBlockUserOnOtherErrors() throws TelegramApiException {
            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                    .thenReturn(false);
            when(telegramClientProvider.getTelegramClient()).thenReturn(telegramClient);
            when(telegramClient.execute(any(SendMessage.class)))
                    .thenThrow(new TelegramApiException("Network timeout"));

            service.checkAndSendNotifications();

            assertThat(user.isBlocked()).isFalse();

            verify(userRepository, never()).save(any());
            verify(notificationLogRepository, never()).save(any());
        }

        @Test
        @DisplayName("Ошибка у одного пользователя не останавливает обработку другого")
        void shouldContinueProcessingAfterError() throws TelegramApiException {
            User secondUser = User.builder()
                    .telegramChatId(200L)
                    .timezone("Europe/Minsk")
                    .quietHoursStart(LocalTime.of(0, 0))
                    .quietHoursEnd(LocalTime.of(0, 0))
                    .blocked(false)
                    .build();

            Plant secondPlant = Plant.builder()
                    .user(secondUser)
                    .name("Фикус")
                    .build();

            CareSchedule secondSchedule = CareSchedule.builder()
                    .plant(secondPlant)
                    .taskType(TaskType.MISTING)
                    .intervalDays(3)
                    .nextDueAt(LocalDateTime.now().minusHours(1))
                    .active(true)
                    .build();

            ReflectionTestUtils.setField(secondUser, "id", 2L);
            ReflectionTestUtils.setField(secondPlant, "id", 20L);
            ReflectionTestUtils.setField(secondSchedule, "id", 200L);

            when(careScheduleRepository.findDueSchedules(any()))
                    .thenReturn(List.of(schedule, secondSchedule));
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                    .thenReturn(false);
            when(telegramClientProvider.getTelegramClient()).thenReturn(telegramClient);
            when(telegramClient.execute(any(SendMessage.class)))
                    .thenThrow(new TelegramApiException("error"))
                    .thenReturn(null);

            service.checkAndSendNotifications();

            verify(telegramClient, times(2)).execute(any(SendMessage.class));
        }
    }

    @Nested
    @DisplayName("Нет просроченных расписаний")
    class NoDueSchedules {

        @Test
        @DisplayName("Не отправляет ничего, если нет просроченных расписаний")
        void shouldDoNothingWhenNoDueSchedules() throws TelegramApiException {
            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of());

            service.checkAndSendNotifications();

            verify(telegramClient, never()).execute(any(SendMessage.class));
            verify(notificationDigestRepository, never()).save(any());
            verify(notificationLogRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Метрики (#115)")
    class Metrics {

        @Test
        @DisplayName("Каждый тик оборачивается в Timer.Sample (start + stop) даже без расписаний")
        void should_wrap_each_tick_in_timer_sample_when_called() {
            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of());
            io.micrometer.core.instrument.Timer.Sample sample =
                    mock(io.micrometer.core.instrument.Timer.Sample.class);
            when(metricsService.startSchedulerTickTimer()).thenReturn(sample);

            service.checkAndSendNotifications();

            verify(metricsService).startSchedulerTickTimer();
            verify(metricsService).stopSchedulerTickTimer(sample);
        }

        @Test
        @DisplayName("Timer.stop вызывается даже если tick упал с исключением (finally)")
        void should_stop_timer_when_tick_fails_with_exception() {
            io.micrometer.core.instrument.Timer.Sample sample =
                    mock(io.micrometer.core.instrument.Timer.Sample.class);
            when(metricsService.startSchedulerTickTimer()).thenReturn(sample);
            when(careScheduleRepository.findDueSchedules(any()))
                    .thenThrow(new RuntimeException("DB down"));

            try {
                service.checkAndSendNotifications();
            } catch (RuntimeException expected) {
                // ожидаемо
            }

            verify(metricsService).startSchedulerTickTimer();
            verify(metricsService).stopSchedulerTickTimer(sample);
        }

        @Test
        @DisplayName("После успешной отправки одиночного уведомления увеличивается notifications.sent")
        void should_record_notification_sent_when_single_push_succeeds() throws TelegramApiException {
            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                    .thenReturn(false);
            when(telegramClientProvider.getTelegramClient()).thenReturn(telegramClient);

            service.checkAndSendNotifications();

            verify(metricsService).recordNotificationSent(MetricsService.CHANNEL_TELEGRAM, TaskType.WATERING);
            verify(metricsService, never()).recordNotificationFailed(any(), any());
        }

        @Test
        @DisplayName("При 429 от Telegram пишутся telegram.api.errors[429] и notifications.failed[rate_limit]")
        void should_record_rate_limit_failure_when_telegram_returns_429() throws TelegramApiException {
            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                    .thenReturn(false);
            when(telegramClientProvider.getTelegramClient()).thenReturn(telegramClient);
            when(telegramClient.execute(any(SendMessage.class)))
                    .thenThrow(new TelegramApiException("[429] Too Many Requests: retry after 30"));

            service.checkAndSendNotifications();

            verify(metricsService).recordTelegramApiError(MetricsService.TelegramErrorCode.RATE_LIMITED);
            verify(metricsService).recordNotificationFailed(
                    MetricsService.CHANNEL_TELEGRAM, MetricsService.FailureReason.RATE_LIMIT);
            verify(metricsService, never()).recordNotificationSent(any(), any());
        }

        @Test
        @DisplayName("При 403 от Telegram пишутся telegram.api.errors[403] и notifications.failed[blocked]")
        void should_record_blocked_failure_when_telegram_returns_403() throws TelegramApiException {
            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                    .thenReturn(false);
            when(telegramClientProvider.getTelegramClient()).thenReturn(telegramClient);
            when(telegramClient.execute(any(SendMessage.class)))
                    .thenThrow(new TelegramApiException("Forbidden: bot was blocked by the user [403]"));

            service.checkAndSendNotifications();

            verify(metricsService).recordTelegramApiError(MetricsService.TelegramErrorCode.FORBIDDEN);
            verify(metricsService).recordNotificationFailed(
                    MetricsService.CHANNEL_TELEGRAM, MetricsService.FailureReason.BLOCKED);
        }

        @Test
        @DisplayName("При прочей ошибке Telegram пишется failed[other]")
        void should_record_other_failure_when_telegram_throws_unknown_error() throws TelegramApiException {
            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                    .thenReturn(false);
            when(telegramClientProvider.getTelegramClient()).thenReturn(telegramClient);
            when(telegramClient.execute(any(SendMessage.class)))
                    .thenThrow(new TelegramApiException("Network timeout"));

            service.checkAndSendNotifications();

            verify(metricsService).recordTelegramApiError(MetricsService.TelegramErrorCode.OTHER);
            verify(metricsService).recordNotificationFailed(
                    MetricsService.CHANNEL_TELEGRAM, MetricsService.FailureReason.OTHER);
        }

        @Test
        @DisplayName("Дайджест из 2 задач: 2x recordNotificationSent + 1x recordDigestSent")
        void should_record_digest_sent_and_per_task_sent_when_digest_pushed() throws TelegramApiException {
            CareSchedule schedule2 = CareSchedule.builder()
                    .plant(plant)
                    .taskType(TaskType.MISTING)
                    .intervalDays(3)
                    .nextDueAt(LocalDateTime.now().minusHours(1))
                    .active(true)
                    .build();
            ReflectionTestUtils.setField(schedule2, "id", 101L);

            NotificationDigest savedDigest = NotificationDigest.builder()
                    .userId(user.getId())
                    .plantTaskIds(List.of())
                    .build();
            ReflectionTestUtils.setField(savedDigest, "id", 500L);

            when(careScheduleRepository.findDueSchedules(any()))
                    .thenReturn(List.of(schedule, schedule2));
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                    .thenReturn(false);
            when(notificationDigestRepository.save(any(NotificationDigest.class)))
                    .thenReturn(savedDigest);
            when(telegramClientProvider.getTelegramClient()).thenReturn(telegramClient);

            service.checkAndSendNotifications();

            verify(metricsService).recordNotificationSent(MetricsService.CHANNEL_TELEGRAM, TaskType.WATERING);
            verify(metricsService).recordNotificationSent(MetricsService.CHANNEL_TELEGRAM, TaskType.MISTING);
            verify(metricsService).recordDigestSent();
        }
    }
}