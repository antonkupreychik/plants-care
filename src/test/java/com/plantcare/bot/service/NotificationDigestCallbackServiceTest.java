package com.plantcare.bot.service;

import com.plantcare.bot.domain.CareSchedule;
import com.plantcare.bot.domain.DigestTaskItem;
import com.plantcare.bot.domain.NotificationDigest;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.TaskType;
import com.plantcare.bot.repository.CareScheduleRepository;
import com.plantcare.bot.repository.NotificationDigestRepository;
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
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
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
@DisplayName("Unit-тесты для NotificationDigestCallbackService")
class NotificationDigestCallbackServiceTest {

    @Mock
    private NotificationDigestRepository notificationDigestRepository;

    @Mock
    private CareScheduleRepository careScheduleRepository;

    @Mock
    private NotificationCallbackService notificationCallbackService;

    @Mock
    private TelegramClient telegramClient;

    @InjectMocks
    private NotificationDigestCallbackService service;

    private User user;
    private Plant plant;
    private CareSchedule wateringSchedule;
    private CareSchedule mistingSchedule;
    private NotificationDigest digest;
    private CallbackQuery callbackQuery;
    private Message message;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .telegramChatId(100L)
                .timezone("Europe/Minsk")
                .build();

        plant = Plant.builder()
                .user(user)
                .name("Монстера")
                .build();

        wateringSchedule = CareSchedule.builder()
                .plant(plant)
                .taskType(TaskType.WATERING)
                .intervalDays(7)
                .nextDueAt(LocalDateTime.now().minusHours(1))
                .active(true)
                .build();

        mistingSchedule = CareSchedule.builder()
                .plant(plant)
                .taskType(TaskType.MISTING)
                .intervalDays(3)
                .nextDueAt(LocalDateTime.now().minusHours(1))
                .active(true)
                .build();

        ReflectionTestUtils.setField(user, "id", 1L);
        ReflectionTestUtils.setField(plant, "id", 10L);
        ReflectionTestUtils.setField(wateringSchedule, "id", 100L);
        ReflectionTestUtils.setField(mistingSchedule, "id", 101L);

        digest = NotificationDigest.builder()
                .userId(user.getId())
                .plantTaskIds(List.of(
                        new DigestTaskItem(
                                100L,
                                10L,
                                "Монстера",
                                TaskType.WATERING,
                                wateringSchedule.getNextDueAt()
                        ),
                        new DigestTaskItem(
                                101L,
                                10L,
                                "Монстера",
                                TaskType.MISTING,
                                mistingSchedule.getNextDueAt()
                        )
                ))
                .build();

        ReflectionTestUtils.setField(digest, "id", 500L);

        callbackQuery = mock(CallbackQuery.class);
        message = mock(Message.class);

