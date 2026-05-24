package com.plantcare.bot.service;

import com.plantcare.bot.domain.CareHistory;
import com.plantcare.bot.domain.CareSchedule;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.TaskType;
import com.plantcare.bot.metrics.MetricsService;
import com.plantcare.bot.repository.CareHistoryRepository;
import com.plantcare.bot.repository.CareScheduleRepository;
import com.plantcare.bot.seasonal.service.SeasonalIntervalService;
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
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("NotificationCallbackService: SOIL_CHECK (issue #74)")
class NotificationCallbackServiceSoilCheckTest {

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

    @Mock
    private MetricsService metricsService;

    @InjectMocks
    private NotificationCallbackService service;

    private CareSchedule soilSchedule;
    private CareSchedule wateringSchedule;
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

        soilSchedule = CareSchedule.builder()
                .plant(plant)
                .taskType(TaskType.SOIL_CHECK)
                .intervalDays(3)
                .nextDueAt(LocalDateTime.now().minusHours(1))
                .active(true)
                .build();

        wateringSchedule = CareSchedule.builder()
                .plant(plant)
                .taskType(TaskType.WATERING)
                .intervalDays(7)
                .nextDueAt(LocalDateTime.now().plusDays(2))
                .active(true)
                .build();

        when(callbackQuery.getId()).thenReturn("cb-soil");
        when(callbackQuery.getMessage()).thenReturn(message);
        when(message.getChatId()).thenReturn(100L);
        when(message.getMessageId()).thenReturn(77);

