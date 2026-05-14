package com.plantcare.bot.service;

import com.plantcare.bot.domain.CareHistory;
import com.plantcare.bot.domain.CareSchedule;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.TaskType;
import com.plantcare.bot.repository.CareHistoryRepository;
import com.plantcare.bot.repository.CareScheduleRepository;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Unit-тесты для NotificationCallbackService (#11)")
class NotificationCallbackServiceTest {

    @Mock private CareScheduleRepository careScheduleRepository;
    @Mock private CareHistoryRepository careHistoryRepository;
    @Mock private PlantService plantService;
    @Mock private UserService userService;
    @Mock private TelegramClient telegramClient;
    @Mock private CallbackQuery callbackQuery;
    @Mock private Message message;

    @InjectMocks
    private NotificationCallbackService service;

    private CareSchedule schedule;
    private Plant plant;

    @BeforeEach
    void setUp() {
        User user = User.builder().telegramChatId(100L).timezone("UTC").build();
        plant = Plant.builder().user(user).name("Монстера").build();
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
            // scheduled_at в будущем → now < scheduled + 24h → on_time = true
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
                    .plant(plant).taskType(TaskType.MISTING)
                    .doneAt(LocalDateTime.now().minusSeconds(30)).onTime(true).build();

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
                    .plant(plant).taskType(TaskType.MISTING)
                    .doneAt(LocalDateTime.now().minusSeconds(120)).onTime(true).build();

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
                    .plant(plant).taskType(TaskType.MISTING)
                    .doneAt(LocalDateTime.now().minusSeconds(10)).onTime(false).note("skipped").build();

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

        @Test
        @DisplayName("+2 часа, без CareHistory")
        void shouldSnoozeWithoutHistory() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:snooze:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

            LocalDateTime before = LocalDateTime.now();
            service.handleCallback(callbackQuery, telegramClient);

            assertThat(schedule.getNextDueAt()).isAfter(before.plusHours(1));
            assertThat(schedule.getNextDueAt()).isBefore(before.plusHours(3));
            verify(careScheduleRepository).save(schedule);
            verify(careHistoryRepository, never()).save(any());
        }

        @Test
        @DisplayName("interval_days не меняется")
        void shouldNotChangeInterval() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:snooze:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

            int orig = schedule.getIntervalDays();
            service.handleCallback(callbackQuery, telegramClient);

            assertThat(schedule.getIntervalDays()).isEqualTo(orig);
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

        @org.junit.jupiter.api.BeforeEach
        void seedUser() {
            user = User.builder().telegramChatId(100L).timezone("UTC").build();
            // user.id выставляем рефлексией — в production его генерит БД, в моках не нужен,
            // но handler передаёт его в plantService.markBulkCareDone, поэтому ловим any().
            when(userService.findByChatId(100L)).thenReturn(Optional.of(user));
        }

        @Test
        @DisplayName("3 растения обновились — присылаем итоговое сообщение и алёрт 'Готово!'")
        void shouldSendResultMessageOnSuccess() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:bulk_done:5");
            when(plantService.markBulkCareDone(any(), eq(5L), eq(TaskType.WATERING)))
                    .thenReturn(new PlantService.BulkCareDoneResult(3, 0, "🛋 Гостиная"));

            service.handleCallback(callbackQuery, telegramClient);

            ArgumentCaptor<org.telegram.telegrambots.meta.api.methods.send.SendMessage> sendCap =
                    ArgumentCaptor.forClass(
                            org.telegram.telegrambots.meta.api.methods.send.SendMessage.class);
            verify(telegramClient).execute(sendCap.capture());
            assertThat(sendCap.getValue().getText())
                    .contains("Готово")
                    .contains("3")
                    .contains("🛋 Гостиная");

            ArgumentCaptor<AnswerCallbackQuery> alertCap = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
            verify(telegramClient).execute(alertCap.capture());
            assertThat(alertCap.getValue().getText()).contains("Готово");

            // Карточку локации НЕ редактируем (по ТЗ).
            verify(telegramClient, never()).execute(any(EditMessageText.class));
        }

        @Test
        @DisplayName("Double-click: всё deduped — алёрт 'Уже полито', сообщение не шлётся")
        void shouldShowAlreadyDoneOnFullDedup() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:bulk_done:5");
            when(plantService.markBulkCareDone(any(), eq(5L), eq(TaskType.WATERING)))
                    .thenReturn(new PlantService.BulkCareDoneResult(0, 3, "🛋 Гостиная"));

            service.handleCallback(callbackQuery, telegramClient);

            ArgumentCaptor<AnswerCallbackQuery> cap = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
            verify(telegramClient).execute(cap.capture());
            assertThat(cap.getValue().getText()).contains("Уже полито");

            verify(telegramClient, never()).execute(
                    any(org.telegram.telegrambots.meta.api.methods.send.SendMessage.class));
        }

        @Test
        @DisplayName("Пустая локация — алёрт 'нечего поливать'")
        void shouldShowEmptyLocationAlert() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:bulk_done:5");
            when(plantService.markBulkCareDone(any(), eq(5L), eq(TaskType.WATERING)))
                    .thenReturn(new PlantService.BulkCareDoneResult(0, 0, null));

            service.handleCallback(callbackQuery, telegramClient);

            ArgumentCaptor<AnswerCallbackQuery> cap = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
            verify(telegramClient).execute(cap.capture());
            assertThat(cap.getValue().getText()).contains("нечего поливать");

            verify(telegramClient, never()).execute(
                    any(org.telegram.telegrambots.meta.api.methods.send.SendMessage.class));
        }

        @Test
        @DisplayName("Битый locationId — алёрт об ошибке, plantService не дёргается")
        void shouldRejectMalformedLocationId() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:bulk_done:abc");

            service.handleCallback(callbackQuery, telegramClient);

            ArgumentCaptor<AnswerCallbackQuery> cap = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
            verify(telegramClient).execute(cap.capture());
            assertThat(cap.getValue().getText()).contains("❌");

            verify(plantService, never()).markBulkCareDone(any(), any(), any());
        }

        @Test
        @DisplayName("Неизвестный chatId — алёрт 'Сначала /start', plantService не дёргается")
        void shouldHandleUnknownUser() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:bulk_done:5");
            when(userService.findByChatId(100L)).thenReturn(Optional.empty());

            service.handleCallback(callbackQuery, telegramClient);

            ArgumentCaptor<AnswerCallbackQuery> cap = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
            verify(telegramClient).execute(cap.capture());
            assertThat(cap.getValue().getText()).contains("/start");

            verify(plantService, never()).markBulkCareDone(any(), any(), any());
        }

        @Test
        @DisplayName("Pluralization: 1 → 'растения', 5 → 'растений', 21 → 'растения'")
        void shouldPluralizeCorrectly() throws TelegramApiException {
            // 1 → "растения"
            when(callbackQuery.getData()).thenReturn("v1:bulk_done:5");
            when(plantService.markBulkCareDone(any(), eq(5L), eq(TaskType.WATERING)))
                    .thenReturn(new PlantService.BulkCareDoneResult(1, 0, "🛋 Гостиная"));
            service.handleCallback(callbackQuery, telegramClient);

            // 5 → "растений"
            when(plantService.markBulkCareDone(any(), eq(5L), eq(TaskType.WATERING)))
                    .thenReturn(new PlantService.BulkCareDoneResult(5, 0, "🛋 Гостиная"));
            service.handleCallback(callbackQuery, telegramClient);

            // 21 → "растения"
            when(plantService.markBulkCareDone(any(), eq(5L), eq(TaskType.WATERING)))
                    .thenReturn(new PlantService.BulkCareDoneResult(21, 0, "🛋 Гостиная"));
            service.handleCallback(callbackQuery, telegramClient);

            ArgumentCaptor<org.telegram.telegrambots.meta.api.methods.send.SendMessage> cap =
                    ArgumentCaptor.forClass(
                            org.telegram.telegrambots.meta.api.methods.send.SendMessage.class);
            verify(telegramClient, times(3)).execute(cap.capture());
            assertThat(cap.getAllValues().get(0).getText()).contains("1 растения");
            assertThat(cap.getAllValues().get(1).getText()).contains("5 растений");
            assertThat(cap.getAllValues().get(2).getText()).contains("21 растения");
        }
    }
}