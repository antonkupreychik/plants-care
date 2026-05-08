package com.plantcare.bot.service;

import com.plantcare.bot.client.TelegramClientProvider;
import com.plantcare.bot.domain.CareSchedule;
import com.plantcare.bot.domain.NotificationLog;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.TaskType;
import com.plantcare.bot.repository.CareScheduleRepository;
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
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Unit-тесты для NotificationSchedulerService")
class NotificationSchedulerServiceTest {

    @Mock private CareScheduleRepository careScheduleRepository;
    @Mock private NotificationLogRepository notificationLogRepository;
    @Mock private UserRepository userRepository;
    @Mock private TelegramClientProvider telegramClientProvider;
    @Mock private TelegramClient telegramClient;

    @InjectMocks
    private NotificationSchedulerService service;

    private User user;
    private Plant plant;
    private CareSchedule schedule;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .telegramChatId(100L)
                .timezone("UTC")
                // Тихие часы ОТКЛЮЧЕНЫ по умолчанию (start == end),
                // чтобы тесты не зависели от времени запуска
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
    }

    @Nested
    @DisplayName("Основной сценарий: отправка уведомления")
    class HappyPath {

        @Test
        @DisplayName("Отправляет уведомление, если все условия выполнены")
        void shouldSendNotificationWhenAllConditionsMet() throws TelegramApiException {
            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                    .thenReturn(false);
            when(telegramClientProvider.getTelegramClient()).thenReturn(telegramClient);

            service.checkAndSendNotifications();

            verify(telegramClient).execute(any(SendMessage.class));
            verify(notificationLogRepository).save(any(NotificationLog.class));
        }

        @Test
        @DisplayName("Текст уведомления содержит имя растения и эмодзи")
        void shouldContainPlantNameInNotificationText() throws TelegramApiException {
            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                    .thenReturn(false);
            when(telegramClientProvider.getTelegramClient()).thenReturn(telegramClient);

            service.checkAndSendNotifications();

            ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
            verify(telegramClient).execute(captor.capture());

            SendMessage sent = captor.getValue();
            assertThat(sent.getText()).contains("🌿 Пора полить: Монстера");
            assertThat(sent.getChatId()).isEqualTo("100");
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

            ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
            verify(telegramClient).execute(captor.capture());
            assertThat(captor.getValue().getText()).contains("💨 Пора опрыскать: Монстера");
        }

        @Test
        @DisplayName("Кнопка 'done' содержит правильный глагол по типу задачи")
        void shouldUseCorrectDoneButtonLabel() throws TelegramApiException {
            // WATERING → "✅ Полил"
            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                    .thenReturn(false);
            when(telegramClientProvider.getTelegramClient()).thenReturn(telegramClient);

            service.checkAndSendNotifications();

            ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
            verify(telegramClient).execute(captor.capture());

            InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) captor.getValue().getReplyMarkup();
            List<String> buttonLabels = keyboard.getKeyboard().stream()
                    .flatMap(Collection::stream)
                    .map(InlineKeyboardButton::getText)
                    .toList();
            assertThat(buttonLabels).contains("✅ Полил");
        }

        @Test
        @DisplayName("Кнопка 'done' для MISTING: '✅ Опрыскал'")
        void shouldUseMistingDoneButtonLabel() throws TelegramApiException {
            schedule.setTaskType(TaskType.MISTING);
            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                    .thenReturn(false);
            when(telegramClientProvider.getTelegramClient()).thenReturn(telegramClient);

            service.checkAndSendNotifications();

            ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
            verify(telegramClient).execute(captor.capture());

            InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) captor.getValue().getReplyMarkup();
            List<String> buttonLabels = keyboard.getKeyboard().stream()
                    .flatMap(Collection::stream)
                    .map(InlineKeyboardButton::getText)
                    .toList();
            assertThat(buttonLabels).contains("✅ Опрыскал");
        }

        @Test
        @DisplayName("Кнопка 'done' для FERTILIZING: '✅ Удобрил'")
        void shouldUseFertilizingDoneButtonLabel() throws TelegramApiException {
            schedule.setTaskType(TaskType.FERTILIZING);
            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                    .thenReturn(false);
            when(telegramClientProvider.getTelegramClient()).thenReturn(telegramClient);

            service.checkAndSendNotifications();

            ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
            verify(telegramClient).execute(captor.capture());

            InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) captor.getValue().getReplyMarkup();
            List<String> buttonLabels = keyboard.getKeyboard().stream()
                    .flatMap(Collection::stream)
                    .map(InlineKeyboardButton::getText)
                    .toList();
            assertThat(buttonLabels).contains("✅ Удобрил");
        }
        void shouldContainThreeInlineButtons() throws TelegramApiException {
            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                    .thenReturn(false);
            when(telegramClientProvider.getTelegramClient()).thenReturn(telegramClient);

            service.checkAndSendNotifications();

            ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
            verify(telegramClient).execute(captor.capture());

            InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) captor.getValue().getReplyMarkup();
            List<String> callbackData = keyboard.getKeyboard().stream()
                    .flatMap(Collection::stream)
                    .map(InlineKeyboardButton::getCallbackData)
                    .toList();

            assertThat(callbackData).anyMatch(d -> d.startsWith("v1:done:"));
            assertThat(callbackData).anyMatch(d -> d.startsWith("v1:snooze:"));
            assertThat(callbackData).anyMatch(d -> d.startsWith("v1:skip:"));
        }

        @Test
        @DisplayName("После отправки создаётся запись в notifications_log")
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
        }
    }

    @Nested
    @DisplayName("Фильтрация: пауза юзера")
    class PausedUser {

        @Test
        @DisplayName("Не отправляет уведомление, если pausedUntil > now()")
        void shouldSkipPausedUser() throws TelegramApiException {
            user.setPausedUntil(LocalDateTime.now().plusHours(1));
            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));

            service.checkAndSendNotifications();

            verify(telegramClient, never()).execute(any(SendMessage.class));
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
        @DisplayName("Не отправляет, если сейчас тихие часы (через полночь)")
        void shouldSkipDuringQuietHours() throws TelegramApiException {
            // Устанавливаем тихие часы, которые покрывают текущее время
            // Ставим 00:00–23:59 — гарантированно всегда тихие часы
            user.setQuietHoursStart(LocalTime.of(0, 0));
            user.setQuietHoursEnd(LocalTime.of(23, 59));

            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));

            service.checkAndSendNotifications();

            verify(telegramClient, never()).execute(any(SendMessage.class));
        }

        @Test
        @DisplayName("Отправляет, если quiet_hours_start == quiet_hours_end (тихие часы отключены)")
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
        @DisplayName("Не отправляет, если за последние 12 часов уже отправляли")
        void shouldSkipIfAlreadyNotifiedRecently() throws TelegramApiException {
            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule));
            when(notificationLogRepository.existsByPlantIdAndTaskTypeAndSentAtAfter(any(), any(), any()))
                    .thenReturn(true);

            service.checkAndSendNotifications();

            verify(telegramClient, never()).execute(any(SendMessage.class));
            verify(notificationLogRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Обработка ошибок Telegram")
    class TelegramErrors {

        @Test
        @DisplayName("При 403 помечает юзера как заблокированного")
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
        @DisplayName("При прочих ошибках юзер не блокируется")
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
        }

        @Test
        @DisplayName("Ошибка в одном schedule не останавливает обработку остальных")
        void shouldContinueProcessingAfterError() throws TelegramApiException {
            CareSchedule schedule2 = CareSchedule.builder()
                    .plant(plant)
                    .taskType(TaskType.MISTING)
                    .intervalDays(3)
                    .nextDueAt(LocalDateTime.now().minusHours(1))
                    .active(true)
                    .build();

            when(careScheduleRepository.findDueSchedules(any())).thenReturn(List.of(schedule, schedule2));
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
            verify(notificationLogRepository, never()).save(any());
        }
    }
}