        when(seasonalIntervalService.effectiveIntervalDays(
                any(Plant.class),
                any(User.class),
                anyInt()
        )).thenAnswer(invocation -> invocation.getArgument(2));
    }

    @Nested
    @DisplayName("soil_dry")
    class SoilDry {

        @Test
        @DisplayName("Пишет CareHistory с note=soil:DRY и сдвигает schedule на now+interval")
        void shouldRecordDryAndReschedule() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:soil_dry:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(soilSchedule));
            when(careHistoryRepository.findFirstByPlantIdAndTaskTypeOrderByDoneAtDesc(any(), any()))
                    .thenReturn(Optional.empty());
            when(careScheduleRepository.findByPlantIdAndTaskType(any(), any()))
                    .thenReturn(Optional.of(wateringSchedule));

            LocalDateTime before = LocalDateTime.now();

            service.handleCallback(callbackQuery, telegramClient);

            ArgumentCaptor<CareHistory> historyCaptor = ArgumentCaptor.forClass(CareHistory.class);
            verify(careHistoryRepository).save(historyCaptor.capture());

            CareHistory history = historyCaptor.getValue();
            assertThat(history.getTaskType()).isEqualTo(TaskType.SOIL_CHECK);
            assertThat(history.getNote()).isEqualTo("soil:DRY");
            assertThat(history.getPlant()).isEqualTo(plant);

            assertThat(soilSchedule.getNextDueAt()).isAfter(before.plusDays(2).plusHours(23));
            verify(careScheduleRepository).save(soilSchedule);
        }

        @Test
        @DisplayName("Editит оригинальное сообщение — итог проверки, без кнопок")
        void shouldEditOriginalMessage() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:soil_dry:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(soilSchedule));
            when(careHistoryRepository.findFirstByPlantIdAndTaskTypeOrderByDoneAtDesc(any(), any()))
                    .thenReturn(Optional.empty());
            when(careScheduleRepository.findByPlantIdAndTaskType(any(), any()))
                    .thenReturn(Optional.empty());

            service.handleCallback(callbackQuery, telegramClient);

            ArgumentCaptor<EditMessageText> editCaptor = ArgumentCaptor.forClass(EditMessageText.class);
            verify(telegramClient).execute(editCaptor.capture());

            assertThat(editCaptor.getValue().getText())
                    .contains("Грунт сухой")
                    .contains("Следующая проверка");
            assertThat(editCaptor.getValue().getReplyMarkup()).isNull();
        }

        @Test
        @DisplayName("При активном WATERING-расписании шлёт отдельное сообщение с CTA «Полить сейчас»")
        void shouldSendFollowUpWithWaterCta() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:soil_dry:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(soilSchedule));
            when(careHistoryRepository.findFirstByPlantIdAndTaskTypeOrderByDoneAtDesc(any(), any()))
                    .thenReturn(Optional.empty());

            ReflectionTestUtils.setField(wateringSchedule, "id", 42L);

            when(careScheduleRepository.findByPlantIdAndTaskType(plant.getId(), TaskType.WATERING))
                    .thenReturn(Optional.of(wateringSchedule));

            service.handleCallback(callbackQuery, telegramClient);

            ArgumentCaptor<SendMessage> sendCaptor = ArgumentCaptor.forClass(SendMessage.class);
            verify(telegramClient).execute(sendCaptor.capture());

            SendMessage sent = sendCaptor.getValue();
            assertThat(sent.getText()).contains("пора полить");

            InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) sent.getReplyMarkup();
            List<String> callbacks = keyboard.getKeyboard().stream()
                    .flatMap(Collection::stream)
                    .map(InlineKeyboardButton::getCallbackData)
                    .toList();

            assertThat(callbacks).contains("v1:soil_water:42");
            assertThat(callbacks).contains("v1:snooze:42");
        }

        @Test
        @DisplayName("Если активного WATERING-расписания нет — CTA не присылается")
        void shouldNotSendCtaIfNoWatering() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:soil_dry:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(soilSchedule));
            when(careHistoryRepository.findFirstByPlantIdAndTaskTypeOrderByDoneAtDesc(any(), any()))
                    .thenReturn(Optional.empty());
            when(careScheduleRepository.findByPlantIdAndTaskType(plant.getId(), TaskType.WATERING))
                    .thenReturn(Optional.empty());

            service.handleCallback(callbackQuery, telegramClient);

            verify(telegramClient, never()).execute(any(SendMessage.class));
        }
    }

    @Nested
    @DisplayName("soil_wet")
    class SoilWet {

        @Test
        @DisplayName("Пишет CareHistory с note=soil:WET, edit сообщения без CTA")
        void shouldRecordWet() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:soil_wet:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(soilSchedule));
            when(careHistoryRepository.findFirstByPlantIdAndTaskTypeOrderByDoneAtDesc(any(), any()))
                    .thenReturn(Optional.empty());

            service.handleCallback(callbackQuery, telegramClient);

            ArgumentCaptor<CareHistory> historyCaptor = ArgumentCaptor.forClass(CareHistory.class);
            verify(careHistoryRepository).save(historyCaptor.capture());

            assertThat(historyCaptor.getValue().getNote()).isEqualTo("soil:WET");

            ArgumentCaptor<EditMessageText> editCaptor = ArgumentCaptor.forClass(EditMessageText.class);
            verify(telegramClient).execute(editCaptor.capture());

            assertThat(editCaptor.getValue().getText())
                    .contains("влажный")
                    .contains("Полив пока не нужен");

            verify(telegramClient, never()).execute(any(SendMessage.class));
        }
    }

    @Nested
    @DisplayName("soil_unk")
    class SoilUnk {

        @Test
        @DisplayName("Пишет CareHistory с note=soil:UNKNOWN, edit с советом проверить пальцем")
        void shouldRecordUnknown() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:soil_unk:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(soilSchedule));
            when(careHistoryRepository.findFirstByPlantIdAndTaskTypeOrderByDoneAtDesc(any(), any()))
                    .thenReturn(Optional.empty());

            service.handleCallback(callbackQuery, telegramClient);

            ArgumentCaptor<CareHistory> historyCaptor = ArgumentCaptor.forClass(CareHistory.class);
            verify(careHistoryRepository).save(historyCaptor.capture());

            assertThat(historyCaptor.getValue().getNote()).isEqualTo("soil:UNKNOWN");

            ArgumentCaptor<EditMessageText> editCaptor = ArgumentCaptor.forClass(EditMessageText.class);
            verify(telegramClient).execute(editCaptor.capture());

            assertThat(editCaptor.getValue().getText()).contains("пальцем/палочкой");
        }
    }

    @Nested
    @DisplayName("soil_water (CTA после DRY)")
    class SoilWater {

        @Test
        @DisplayName("Переиспользует markScheduleDone для WATERING — записывает CareHistory(WATERING)")
        void shouldMarkWateringDone() throws TelegramApiException {
            ReflectionTestUtils.setField(wateringSchedule, "id", 42L);

            when(callbackQuery.getData()).thenReturn("v1:soil_water:42");
            when(careScheduleRepository.findById(42L)).thenReturn(Optional.of(wateringSchedule));
            when(careHistoryRepository.findFirstByPlantIdAndTaskTypeOrderByDoneAtDesc(any(), any()))
                    .thenReturn(Optional.empty());

            service.handleCallback(callbackQuery, telegramClient);

            ArgumentCaptor<CareHistory> historyCaptor = ArgumentCaptor.forClass(CareHistory.class);
            verify(careHistoryRepository).save(historyCaptor.capture());

            assertThat(historyCaptor.getValue().getTaskType()).isEqualTo(TaskType.WATERING);

            ArgumentCaptor<EditMessageText> editCaptor = ArgumentCaptor.forClass(EditMessageText.class);
            verify(telegramClient).execute(editCaptor.capture());

            assertThat(editCaptor.getValue().getText())
                    .contains("Полил")
                    .contains("Следующий полив");
        }

        @Test
        @DisplayName("Если передан id не-WATERING расписания — отказ, ничего не пишем")
        void shouldRejectWrongTypeSchedule() throws TelegramApiException {
            ReflectionTestUtils.setField(soilSchedule, "id", 1L);

            when(callbackQuery.getData()).thenReturn("v1:soil_water:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(soilSchedule));

            service.handleCallback(callbackQuery, telegramClient);

            verify(careHistoryRepository, never()).save(any());

            ArgumentCaptor<AnswerCallbackQuery> answerCaptor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
            verify(telegramClient).execute(answerCaptor.capture());

            assertThat(answerCaptor.getValue().getText()).contains("❌");
        }
    }

    @Nested
    @DisplayName("Дедупликация и edge cases")
    class Dedup {

        @Test
        @DisplayName("Повторный soil_dry в течение 60 сек — алёрт «Уже отмечено», без записи")
        void shouldDedupRapidPresses() throws TelegramApiException {
            CareHistory recent = CareHistory.builder()
                    .plant(plant)
                    .taskType(TaskType.SOIL_CHECK)
                    .doneAt(LocalDateTime.now().minusSeconds(10))
                    .onTime(true)
                    .note("soil:DRY")
                    .build();

            when(callbackQuery.getData()).thenReturn("v1:soil_dry:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(soilSchedule));
            when(careHistoryRepository.findFirstByPlantIdAndTaskTypeOrderByDoneAtDesc(any(), any()))
                    .thenReturn(Optional.of(recent));

            service.handleCallback(callbackQuery, telegramClient);

            verify(careHistoryRepository, never()).save(any());
            verify(careScheduleRepository, never()).save(any());

            ArgumentCaptor<AnswerCallbackQuery> answerCaptor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
            verify(telegramClient).execute(answerCaptor.capture());

            assertThat(answerCaptor.getValue().getText()).contains("Уже отмечено");
        }

        @Test
        @DisplayName("Архивированное растение — graceful, без записи")
        void shouldHandleArchivedPlant() throws TelegramApiException {
            plant.archive();

            when(callbackQuery.getData()).thenReturn("v1:soil_wet:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(soilSchedule));

            service.handleCallback(callbackQuery, telegramClient);

            verify(careHistoryRepository, never()).save(any());
            verify(careScheduleRepository, never()).save(any());

            ArgumentCaptor<EditMessageText> editCaptor = ArgumentCaptor.forClass(EditMessageText.class);
            verify(telegramClient).execute(editCaptor.capture());

            assertThat(editCaptor.getValue().getText()).contains("удалено");
        }

        @Test
        @DisplayName("Несуществующий scheduleId для soil_dry — отказ")
        void shouldHandleMissingSchedule() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:soil_dry:9999");
            when(careScheduleRepository.findById(9999L)).thenReturn(Optional.empty());

            service.handleCallback(callbackQuery, telegramClient);

            verify(careHistoryRepository, never()).save(any());

            ArgumentCaptor<AnswerCallbackQuery> answerCaptor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
            verify(telegramClient).execute(answerCaptor.capture());

            assertThat(answerCaptor.getValue().getText()).contains("не найдено");
        }

        @Test
        @DisplayName("Расписание не SOIL_CHECK — отказ (защита от подмены id в callback)")
        void shouldRejectWrongTypeSchedule() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:soil_dry:7");
            when(careScheduleRepository.findById(7L)).thenReturn(Optional.of(wateringSchedule));

            service.handleCallback(callbackQuery, telegramClient);

            verify(careHistoryRepository, never()).save(any());
        }
    }
}