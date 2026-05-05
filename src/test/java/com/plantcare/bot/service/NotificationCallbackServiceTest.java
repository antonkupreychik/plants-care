package com.plantcare.bot.service;

import com.plantcare.bot.domain.CareSchedule;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.TaskType;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-тесты для NotificationCallbackService")
class NotificationCallbackServiceTest {

    @Mock private CareScheduleRepository careScheduleRepository;
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
                .taskType(TaskType.WATERING)
                .intervalDays(7)
                .nextDueAt(LocalDateTime.now().minusHours(1))
                .active(true)
                .build();

        lenient().when(callbackQuery.getId()).thenReturn("cb-123");
        lenient().when(callbackQuery.getMessage()).thenReturn(message);
        lenient().when(message.getChatId()).thenReturn(100L);
        lenient().when(message.getMessageId()).thenReturn(42);
    }

    @Nested
    @DisplayName("Действие: done")
    class DoneAction {

        @Test
        @DisplayName("Сдвигает nextDueAt на intervalDays от текущего времени")
        void shouldRescheduleOnDone() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:done:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

            LocalDateTime before = LocalDateTime.now();
            service.handleCallback(callbackQuery, telegramClient);

            // nextDueAt должен быть сдвинут примерно на 7 дней вперёд
            assertThat(schedule.getNextDueAt()).isAfter(before.plusDays(6));
            verify(careScheduleRepository).save(schedule);
        }

        @Test
        @DisplayName("Обновляет текст сообщения, убирает кнопки")
        void shouldEditMessageOnDone() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:done:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

            service.handleCallback(callbackQuery, telegramClient);

            ArgumentCaptor<EditMessageText> captor = ArgumentCaptor.forClass(EditMessageText.class);
            verify(telegramClient).execute(captor.capture());
            EditMessageText edit = captor.getValue();

            assertThat(edit.getText()).contains("✅").contains("Монстера").contains("готово");
            assertThat(edit.getReplyMarkup()).isNull();
        }
    }

    @Nested
    @DisplayName("Действие: snooze")
    class SnoozeAction {

        @Test
        @DisplayName("Сдвигает nextDueAt на 2 часа, не трогает intervalDays")
        void shouldSnoozeByTwoHours() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:snooze:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));
            int originalInterval = schedule.getIntervalDays();

            LocalDateTime before = LocalDateTime.now();
            service.handleCallback(callbackQuery, telegramClient);

            assertThat(schedule.getNextDueAt()).isAfter(before.plusHours(1));
            assertThat(schedule.getNextDueAt()).isBefore(before.plusHours(3));
            assertThat(schedule.getIntervalDays()).isEqualTo(originalInterval);
            verify(careScheduleRepository).save(schedule);
        }

        @Test
        @DisplayName("Текст сообщения содержит инфо об отложении")
        void shouldEditMessageOnSnooze() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:snooze:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

            service.handleCallback(callbackQuery, telegramClient);

            ArgumentCaptor<EditMessageText> captor = ArgumentCaptor.forClass(EditMessageText.class);
            verify(telegramClient).execute(captor.capture());

            assertThat(captor.getValue().getText()).contains("⏰").contains("Монстера");
        }
    }

    @Nested
    @DisplayName("Действие: skip")
    class SkipAction {

        @Test
        @DisplayName("Сдвигает nextDueAt на intervalDays (как done)")
        void shouldRescheduleOnSkip() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:skip:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

            LocalDateTime before = LocalDateTime.now();
            service.handleCallback(callbackQuery, telegramClient);

            assertThat(schedule.getNextDueAt()).isAfter(before.plusDays(6));
            verify(careScheduleRepository).save(schedule);
        }

        @Test
        @DisplayName("Текст содержит информацию о пропуске")
        void shouldEditMessageOnSkip() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:skip:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

            service.handleCallback(callbackQuery, telegramClient);

            ArgumentCaptor<EditMessageText> captor = ArgumentCaptor.forClass(EditMessageText.class);
            verify(telegramClient).execute(captor.capture());

            assertThat(captor.getValue().getText()).contains("❌").contains("пропущено");
        }
    }

    @Nested
    @DisplayName("Невалидные callback_data")
    class InvalidCallbacks {

        @Test
        @DisplayName("Неверный формат данных — отправляет ошибку")
        void shouldHandleInvalidFormat() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:invalid");

            service.handleCallback(callbackQuery, telegramClient);

            ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
            verify(telegramClient).execute(captor.capture());
            assertThat(captor.getValue().getText()).contains("❌");
            verify(careScheduleRepository, never()).save(any());
        }

        @Test
        @DisplayName("Несуществующий schedule — отправляет ошибку")
        void shouldHandleMissingSchedule() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:done:9999");
            when(careScheduleRepository.findById(9999L)).thenReturn(Optional.empty());

            service.handleCallback(callbackQuery, telegramClient);

            ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
            verify(telegramClient).execute(captor.capture());
            assertThat(captor.getValue().getText()).contains("❌");
        }

        @Test
        @DisplayName("Неизвестное действие — отправляет ошибку")
        void shouldHandleUnknownAction() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:unknown:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

            service.handleCallback(callbackQuery, telegramClient);

            // Только AnswerCallbackQuery, без EditMessageText
            verify(telegramClient).execute(any(AnswerCallbackQuery.class));
            verify(telegramClient, never()).execute(any(EditMessageText.class));
            verify(careScheduleRepository, never()).save(any());
        }

        @Test
        @DisplayName("Нечисловой ID — отправляет ошибку")
        void shouldHandleNonNumericId() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:done:abc");

            service.handleCallback(callbackQuery, telegramClient);

            ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
            verify(telegramClient).execute(captor.capture());
            assertThat(captor.getValue().getText()).contains("❌");
        }
    }

    @Nested
    @DisplayName("AnswerCallbackQuery")
    class CallbackAnswer {

        @Test
        @DisplayName("Всегда отвечает на callback, даже при ошибке TelegramApi")
        void shouldAnswerCallbackEvenOnEditFailure() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:done:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));
            when(telegramClient.execute(any(EditMessageText.class)))
                    .thenThrow(new TelegramApiException("edit failed"));

            service.handleCallback(callbackQuery, telegramClient);

            verify(telegramClient).execute(any(AnswerCallbackQuery.class));
        }
    }
}