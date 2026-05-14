package com.plantcare.bot.service;

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
import org.springframework.test.util.ReflectionTestUtils;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("NotificationCallbackService: acclimation soil callbacks (issue #75)")
class NotificationCallbackServiceAcclimationTest {

    @Mock private CareScheduleRepository careScheduleRepository;
    @Mock private CareHistoryRepository careHistoryRepository;
    @Mock private PlantService plantService;
    @Mock private UserService userService;
    @Mock private PlantCardService plantCardService;
    @Mock private PlantAcclimationService plantAcclimationService;
    @Mock private TelegramClient telegramClient;
    @Mock private CallbackQuery callbackQuery;
    @Mock private Message message;

    @InjectMocks
    private NotificationCallbackService service;

    private CareSchedule wateringSchedule;
    private Plant plant;

    @BeforeEach
    void setUp() {
        User user = User.builder().telegramChatId(100L).timezone("UTC").build();
        plant = Plant.builder().user(user).name("Монстера").build();
        ReflectionTestUtils.setField(plant, "id", 7L);
        plant.setAcclimationUntil(LocalDateTime.now().plusDays(14));

        wateringSchedule = CareSchedule.builder()
                .plant(plant)
                .taskType(TaskType.WATERING)
                .intervalDays(7)
                .nextDueAt(LocalDateTime.now().minusHours(1))
                .active(true)
                .build();
        ReflectionTestUtils.setField(wateringSchedule, "id", 42L);

        when(callbackQuery.getId()).thenReturn("cb-accl");
        when(callbackQuery.getMessage()).thenReturn(message);
        when(message.getChatId()).thenReturn(100L);
        when(message.getMessageId()).thenReturn(99);
    }

    @Nested
    @DisplayName("v1:accl_soil")
    class AccLimationSoil {

        @Test
        @DisplayName("DRY → переходит в обычный watering-details flow (edit с «как полил?»)")
        void shouldChainToWateringDetailsOnDry() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:accl_soil:42:DRY");
            when(careScheduleRepository.findById(42L)).thenReturn(Optional.of(wateringSchedule));

            service.handleCallback(callbackQuery, telegramClient);

            // CareHistory не пишем — это только пере́ход к следующему шагу.
            verify(careHistoryRepository, never()).save(any());

            ArgumentCaptor<EditMessageText> ecap = ArgumentCaptor.forClass(EditMessageText.class);
            verify(telegramClient).execute(ecap.capture());
            assertThat(ecap.getValue().getText()).contains("Монстера").contains("как полил");
        }

        @Test
        @DisplayName("WET → откладывает nextDueAt на +1 день, не пишет историю, показывает snooze-кнопку")
        void shouldSnoozeOnWet() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:accl_soil:42:WET");
            when(careScheduleRepository.findById(42L)).thenReturn(Optional.of(wateringSchedule));

            LocalDateTime before = LocalDateTime.now();
            service.handleCallback(callbackQuery, telegramClient);

            assertThat(wateringSchedule.getNextDueAt()).isAfter(before.plusHours(23));
            assertThat(wateringSchedule.getNextDueAt()).isBefore(before.plusDays(1).plusMinutes(1));
            verify(careScheduleRepository).save(wateringSchedule);
            verify(careHistoryRepository, never()).save(any());

            ArgumentCaptor<EditMessageText> ecap = ArgumentCaptor.forClass(EditMessageText.class);
            verify(telegramClient).execute(ecap.capture());
            assertThat(ecap.getValue().getText()).contains("не поливаем");
        }

        @Test
        @DisplayName("UNKNOWN → подсказка с инструкцией + snooze +1")
        void shouldShowHintOnUnknown() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:accl_soil:42:UNKNOWN");
            when(careScheduleRepository.findById(42L)).thenReturn(Optional.of(wateringSchedule));

            service.handleCallback(callbackQuery, telegramClient);

            ArgumentCaptor<EditMessageText> ecap = ArgumentCaptor.forClass(EditMessageText.class);
            verify(telegramClient).execute(ecap.capture());
            assertThat(ecap.getValue().getText())
                    .contains("палец")
                    .contains("2–3 см");

            verify(careHistoryRepository, never()).save(any());
            verify(careScheduleRepository).save(wateringSchedule);
        }

        @Test
        @DisplayName("Архивированное растение — alert, без записи")
        void shouldHandleArchivedPlant() throws TelegramApiException {
            plant.archive();
            when(callbackQuery.getData()).thenReturn("v1:accl_soil:42:WET");
            when(careScheduleRepository.findById(42L)).thenReturn(Optional.of(wateringSchedule));

            service.handleCallback(callbackQuery, telegramClient);

            verify(careScheduleRepository, never()).save(any());
            verify(careHistoryRepository, never()).save(any());
        }

        @Test
        @DisplayName("Неверный ответ → отказ")
        void shouldRejectUnknownAnswer() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:accl_soil:42:SOAKED");
            when(careScheduleRepository.findById(42L)).thenReturn(Optional.of(wateringSchedule));

            service.handleCallback(callbackQuery, telegramClient);

            verify(careScheduleRepository, never()).save(any());
            ArgumentCaptor<AnswerCallbackQuery> acap = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
            verify(telegramClient).execute(acap.capture());
            assertThat(acap.getValue().getText()).contains("❌");
        }
    }

    @Nested
    @DisplayName("v1:accl_checkin")
    class AcclimationCheckin {

        @Test
        @DisplayName("OK → благодарность, scheduleNextCheckin вызван")
        void shouldHandleOk() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:accl_checkin:7:OK");
            when(plantAcclimationService.findById(7L)).thenReturn(Optional.of(plant));

            service.handleCallback(callbackQuery, telegramClient);

            ArgumentCaptor<EditMessageText> ecap = ArgumentCaptor.forClass(EditMessageText.class);
            verify(telegramClient).execute(ecap.capture());
            assertThat(ecap.getValue().getText()).contains("Отлично");

            verify(plantAcclimationService).scheduleNextCheckin(plant);
        }

        @Test
        @DisplayName("WILT → советы про перелив, scheduleNextCheckin вызван")
        void shouldHandleWilt() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:accl_checkin:7:WILT");
            when(plantAcclimationService.findById(7L)).thenReturn(Optional.of(plant));

            service.handleCallback(callbackQuery, telegramClient);

            ArgumentCaptor<EditMessageText> ecap = ArgumentCaptor.forClass(EditMessageText.class);
            verify(telegramClient).execute(ecap.capture());
            assertThat(ecap.getValue().getText()).contains("перелив");

            verify(plantAcclimationService).scheduleNextCheckin(plant);
        }

        @Test
        @DisplayName("YELLOW → советы про жёлтые листья")
        void shouldHandleYellow() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:accl_checkin:7:YELLOW");
            when(plantAcclimationService.findById(7L)).thenReturn(Optional.of(plant));

            service.handleCallback(callbackQuery, telegramClient);

            ArgumentCaptor<EditMessageText> ecap = ArgumentCaptor.forClass(EditMessageText.class);
            verify(telegramClient).execute(ecap.capture());
            assertThat(ecap.getValue().getText()).contains("Жёлтые");

            verify(plantAcclimationService).scheduleNextCheckin(plant);
        }

        @Test
        @DisplayName("Растение не найдено → отказ, scheduleNextCheckin НЕ вызван")
        void shouldHandleMissingPlant() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:accl_checkin:999:OK");
            when(plantAcclimationService.findById(999L)).thenReturn(Optional.empty());

            service.handleCallback(callbackQuery, telegramClient);

            verify(plantAcclimationService, never()).scheduleNextCheckin(any());
        }
    }
}