        when(callbackQuery.getId()).thenReturn("callback-1");
        when(callbackQuery.getMessage()).thenReturn(message);
        when(message.getChatId()).thenReturn(100L);
        when(message.getMessageId()).thenReturn(77);
    }

    @Test
    @DisplayName("done_all отмечает все задачи из дайджеста")
    void shouldMarkAllTasksDone() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("digest:done_all:500");
        when(notificationDigestRepository.findById(500L)).thenReturn(Optional.of(digest));
        when(careScheduleRepository.findById(100L)).thenReturn(Optional.of(wateringSchedule));
        when(careScheduleRepository.findById(101L)).thenReturn(Optional.of(mistingSchedule));
        when(notificationCallbackService.markScheduleDone(any(CareSchedule.class), any()))
                .thenReturn(true);

        service.handleCallback(callbackQuery, telegramClient);

        verify(notificationCallbackService).markScheduleDone(eq(wateringSchedule), any());
        verify(notificationCallbackService).markScheduleDone(eq(mistingSchedule), any());

        ArgumentCaptor<EditMessageText> editCaptor = ArgumentCaptor.forClass(EditMessageText.class);
        verify(telegramClient).execute(editCaptor.capture());

        assertThat(editCaptor.getValue().getText())
                .contains("Все задачи из дайджеста отмечены");

        verify(telegramClient).execute(any(AnswerCallbackQuery.class));
    }

    @Test
    @DisplayName("Повторный done_all не создаёт новых отметок и отвечает Уже отмечено")
    void shouldNotDuplicateDoneAll() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("digest:done_all:500");
        when(notificationDigestRepository.findById(500L)).thenReturn(Optional.of(digest));
        when(careScheduleRepository.findById(100L)).thenReturn(Optional.of(wateringSchedule));
        when(careScheduleRepository.findById(101L)).thenReturn(Optional.of(mistingSchedule));
        when(notificationCallbackService.markScheduleDone(any(CareSchedule.class), any()))
                .thenReturn(false);

        service.handleCallback(callbackQuery, telegramClient);

        verify(notificationCallbackService, times(2))
                .markScheduleDone(any(CareSchedule.class), any());

        verify(telegramClient, never()).execute(any(EditMessageText.class));

        ArgumentCaptor<AnswerCallbackQuery> answerCaptor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(answerCaptor.capture());

        assertThat(answerCaptor.getValue().getText()).contains("Уже отмечено");
    }

    @Test
    @DisplayName("expand показывает задачи по одной с кнопками Сделал и Отложить")
    void shouldExpandDigestToSeparateTasks() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("digest:expand:500");
        when(notificationDigestRepository.findById(500L)).thenReturn(Optional.of(digest));
        when(careScheduleRepository.findById(100L)).thenReturn(Optional.of(wateringSchedule));
        when(careScheduleRepository.findById(101L)).thenReturn(Optional.of(mistingSchedule));

        service.handleCallback(callbackQuery, telegramClient);

        ArgumentCaptor<EditMessageText> editCaptor = ArgumentCaptor.forClass(EditMessageText.class);
        verify(telegramClient).execute(editCaptor.capture());

        EditMessageText edit = editCaptor.getValue();

        assertThat(edit.getText()).contains("На сегодня:");
        assertThat(edit.getText()).contains("Монстера — полить");
        assertThat(edit.getText()).contains("Монстера — опрыскать");

        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) edit.getReplyMarkup();

        List<String> buttonTexts = keyboard.getKeyboard().stream()
                .flatMap(Collection::stream)
                .map(InlineKeyboardButton::getText)
                .toList();

        List<String> callbacks = keyboard.getKeyboard().stream()
                .flatMap(Collection::stream)
                .map(InlineKeyboardButton::getCallbackData)
                .toList();

        assertThat(buttonTexts).contains("✅ Сделал");
        assertThat(buttonTexts).contains("⏰ Отложить");

        assertThat(callbacks).contains("v1:done:100");
        assertThat(callbacks).contains("v1:snooze:100");
        assertThat(callbacks).contains("v1:done:101");
        assertThat(callbacks).contains("v1:snooze:101");
    }

    @Test
    @DisplayName("expand не показывает задачу, если nextDueAt уже сдвинут вперёд")
    void shouldHideAlreadyCompletedTaskOnExpand() throws TelegramApiException {
        wateringSchedule.setNextDueAt(LocalDateTime.now().plusDays(7));

        when(callbackQuery.getData()).thenReturn("digest:expand:500");
        when(notificationDigestRepository.findById(500L)).thenReturn(Optional.of(digest));
        when(careScheduleRepository.findById(100L)).thenReturn(Optional.of(wateringSchedule));
        when(careScheduleRepository.findById(101L)).thenReturn(Optional.of(mistingSchedule));

        service.handleCallback(callbackQuery, telegramClient);

        ArgumentCaptor<EditMessageText> editCaptor = ArgumentCaptor.forClass(EditMessageText.class);
        verify(telegramClient).execute(editCaptor.capture());

        EditMessageText edit = editCaptor.getValue();

        assertThat(edit.getText()).doesNotContain("Монстера — полить");
        assertThat(edit.getText()).contains("Монстера — опрыскать");
    }

    @Test
    @DisplayName("Если digest не найден, отправляется ошибка")
    void shouldAnswerWhenDigestNotFound() throws TelegramApiException {
        when(callbackQuery.getData()).thenReturn("digest:expand:999");
        when(notificationDigestRepository.findById(999L)).thenReturn(Optional.empty());

        service.handleCallback(callbackQuery, telegramClient);

        ArgumentCaptor<AnswerCallbackQuery> answerCaptor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(answerCaptor.capture());

        assertThat(answerCaptor.getValue().getText()).contains("Дайджест не найден");
    }
}