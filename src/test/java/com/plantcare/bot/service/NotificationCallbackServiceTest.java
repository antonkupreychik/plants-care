package com.plantcare.bot.service;

import com.plantcare.core.service.PlantAcclimationService;
import com.plantcare.core.service.PlantService;
import com.plantcare.core.service.QuietHoursPolicy;
import com.plantcare.core.service.UserService;

import com.plantcare.core.domain.CareHistory;
import com.plantcare.core.domain.CareSchedule;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.metrics.MetricsService;
import com.plantcare.core.metrics.MetricsService.CallbackOutcome;
import com.plantcare.core.repository.CareHistoryRepository;
import com.plantcare.core.repository.CareScheduleRepository;
import com.plantcare.core.seasonal.service.SeasonalIntervalService;
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
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Unit-тесты для NotificationCallbackService (#11)")
class NotificationCallbackServiceTest {

    @Mock
    private CareScheduleRepository careScheduleRepository;

    @Mock
    private CareHistoryRepository careHistoryRepository;

    @Mock
    private PlantService plantService;

    @Mock
    private UserService userService;

    @Mock
    private SeasonalIntervalService seasonalIntervalService;

    @Mock
    private TelegramClient telegramClient;

    @Mock
    private CallbackQuery callbackQuery;

    @Mock
    private Message message;

    // Зависимости, появившиеся после рефакторингов #71/#74/#75/#118.
    // Без них @InjectMocks инжектит null и тесты падают NPE на новых ветках.
    @Mock
    private PlantCardService plantCardService;

    @Mock
    private PlantAcclimationService plantAcclimationService;

    @Mock
    private QuietHoursPolicy quietHoursPolicy;

    @Mock
    private ReminderKeyboardFactory reminderKeyboardFactory;

    @Mock
    private BackdatedCareCallbackService backdatedCareCallbackService;

    @Mock
    private MetricsService metricsService;

    /**
     * Реальный Clock-bean: snooze-flow вызывает {@code clock.instant()} напрямую,
     * мокать смысла нет — оставляем системный, тесты проверяют только относительный
     * сдвиг (between(before+1h; before+3h)).
     */
    @org.mockito.Spy
    private java.time.Clock clock = java.time.Clock.systemUTC();

    @InjectMocks
    private NotificationCallbackService service;

    private CareSchedule schedule;
    private Plant plant;

    @BeforeEach
    void setUp() {
        User user = User.builder()
                .telegramChatId(100L)
                .timezone("UTC")
                .build();

        plant = Plant.builder()
                .user(user)
                .name("Монстера")
                .build();

        schedule = CareSchedule.builder()
                .plant(plant)
                // MISTING вместо WATERING: после issue #71 done для WATERING стартует
                // двухшаговый flow (отдельные тесты в NotificationCallbackServiceWateringDetailsTest).
                // Здесь проверяем классический немедленный done — он остался для MISTING/FERTILIZING.
                .taskType(TaskType.MISTING)
                .intervalDays(7)
                .nextDueAt(LocalDateTime.now().minusHours(1))
                .active(true)
                .build();

        when(callbackQuery.getId()).thenReturn("cb-123");
        when(callbackQuery.getMessage()).thenReturn(message);
        when(message.getChatId()).thenReturn(100L);
        when(message.getMessageId()).thenReturn(42);

        when(seasonalIntervalService.effectiveIntervalDays(
                any(Plant.class),
                any(User.class),
                anyInt()
        )).thenAnswer(invocation -> invocation.getArgument(2));
    }

    @Nested
    @DisplayName("Действие: done")
    class DoneAction {

        @Test
        @DisplayName("Записывает CareHistory с done_at = now()")
        void shouldCreateCareHistoryRecord() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:done:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));
            when(careHistoryRepository.findFirstByPlantIdAndTaskTypeOrderByDoneAtDesc(any(), any()))
                    .thenReturn(Optional.empty());

            service.handleCallback(callbackQuery, telegramClient);

            ArgumentCaptor<CareHistory> captor = ArgumentCaptor.forClass(CareHistory.class);
            verify(careHistoryRepository).save(captor.capture());

            CareHistory saved = captor.getValue();
            assertThat(saved.getPlant()).isEqualTo(plant);
            assertThat(saved.getTaskType()).isEqualTo(TaskType.MISTING);
            assertThat(saved.getDoneAt()).isNotNull();
            assertThat(saved.getNote()).isNull();
        }

        @Test
        @DisplayName("was_on_time = true, если now <= scheduled_at + 24h")
        void shouldBeOnTimeWithinGracePeriod() throws TelegramApiException {
            schedule.setNextDueAt(LocalDateTime.now().minusHours(1));

            when(callbackQuery.getData()).thenReturn("v1:done:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));
            when(careHistoryRepository.findFirstByPlantIdAndTaskTypeOrderByDoneAtDesc(any(), any()))
                    .thenReturn(Optional.empty());

            service.handleCallback(callbackQuery, telegramClient);

            ArgumentCaptor<CareHistory> captor = ArgumentCaptor.forClass(CareHistory.class);
            verify(careHistoryRepository).save(captor.capture());

            assertThat(captor.getValue().isOnTime()).isTrue();
        }

        @Test
        @DisplayName("was_on_time = false, если now > scheduled_at + 24h")
        void shouldNotBeOnTimeAfterGracePeriod() throws TelegramApiException {
            schedule.setNextDueAt(LocalDateTime.now().minusHours(25));

            when(callbackQuery.getData()).thenReturn("v1:done:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));
            when(careHistoryRepository.findFirstByPlantIdAndTaskTypeOrderByDoneAtDesc(any(), any()))
                    .thenReturn(Optional.empty());

            service.handleCallback(callbackQuery, telegramClient);

            ArgumentCaptor<CareHistory> captor = ArgumentCaptor.forClass(CareHistory.class);
            verify(careHistoryRepository).save(captor.capture());

            assertThat(captor.getValue().isOnTime()).isFalse();
        }

        @Test
        @DisplayName("was_on_time = true, если scheduled_at в будущем (досрочно)")
        void shouldBeOnTimeWhenDoneEarly() throws TelegramApiException {
            schedule.setNextDueAt(LocalDateTime.now().plusHours(2));

            when(callbackQuery.getData()).thenReturn("v1:done:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));
            when(careHistoryRepository.findFirstByPlantIdAndTaskTypeOrderByDoneAtDesc(any(), any()))
                    .thenReturn(Optional.empty());

            service.handleCallback(callbackQuery, telegramClient);

            ArgumentCaptor<CareHistory> captor = ArgumentCaptor.forClass(CareHistory.class);
            verify(careHistoryRepository).save(captor.capture());

            assertThat(captor.getValue().isOnTime()).isTrue();
        }

        @Test
        @DisplayName("next_due_at = now() + interval_days")
        void shouldRescheduleFromNow() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:done:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));
            when(careHistoryRepository.findFirstByPlantIdAndTaskTypeOrderByDoneAtDesc(any(), any()))
                    .thenReturn(Optional.empty());

            LocalDateTime before = LocalDateTime.now();

            service.handleCallback(callbackQuery, telegramClient);

            assertThat(schedule.getNextDueAt()).isAfter(before.plusDays(6));
            assertThat(schedule.getNextDueAt()).isBefore(before.plusDays(8));
            verify(careScheduleRepository).save(schedule);
        }

        @Test
        @DisplayName("Текст done-ответа содержит правильный глагол для MISTING")
        void shouldEditMessageWithMistingText() throws TelegramApiException {
            schedule.setTaskType(TaskType.MISTING);

            when(callbackQuery.getData()).thenReturn("v1:done:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));
            when(careHistoryRepository.findFirstByPlantIdAndTaskTypeOrderByDoneAtDesc(any(), any()))
                    .thenReturn(Optional.empty());

            service.handleCallback(callbackQuery, telegramClient);

            ArgumentCaptor<EditMessageText> captor = ArgumentCaptor.forClass(EditMessageText.class);
            verify(telegramClient).execute(captor.capture());

            assertThat(captor.getValue().getText()).startsWith("✅ Опрыскал Монстера в ");
            assertThat(captor.getValue().getText()).contains("Следующее опрыскивание —");
        }

        @Test
        @DisplayName("Текст done-ответа содержит правильный глагол для FERTILIZING")
        void shouldEditMessageWithFertilizingText() throws TelegramApiException {
            schedule.setTaskType(TaskType.FERTILIZING);

            when(callbackQuery.getData()).thenReturn("v1:done:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));
            when(careHistoryRepository.findFirstByPlantIdAndTaskTypeOrderByDoneAtDesc(any(), any()))
                    .thenReturn(Optional.empty());

            service.handleCallback(callbackQuery, telegramClient);

            ArgumentCaptor<EditMessageText> captor = ArgumentCaptor.forClass(EditMessageText.class);
            verify(telegramClient).execute(captor.capture());

            assertThat(captor.getValue().getText()).startsWith("✅ Удобрил Монстера в ");
            assertThat(captor.getValue().getText()).contains("Следующее удобрение —");
        }
    }

    @Nested
    @DisplayName("Дедупликация: защита от двойного нажатия")
    class Deduplication {

        @Test
        @DisplayName("Повторное done в течение 60с — не создаёт второй записи")
        void shouldNotDuplicateWithin60Seconds() throws TelegramApiException {
            CareHistory recent = CareHistory.builder()
                    .plant(plant)
                    .taskType(TaskType.MISTING)
                    .doneAt(LocalDateTime.now().minusSeconds(30))
                    .onTime(true)
                    .build();

            when(callbackQuery.getData()).thenReturn("v1:done:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));
            when(careHistoryRepository.findFirstByPlantIdAndTaskTypeOrderByDoneAtDesc(any(), any()))
                    .thenReturn(Optional.of(recent));

            service.handleCallback(callbackQuery, telegramClient);

            verify(careHistoryRepository, never()).save(any());
            verify(careScheduleRepository, never()).save(any());

            ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
            verify(telegramClient).execute(captor.capture());

            assertThat(captor.getValue().getText()).contains("Уже отмечено");
        }

        @Test
        @DisplayName("done спустя 61+ секунд — нормально создаёт запись")
        void shouldAllowAfter60Seconds() throws TelegramApiException {
            CareHistory old = CareHistory.builder()
                    .plant(plant)
                    .taskType(TaskType.MISTING)
                    .doneAt(LocalDateTime.now().minusSeconds(120))
                    .onTime(true)
                    .build();

            when(callbackQuery.getData()).thenReturn("v1:done:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));
            when(careHistoryRepository.findFirstByPlantIdAndTaskTypeOrderByDoneAtDesc(any(), any()))
                    .thenReturn(Optional.of(old));

            service.handleCallback(callbackQuery, telegramClient);

            verify(careHistoryRepository).save(any(CareHistory.class));
            verify(careScheduleRepository).save(schedule);
        }

        @Test
        @DisplayName("Повторный skip в течение 60с — тоже дедуплицируется")
        void shouldDeduplicateSkip() throws TelegramApiException {
            CareHistory recent = CareHistory.builder()
                    .plant(plant)
                    .taskType(TaskType.MISTING)
                    .doneAt(LocalDateTime.now().minusSeconds(10))
                    .onTime(false)
                    .note("skipped")
                    .build();

            when(callbackQuery.getData()).thenReturn("v1:skip:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));
            when(careHistoryRepository.findFirstByPlantIdAndTaskTypeOrderByDoneAtDesc(any(), any()))
                    .thenReturn(Optional.of(recent));

            service.handleCallback(callbackQuery, telegramClient);

            verify(careHistoryRepository, never()).save(any());
            verify(careScheduleRepository, never()).save(any());
        }

        @Test
        @DisplayName("Первое нажатие без истории — не дубликат")
        void shouldNotBeDuplicateWhenNoHistory() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:done:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));
            when(careHistoryRepository.findFirstByPlantIdAndTaskTypeOrderByDoneAtDesc(any(), any()))
                    .thenReturn(Optional.empty());

            service.handleCallback(callbackQuery, telegramClient);

            verify(careHistoryRepository).save(any(CareHistory.class));
        }
    }

    @Nested
    @DisplayName("Действие: skip")
    class SkipAction {

        @Test
        @DisplayName("CareHistory: was_on_time=false, note='skipped'")
        void shouldCreateHistoryWithSkippedNote() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:skip:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));
            when(careHistoryRepository.findFirstByPlantIdAndTaskTypeOrderByDoneAtDesc(any(), any()))
                    .thenReturn(Optional.empty());

            service.handleCallback(callbackQuery, telegramClient);

            ArgumentCaptor<CareHistory> captor = ArgumentCaptor.forClass(CareHistory.class);
            verify(careHistoryRepository).save(captor.capture());

            CareHistory saved = captor.getValue();
            assertThat(saved.isOnTime()).isFalse();
            assertThat(saved.getNote()).isEqualTo("skipped");
        }

        @Test
        @DisplayName("next_due_at пересчитывается от now()")
        void shouldRescheduleFromNow() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:skip:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));
            when(careHistoryRepository.findFirstByPlantIdAndTaskTypeOrderByDoneAtDesc(any(), any()))
                    .thenReturn(Optional.empty());

            LocalDateTime before = LocalDateTime.now();

            service.handleCallback(callbackQuery, telegramClient);

            assertThat(schedule.getNextDueAt()).isAfter(before.plusDays(6));
            verify(careScheduleRepository).save(schedule);
        }

        @Test
        @DisplayName("Текст содержит 'пропущено'")
        void shouldEditWithSkipText() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:skip:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));
            when(careHistoryRepository.findFirstByPlantIdAndTaskTypeOrderByDoneAtDesc(any(), any()))
                    .thenReturn(Optional.empty());

            service.handleCallback(callbackQuery, telegramClient);

            ArgumentCaptor<EditMessageText> captor = ArgumentCaptor.forClass(EditMessageText.class);
            verify(telegramClient).execute(captor.capture());

            assertThat(captor.getValue().getText()).contains("❌").contains("пропущено");
        }
    }

    @Nested
    @DisplayName("Действие: snooze")
    class SnoozeAction {

        // После issue #118 v1:snooze:<id> больше не сдвигает время сразу:
        // показывает клавиатуру выбора интервала (через час / вечером / завтра),
        // а сдвиг делает только v1:snooze_pick. Поэтому тест ниже проверяет,
        // что время и история не меняются.
        @Test
        @DisplayName("Клик «Отложить» не пишет CareHistory и не двигает время (#118)")
        void shouldSnoozeWithoutHistory() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:snooze:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

            LocalDateTime originalNext = schedule.getNextDueAt();

            service.handleCallback(callbackQuery, telegramClient);

            assertThat(schedule.getNextDueAt()).isEqualTo(originalNext);
            verify(careScheduleRepository, never()).save(any());
            verify(careHistoryRepository, never()).save(any());
        }

        @Test
        @DisplayName("interval_days не меняется")
        void shouldNotChangeInterval() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:snooze:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

            int original = schedule.getIntervalDays();

            service.handleCallback(callbackQuery, telegramClient);

            assertThat(schedule.getIntervalDays()).isEqualTo(original);
        }
    }

    @Nested
    @DisplayName("Удалённое (архивированное) растение")
    class ArchivedPlant {

        @Test
        @DisplayName("done → 'Растение уже удалено', без записи")
        void shouldHandleArchivedOnDone() throws TelegramApiException {
            plant.archive();

            when(callbackQuery.getData()).thenReturn("v1:done:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

            service.handleCallback(callbackQuery, telegramClient);

            verify(careHistoryRepository, never()).save(any());
            verify(careScheduleRepository, never()).save(any());

            ArgumentCaptor<EditMessageText> editCaptor = ArgumentCaptor.forClass(EditMessageText.class);
            verify(telegramClient).execute(editCaptor.capture());

            assertThat(editCaptor.getValue().getText()).contains("удалено");
        }

        @Test
        @DisplayName("skip → тоже graceful, без записи")
        void shouldHandleArchivedOnSkip() throws TelegramApiException {
            plant.archive();

            when(callbackQuery.getData()).thenReturn("v1:skip:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

            service.handleCallback(callbackQuery, telegramClient);

            verify(careHistoryRepository, never()).save(any());
            verify(careScheduleRepository, never()).save(any());
        }

        @Test
        @DisplayName("snooze → тоже graceful, без записи")
        void shouldHandleArchivedOnSnooze() throws TelegramApiException {
            plant.archive();

            when(callbackQuery.getData()).thenReturn("v1:snooze:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

            service.handleCallback(callbackQuery, telegramClient);

            verify(careScheduleRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Невалидные callback_data")
    class InvalidCallbacks {

        @Test
        @DisplayName("Неверный формат")
        void shouldHandleInvalidFormat() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:invalid");

            service.handleCallback(callbackQuery, telegramClient);

            ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
            verify(telegramClient).execute(captor.capture());

            assertThat(captor.getValue().getText()).contains("❌");
            verify(careHistoryRepository, never()).save(any());
        }

        @Test
        @DisplayName("Несуществующий scheduleId")
        void shouldHandleMissingSchedule() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:done:9999");
            when(careScheduleRepository.findById(9999L)).thenReturn(Optional.empty());

            service.handleCallback(callbackQuery, telegramClient);

            ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
            verify(telegramClient).execute(captor.capture());

            assertThat(captor.getValue().getText()).contains("не найдено");
        }

        @Test
        @DisplayName("Неизвестное действие")
        void shouldHandleUnknownAction() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:unknown:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

            service.handleCallback(callbackQuery, telegramClient);

            verify(telegramClient).execute(any(AnswerCallbackQuery.class));
            verify(telegramClient, never()).execute(any(EditMessageText.class));
            verify(careHistoryRepository, never()).save(any());
        }

        @Test
        @DisplayName("Нечисловой ID")
        void shouldHandleNonNumericId() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:done:abc");

            service.handleCallback(callbackQuery, telegramClient);

            ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
            verify(telegramClient).execute(captor.capture());

            assertThat(captor.getValue().getText()).contains("❌");
        }
    }

    @Nested
    @DisplayName("Действие: bulk_done (issue #19)")
    class BulkDoneAction {

        private User user;

        @BeforeEach
        void seedUser() {
            user = User.builder()
                    .telegramChatId(100L)
                    .timezone("UTC")
                    .build();

            when(userService.findByChatId(100L)).thenReturn(Optional.of(user));
        }

        @Test
        @DisplayName("3 растения обновились — присылаем итоговое сообщение и алёрт 'Готово!'")
        void shouldSendResultMessageOnSuccess() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:bulk_done:5");
            when(plantService.markBulkCareDone(any(), eq(5L), eq(TaskType.WATERING)))
                    .thenReturn(new PlantService.BulkCareDoneResult(3, 0, " Гостиная"));

            service.handleCallback(callbackQuery, telegramClient);

            ArgumentCaptor<org.telegram.telegrambots.meta.api.methods.send.SendMessage> sendCaptor =
                    ArgumentCaptor.forClass(org.telegram.telegrambots.meta.api.methods.send.SendMessage.class);
            verify(telegramClient).execute(sendCaptor.capture());

            assertThat(sendCaptor.getValue().getText())
                    .contains("Готово")
                    .contains("3")
                    .contains(" Гостиная");

            ArgumentCaptor<AnswerCallbackQuery> alertCaptor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
            verify(telegramClient).execute(alertCaptor.capture());

            assertThat(alertCaptor.getValue().getText()).contains("Готово");
            verify(telegramClient, never()).execute(any(EditMessageText.class));
        }

        @Test
        @DisplayName("Double-click: всё deduped — алёрт 'Уже полито', сообщение не шлётся")
        void shouldShowAlreadyDoneOnFullDedup() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:bulk_done:5");
            when(plantService.markBulkCareDone(any(), eq(5L), eq(TaskType.WATERING)))
                    .thenReturn(new PlantService.BulkCareDoneResult(0, 3, " Гостиная"));

            service.handleCallback(callbackQuery, telegramClient);

            ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
            verify(telegramClient).execute(captor.capture());

            assertThat(captor.getValue().getText()).contains("Уже полито");
            verify(telegramClient, never()).execute(
                    any(org.telegram.telegrambots.meta.api.methods.send.SendMessage.class)
            );
        }

        @Test
        @DisplayName("Пустая локация — алёрт 'нечего поливать'")
        void shouldShowEmptyLocationAlert() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:bulk_done:5");
            when(plantService.markBulkCareDone(any(), eq(5L), eq(TaskType.WATERING)))
                    .thenReturn(new PlantService.BulkCareDoneResult(0, 0, null));

            service.handleCallback(callbackQuery, telegramClient);

            ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
            verify(telegramClient).execute(captor.capture());

            assertThat(captor.getValue().getText()).contains("нечего поливать");
            verify(telegramClient, never()).execute(
                    any(org.telegram.telegrambots.meta.api.methods.send.SendMessage.class)
            );
        }

        @Test
        @DisplayName("Битый locationId — алёрт об ошибке, plantService не дёргается")
        void shouldRejectMalformedLocationId() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:bulk_done:abc");

            service.handleCallback(callbackQuery, telegramClient);

            ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
            verify(telegramClient).execute(captor.capture());

            assertThat(captor.getValue().getText()).contains("❌");
            verify(plantService, never()).markBulkCareDone(any(), any(), any());
        }

        @Test
        @DisplayName("Неизвестный chatId — алёрт 'Сначала /start', plantService не дёргается")
        void shouldHandleUnknownUser() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:bulk_done:5");
            when(userService.findByChatId(100L)).thenReturn(Optional.empty());

            service.handleCallback(callbackQuery, telegramClient);

            ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
            verify(telegramClient).execute(captor.capture());

            assertThat(captor.getValue().getText()).contains("/start");
            verify(plantService, never()).markBulkCareDone(any(), any(), any());
        }

        @Test
        @DisplayName("Pluralization: 1 → 'растения', 5 → 'растений', 21 → 'растения'")
        void shouldPluralizeCorrectly() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:bulk_done:5");

            when(plantService.markBulkCareDone(any(), eq(5L), eq(TaskType.WATERING)))
                    .thenReturn(new PlantService.BulkCareDoneResult(1, 0, " Гостиная"));
            service.handleCallback(callbackQuery, telegramClient);

            when(plantService.markBulkCareDone(any(), eq(5L), eq(TaskType.WATERING)))
                    .thenReturn(new PlantService.BulkCareDoneResult(5, 0, " Гостиная"));
            service.handleCallback(callbackQuery, telegramClient);

            when(plantService.markBulkCareDone(any(), eq(5L), eq(TaskType.WATERING)))
                    .thenReturn(new PlantService.BulkCareDoneResult(21, 0, " Гостиная"));
            service.handleCallback(callbackQuery, telegramClient);

            ArgumentCaptor<org.telegram.telegrambots.meta.api.methods.send.SendMessage> captor =
                    ArgumentCaptor.forClass(org.telegram.telegrambots.meta.api.methods.send.SendMessage.class);
            verify(telegramClient, times(3)).execute(captor.capture());

            assertThat(captor.getAllValues().get(0).getText()).contains("1 растения");
            assertThat(captor.getAllValues().get(1).getText()).contains("5 растений");
            assertThat(captor.getAllValues().get(2).getText()).contains("21 растения");
        }
    }

    @Nested
    @DisplayName("Метрики callbacks (#115)")
    class CallbackMetrics {

        @Test
        @DisplayName("done MISTING (без WATERING-flow): recordCallback(done, ok)")
        void should_record_done_ok_when_misting_done_succeeds() throws TelegramApiException {
            // MISTING — отметка идёт по прежнему пути (не через WATERING двухшаговый flow),
            // т.е. финальный recordCallback(done, ok) должен сработать.
            when(callbackQuery.getData()).thenReturn("v1:done:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));
            when(careHistoryRepository.findFirstByPlantIdAndTaskTypeOrderByDoneAtDesc(any(), any()))
                    .thenReturn(Optional.empty());

            service.handleCallback(callbackQuery, telegramClient);

            verify(metricsService).recordCallback("done", CallbackOutcome.OK);
        }

        @Test
        @DisplayName("snooze: recordCallback(snooze, ok)")
        void should_record_snooze_ok_when_snooze_action_handled() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:snooze:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

            service.handleCallback(callbackQuery, telegramClient);

            verify(metricsService).recordCallback("snooze", CallbackOutcome.OK);
        }

        @Test
        @DisplayName("skip: recordCallback(skip, ok)")
        void should_record_skip_ok_when_skip_action_handled() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:skip:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));
            when(careHistoryRepository.findFirstByPlantIdAndTaskTypeOrderByDoneAtDesc(any(), any()))
                    .thenReturn(Optional.empty());

            service.handleCallback(callbackQuery, telegramClient);

            verify(metricsService).recordCallback("skip", CallbackOutcome.OK);
        }

        @Test
        @DisplayName("Повторное done в окне дедупа: recordCallback(done, idempotent)")
        void should_record_done_idempotent_when_dedup_within_60s() throws TelegramApiException {
            CareHistory recent = CareHistory.builder()
                    .plant(plant)
                    .taskType(TaskType.MISTING)
                    .doneAt(LocalDateTime.now().minusSeconds(10))
                    .onTime(true)
                    .build();

            when(callbackQuery.getData()).thenReturn("v1:done:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));
            when(careHistoryRepository.findFirstByPlantIdAndTaskTypeOrderByDoneAtDesc(any(), any()))
                    .thenReturn(Optional.of(recent));

            service.handleCallback(callbackQuery, telegramClient);

            verify(metricsService).recordCallback("done", CallbackOutcome.IDEMPOTENT);
        }

        @Test
        @DisplayName("Архивированное растение: recordCallback(done, idempotent)")
        void should_record_done_idempotent_when_plant_archived() throws TelegramApiException {
            plant.archive();

            when(callbackQuery.getData()).thenReturn("v1:done:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

            service.handleCallback(callbackQuery, telegramClient);

            verify(metricsService).recordCallback("done", CallbackOutcome.IDEMPOTENT);
        }

        @Test
        @DisplayName("Невалидный формат callback: recordCallback(unknown, error)")
        void should_record_unknown_error_when_callback_format_too_short() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:invalid");

            service.handleCallback(callbackQuery, telegramClient);

            verify(metricsService).recordCallback("unknown", CallbackOutcome.ERROR);
        }

        @Test
        @DisplayName("Несуществующий scheduleId: recordCallback(done, error)")
        void should_record_done_error_when_schedule_not_found() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:done:9999");
            when(careScheduleRepository.findById(9999L)).thenReturn(Optional.empty());

            service.handleCallback(callbackQuery, telegramClient);

            verify(metricsService).recordCallback("done", CallbackOutcome.ERROR);
        }

        @Test
        @DisplayName("Неизвестный action: recordCallback(unknown_action_name, error)")
        void should_record_unknown_action_error_when_unrecognized_command() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:weirdo:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

            service.handleCallback(callbackQuery, telegramClient);

            verify(metricsService).recordCallback("weirdo", CallbackOutcome.ERROR);
        }

        @Test
        @DisplayName("bulk_done успешно: recordCallback(bulk_done, ok)")
        void should_record_bulk_done_ok_on_success() throws TelegramApiException {
            User u = User.builder().telegramChatId(100L).timezone("UTC").build();
            when(userService.findByChatId(100L)).thenReturn(Optional.of(u));
            when(callbackQuery.getData()).thenReturn("v1:bulk_done:5");
            when(plantService.markBulkCareDone(any(), eq(5L), eq(TaskType.WATERING)))
                    .thenReturn(new PlantService.BulkCareDoneResult(3, 0, "Гостиная"));

            service.handleCallback(callbackQuery, telegramClient);

            verify(metricsService).recordCallback("bulk_done", CallbackOutcome.OK);
        }

        @Test
        @DisplayName("bulk_done double-click: recordCallback(bulk_done, idempotent)")
        void should_record_bulk_done_idempotent_on_full_dedup() throws TelegramApiException {
            User u = User.builder().telegramChatId(100L).timezone("UTC").build();
            when(userService.findByChatId(100L)).thenReturn(Optional.of(u));
            when(callbackQuery.getData()).thenReturn("v1:bulk_done:5");
            when(plantService.markBulkCareDone(any(), eq(5L), eq(TaskType.WATERING)))
                    .thenReturn(new PlantService.BulkCareDoneResult(0, 3, "Гостиная"));

            service.handleCallback(callbackQuery, telegramClient);

            verify(metricsService).recordCallback("bulk_done", CallbackOutcome.IDEMPOTENT);
        }
    }
}