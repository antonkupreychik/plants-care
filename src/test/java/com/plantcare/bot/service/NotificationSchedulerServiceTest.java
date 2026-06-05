package com.plantcare.bot.service;

import com.plantcare.core.service.QuietHoursPolicy;
import com.plantcare.core.service.SchedulerHealthTracker;

import com.plantcare.core.domain.CareSchedule;
import com.plantcare.core.domain.NotificationDigest;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.metrics.MetricsService;
import com.plantcare.core.repository.CareScheduleRepository;
import com.plantcare.core.repository.NotificationDigestRepository;
import com.plantcare.core.repository.NotificationLogRepository;
import com.plantcare.core.repository.UserRepository;
import com.plantcare.bot.telegram.RateLimitedTelegramSender;
import com.plantcare.bot.telegram.SendCallbacks;
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

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты {@link NotificationSchedulerService} после перевода отправки на
 * rate-limited очередь (issue #29). Шедулер больше не зовёт Telegram напрямую —
 * он кладёт {@link SendMessage} в {@link RateLimitedTelegramSender#enqueue}, а
 * bookkeeping (дедуп-лог, метрики, пометка blocked) выполняется в колбэках
 * {@link SendCallbacks}, делегирующих в {@link NotificationDeliveryCallbacks}.
 *
 * <p>Тесты ловят: (1) что именно ставится в очередь (текст/кнопки/получатель),
 * (2) фильтры shouldSend (pause / quiet-hours / дедуп) до постановки в очередь,
 * (3) маршрутизацию колбэков — onSuccess → onSent/onDigestSent, onFailure → onFailed.
 *
 * <p>Дедуп-проверка считает quiet-hours в TZ юзера (Europe/Minsk) — таймзонный
 * кейс сохранён через QuietHoursPolicy-перегрузку на Instant из фиксированного Clock.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Unit-тесты для NotificationSchedulerService (issue #29)")
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
    private SchedulerHealthTracker schedulerHealthTracker;

    @Mock
    private com.plantcare.core.weather.service.WeatherService weatherService;

    @Mock
    private com.plantcare.core.seasonal.service.SeasonalIntervalService seasonalIntervalService;

    @Mock
    private QuietHoursPolicy quietHoursPolicy;

    @Mock
    private MetricsService metricsService;

    @Mock
    private RateLimitedTelegramSender telegramSender;

    @Mock
    private NotificationDeliveryCallbacks deliveryCallbacks;

    @Mock
    private com.plantcare.core.service.LocationSharingService locationSharingService;

    /**
     * Реальный экземпляр, не мок: проверки в тестах смотрят на содержимое
     * клавиатуры (число кнопок, callback_data). Фабрика — pure-функция без
     * зависимостей, мокать смысла нет.
     */
    @org.mockito.Spy
    private ReminderKeyboardFactory reminderKeyboardFactory = new ReminderKeyboardFactory();

    /**
     * Реальный Clock с фиксированным моментом — quiet-hours проверка читает
     * {@code clock.instant()} (фикс регрессии после #118).
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

        // По умолчанию quiet-hours не активны. Шедулер с #118-фикса вызывает
        // Instant-перегрузку (см. shouldSend).
        when(quietHoursPolicy.isQuiet(any(User.class), any(Instant.class)))
                .thenReturn(false);
        when(seasonalIntervalService.effectiveIntervalDays(any(), any(), any(Integer.class)))
                .thenAnswer(inv -> inv.getArgument(2, Integer.class));
    }

    /** Захватывает SendCallbacks из единственного enqueue с колбэками. */
    private SendCallbacks captureCallbacks() {
        ArgumentCaptor<SendCallbacks> captor = ArgumentCaptor.forClass(SendCallbacks.class);
        verify(telegramSender).enqueue(any(SendMessage.class), captor.capture());
        return captor.getValue();
    }

    /** Эмулирует вызов onSuccess воркером очереди (с null-guard, как в проде). */
    private static void runSuccess(SendCallbacks callbacks) {
        if (callbacks.onSuccess() != null) {
            callbacks.onSuccess().run();
        }
    }

    /** Эмулирует вызов onFailure воркером очереди (с null-guard, как в проде). */
    private static void runFailure(SendCallbacks callbacks, TelegramApiException e) {
        if (callbacks.onFailure() != null) {
            callbacks.onFailure().accept(e);
        }
    }

    @Nested
    @DisplayName("Одиночное уведомление")
    class SingleNotification {

        @Test
        @DisplayName("Если due-задача одна, в очередь ставится обычное уведомление")
        void shouldEnqueueSingleNotificationWhenOnlyOneDueSchedule() {
            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                    .thenReturn(false);

            service.checkAndSendNotifications();

            ArgumentCaptor<SendMessage> messageCaptor = ArgumentCaptor.forClass(SendMessage.class);
            verify(telegramSender).enqueue(messageCaptor.capture(), any(SendCallbacks.class));

            SendMessage sent = messageCaptor.getValue();
            assertThat(sent.getChatId()).isEqualTo("100");
            assertThat(sent.getText()).contains("Пора полить");
            assertThat(sent.getText()).contains("Монстера");
            assertThat(sent.getText()).doesNotContain("На сегодня:");

            verify(notificationDigestRepository, never()).save(any());
            // Тик зафиксирован после успешной итерации (issue #28).
            verify(schedulerHealthTracker).recordTick();
        }

        @Test
        @DisplayName("onSuccess колбэк пишет дедуп-лог через NotificationDeliveryCallbacks.onSent")
        void shouldRouteSuccessCallbackToOnSent() {
            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                    .thenReturn(false);

            service.checkAndSendNotifications();

            SendCallbacks callbacks = captureCallbacks();
            // До выполнения колбэка bookkeeping не трогается.
            verify(deliveryCallbacks, never()).onSent(anyLong(), any());

            runSuccess(callbacks);

            verify(deliveryCallbacks).onSent(plant.getId(), TaskType.WATERING);
        }

        @Test
        @DisplayName("onFailure колбэк делегирует в NotificationDeliveryCallbacks.onFailed с chatId")
        void shouldRouteFailureCallbackToOnFailed() {
            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                    .thenReturn(false);

            service.checkAndSendNotifications();

            SendCallbacks callbacks = captureCallbacks();
            TelegramApiException error =
                    new TelegramApiException("Forbidden: bot was blocked by the user [403]");

            runFailure(callbacks, error);

            verify(deliveryCallbacks).onFailed(user.getTelegramChatId(), error);
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

            verify(schedulerHealthTracker, never()).recordTick();
        }

        @Test
        @DisplayName("Текст уведомления зависит от типа задачи")
        void shouldUseCorrectTextForDifferentTaskTypes() {
            schedule.setTaskType(TaskType.MISTING);

            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                    .thenReturn(false);

            service.checkAndSendNotifications();

            ArgumentCaptor<SendMessage> messageCaptor = ArgumentCaptor.forClass(SendMessage.class);
            verify(telegramSender).enqueue(messageCaptor.capture(), any(SendCallbacks.class));

            assertThat(messageCaptor.getValue().getText()).contains("Пора опрыскать");
            assertThat(messageCaptor.getValue().getText()).contains("Монстера");
        }

        @Test
        @DisplayName("Кнопка done содержит правильный глагол для WATERING")
        void shouldUseWateringDoneButtonLabel() {
            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                    .thenReturn(false);

            service.checkAndSendNotifications();

            assertThat(buttonLabels()).contains("✅ Полил");
        }

        @Test
        @DisplayName("Кнопка done содержит правильный глагол для MISTING")
        void shouldUseMistingDoneButtonLabel() {
            schedule.setTaskType(TaskType.MISTING);

            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                    .thenReturn(false);

            service.checkAndSendNotifications();

            assertThat(buttonLabels()).contains("✅ Опрыскал");
        }

        @Test
        @DisplayName("Кнопка done содержит правильный глагол для FERTILIZING")
        void shouldUseFertilizingDoneButtonLabel() {
            schedule.setTaskType(TaskType.FERTILIZING);

            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                    .thenReturn(false);

            service.checkAndSendNotifications();

            assertThat(buttonLabels()).contains("✅ Удобрил");
        }

        @Test
        @DisplayName("Обычное уведомление содержит кнопки done, snooze, skip")
        void shouldContainThreeInlineButtons() {
            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                    .thenReturn(false);

            service.checkAndSendNotifications();

            assertThat(callbackData())
                    .contains("v1:done:100", "v1:snooze:100", "v1:skip:100");
        }

        private List<String> buttonLabels() {
            ArgumentCaptor<SendMessage> messageCaptor = ArgumentCaptor.forClass(SendMessage.class);
            verify(telegramSender).enqueue(messageCaptor.capture(), any(SendCallbacks.class));
            InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) messageCaptor.getValue().getReplyMarkup();
            return keyboard.getKeyboard().stream()
                    .flatMap(Collection::stream)
                    .map(InlineKeyboardButton::getText)
                    .toList();
        }

        private List<String> callbackData() {
            ArgumentCaptor<SendMessage> messageCaptor = ArgumentCaptor.forClass(SendMessage.class);
            verify(telegramSender).enqueue(messageCaptor.capture(), any(SendCallbacks.class));
            InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) messageCaptor.getValue().getReplyMarkup();
            return keyboard.getKeyboard().stream()
                    .flatMap(Collection::stream)
                    .map(InlineKeyboardButton::getCallbackData)
                    .toList();
        }
    }

    @Nested
    @DisplayName("Дайджест уведомлений")
    class DigestNotifications {

        @Test
        @DisplayName("Если у одного пользователя 3 due-задачи, в очередь ставится один digest")
        void shouldEnqueueDigestWhenUserHasSeveralDueSchedules() {
            CareSchedule schedule2 = dueSchedule(plant, TaskType.MISTING, 101L);
            CareSchedule schedule3 = dueSchedule(plant, TaskType.FERTILIZING, 102L);

            NotificationDigest savedDigest = savedDigest(500L);

            when(careScheduleRepository.findDueSchedules(any()))
                    .thenReturn(List.of(schedule, schedule2, schedule3));
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                    .thenReturn(false);
            when(notificationDigestRepository.save(any(NotificationDigest.class)))
                    .thenReturn(savedDigest);

            service.checkAndSendNotifications();

            ArgumentCaptor<SendMessage> messageCaptor = ArgumentCaptor.forClass(SendMessage.class);
            verify(telegramSender).enqueue(messageCaptor.capture(), any(SendCallbacks.class));

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

            assertThat(callbacks).contains("digest:done_all:500", "digest:expand:500");

            verify(notificationDigestRepository).save(any(NotificationDigest.class));
        }

        @Test
        @DisplayName("onSuccess дайджеста пишет дедуп-лог по каждой задаче через onDigestSent")
        void shouldRouteDigestSuccessToOnDigestSent() {
            CareSchedule schedule2 = dueSchedule(plant, TaskType.MISTING, 101L);
            when(careScheduleRepository.findDueSchedules(any()))
                    .thenReturn(List.of(schedule, schedule2));
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                    .thenReturn(false);
            when(notificationDigestRepository.save(any(NotificationDigest.class)))
                    .thenReturn(savedDigest(500L));

            service.checkAndSendNotifications();

            runSuccess(captureCallbacks());

            ArgumentCaptor<List<NotificationDeliveryCallbacks.DigestLogItem>> itemsCaptor =
                    ArgumentCaptor.forClass(List.class);
            verify(deliveryCallbacks).onDigestSent(itemsCaptor.capture());
            assertThat(itemsCaptor.getValue())
                    .extracting(NotificationDeliveryCallbacks.DigestLogItem::taskType)
                    .containsExactlyInAnyOrder(TaskType.WATERING, TaskType.MISTING);
        }

        @Test
        @DisplayName("В NotificationDigest сохраняются все задачи пользователя")
        void shouldSaveDigestWithAllTasks() {
            CareSchedule schedule2 = dueSchedule(plant, TaskType.MISTING, 101L);

            when(careScheduleRepository.findDueSchedules(any()))
                    .thenReturn(List.of(schedule, schedule2));
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                    .thenReturn(false);
            when(notificationDigestRepository.save(any(NotificationDigest.class)))
                    .thenReturn(savedDigest(500L));

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
        void shouldNotCreateDigestForDifferentUsersWithSingleTaskEach() {
            User secondUser = secondUser();
            Plant secondPlant = secondPlant(secondUser);
            CareSchedule secondSchedule = dueSchedule(secondPlant, TaskType.MISTING, 200L);

            when(careScheduleRepository.findDueSchedules(any()))
                    .thenReturn(List.of(schedule, secondSchedule));
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                    .thenReturn(false);

            service.checkAndSendNotifications();

            verify(telegramSender, times(2)).enqueue(any(SendMessage.class), any(SendCallbacks.class));
            verify(notificationDigestRepository, never()).save(any());
        }

        @Test
        @DisplayName("Первый пользователь получает digest, второй — обычное уведомление")
        void shouldSendDigestForOneUserAndSingleNotificationForAnother() {
            CareSchedule schedule2 = dueSchedule(plant, TaskType.MISTING, 101L);

            User secondUser = secondUser();
            Plant secondPlant = secondPlant(secondUser);
            CareSchedule secondUserSchedule = dueSchedule(secondPlant, TaskType.WATERING, 200L);

            when(careScheduleRepository.findDueSchedules(any()))
                    .thenReturn(List.of(schedule, schedule2, secondUserSchedule));
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                    .thenReturn(false);
            when(notificationDigestRepository.save(any(NotificationDigest.class)))
                    .thenReturn(savedDigest(500L));

            service.checkAndSendNotifications();

            ArgumentCaptor<SendMessage> messageCaptor = ArgumentCaptor.forClass(SendMessage.class);
            verify(telegramSender, times(2)).enqueue(messageCaptor.capture(), any(SendCallbacks.class));

            List<SendMessage> sentMessages = messageCaptor.getAllValues();
            assertThat(sentMessages)
                    .anyMatch(m -> m.getChatId().equals("100") && m.getText().contains("На сегодня:"));
            assertThat(sentMessages)
                    .anyMatch(m -> m.getChatId().equals("200")
                            && m.getText().contains("Пора полить")
                            && m.getText().contains("Фикус"));

            verify(notificationDigestRepository).save(any(NotificationDigest.class));
        }
    }

    @Nested
    @DisplayName("Фильтрация: пауза пользователя")
    class PausedUser {

        @Test
        @DisplayName("Не ставит в очередь, если pausedUntil > now")
        void shouldSkipPausedUser() {
            user.setPausedUntil(LocalDateTime.now().plusHours(1));

            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));

            service.checkAndSendNotifications();

            verifyNoInteractions(telegramSender);
            verify(notificationDigestRepository, never()).save(any());
        }

        @Test
        @DisplayName("Ставит в очередь, если pausedUntil уже прошло")
        void shouldSendIfPauseExpired() {
            user.setPausedUntil(LocalDateTime.now().minusHours(1));

            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                    .thenReturn(false);

            service.checkAndSendNotifications();

            verify(telegramSender).enqueue(any(SendMessage.class), any(SendCallbacks.class));
        }
    }

    @Nested
    @DisplayName("Фильтрация: тихие часы")
    class QuietHours {

        @Test
        @DisplayName("Не ставит в очередь во время тихих часов")
        void shouldSkipDuringQuietHours() {
            user.setQuietHoursStart(LocalTime.of(0, 0));
            user.setQuietHoursEnd(LocalTime.of(23, 59));

            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));
            // С #118-фикса quiet-проверка идёт через Instant-перегрузку.
            when(quietHoursPolicy.isQuiet(any(User.class), any(Instant.class)))
                    .thenReturn(true);

            service.checkAndSendNotifications();

            verifyNoInteractions(telegramSender);
            verify(notificationDigestRepository, never()).save(any());
        }

        @Test
        @DisplayName("Ставит в очередь, если quiet_hours_start == quiet_hours_end")
        void shouldSendWhenQuietHoursDisabled() {
            user.setQuietHoursStart(LocalTime.of(0, 0));
            user.setQuietHoursEnd(LocalTime.of(0, 0));

            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                    .thenReturn(false);

            service.checkAndSendNotifications();

            verify(telegramSender).enqueue(any(SendMessage.class), any(SendCallbacks.class));
        }
    }

    @Nested
    @DisplayName("Фильтрация: дедупликация")
    class Deduplication {

        @Test
        @DisplayName("Не ставит в очередь, если за последние 12 часов уже отправляли")
        void shouldSkipIfAlreadyNotifiedRecently() {
            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                    .thenReturn(true);

            service.checkAndSendNotifications();

            verifyNoInteractions(telegramSender);
            verify(notificationDigestRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Ошибки доставки маршрутизируются в onFailed")
    class FailureRouting {

        @Test
        @DisplayName("403-колбэк делегирует в onFailed (пометка blocked живёт там)")
        void shouldRouteForbiddenToOnFailed() {
            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                    .thenReturn(false);

            service.checkAndSendNotifications();

            TelegramApiException error =
                    new TelegramApiException("Forbidden: bot was blocked by the user [403]");
            runFailure(captureCallbacks(), error);

            verify(deliveryCallbacks).onFailed(100L, error);
        }

        @Test
        @DisplayName("Прочая ошибка тоже делегирует в onFailed с тем же chatId/исключением")
        void shouldRouteOtherErrorToOnFailed() {
            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                    .thenReturn(false);

            service.checkAndSendNotifications();

            TelegramApiException error = new TelegramApiException("Network timeout");
            runFailure(captureCallbacks(), error);

            verify(deliveryCallbacks).onFailed(100L, error);
        }
    }

    @Nested
    @DisplayName("Нет просроченных расписаний")
    class NoDueSchedules {

        @Test
        @DisplayName("Не ставит ничего в очередь, если нет просроченных расписаний")
        void shouldDoNothingWhenNoDueSchedules() {
            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of());

            service.checkAndSendNotifications();

            verifyNoInteractions(telegramSender);
            verify(notificationDigestRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Метрики тика (#115)")
    class Metrics {

        @Test
        @DisplayName("Каждый тик оборачивается в Timer.Sample (start + stop) даже без расписаний")
        void should_wrap_each_tick_in_timer_sample_when_called() {
            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of());
            io.micrometer.core.instrument.Timer.Sample sample =
                    org.mockito.Mockito.mock(io.micrometer.core.instrument.Timer.Sample.class);
            when(metricsService.startSchedulerTickTimer()).thenReturn(sample);

            service.checkAndSendNotifications();

            verify(metricsService).startSchedulerTickTimer();
            verify(metricsService).stopSchedulerTickTimer(sample);
        }

        @Test
        @DisplayName("Timer.stop вызывается даже если tick упал с исключением (finally)")
        void should_stop_timer_when_tick_fails_with_exception() {
            io.micrometer.core.instrument.Timer.Sample sample =
                    org.mockito.Mockito.mock(io.micrometer.core.instrument.Timer.Sample.class);
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
    }

    // ===== helpers =====

    private CareSchedule dueSchedule(Plant ownerPlant, TaskType type, long id) {
        CareSchedule s = CareSchedule.builder()
                .plant(ownerPlant)
                .taskType(type)
                .intervalDays(3)
                .nextDueAt(LocalDateTime.now().minusHours(1))
                .active(true)
                .build();
        ReflectionTestUtils.setField(s, "id", id);
        return s;
    }

    private NotificationDigest savedDigest(long id) {
        NotificationDigest d = NotificationDigest.builder()
                .userId(user.getId())
                .plantTaskIds(List.of())
                .build();
        ReflectionTestUtils.setField(d, "id", id);
        return d;
    }

    private User secondUser() {
        User secondUser = User.builder()
                .telegramChatId(200L)
                .timezone("Europe/Minsk")
                .quietHoursStart(LocalTime.of(0, 0))
                .quietHoursEnd(LocalTime.of(0, 0))
                .blocked(false)
                .build();
        ReflectionTestUtils.setField(secondUser, "id", 2L);
        return secondUser;
    }

    private Plant secondPlant(User owner) {
        Plant secondPlant = Plant.builder()
                .user(owner)
                .name("Фикус")
                .build();
        ReflectionTestUtils.setField(secondPlant, "id", 20L);
        return secondPlant;
    }
}
