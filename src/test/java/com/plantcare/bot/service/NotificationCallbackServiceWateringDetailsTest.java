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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Тесты для расширенного «Полил»-flow (issue #71):
 * двухшаговая цепочка callback'ов с обильностью и сухостью грунта.
 *
 *   v1:done:<id>             → step 1 (для WATERING): спрашиваем «Обильно?»
 *   v1:wabund:<id>:<a>       → step 2: спрашиваем «Сухая?»
 *   v1:wsoil:<id>:<a>:<s>    → step 3: пишем историю, сдвигаем schedule
 *
 * Состояние между шагами stateless — abundance зашит в callback_data,
 * scheduleId передаётся в каждом шаге.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("NotificationCallbackService: двухшаговый полив (issue #71)")
class NotificationCallbackServiceWateringDetailsTest {

    @Mock private CareScheduleRepository careScheduleRepository;
    @Mock private CareHistoryRepository careHistoryRepository;
    @Mock private PlantService plantService;
    @Mock private UserService userService;
    @Mock private PlantCardService plantCardService;
    @Mock private TelegramClient telegramClient;
    @Mock private CallbackQuery callbackQuery;
    @Mock private Message message;

    @InjectMocks
    private NotificationCallbackService service;

    private CareSchedule wateringSchedule;
    private CareSchedule mistingSchedule;
    private Plant plant;

    @BeforeEach
    void setUp() {
        User user = User.builder().telegramChatId(100L).timezone("UTC").build();
        plant = Plant.builder().user(user).name("Монстера").build();

        wateringSchedule = CareSchedule.builder()
                .plant(plant)
                .taskType(TaskType.WATERING)
                .intervalDays(7)
                .nextDueAt(LocalDateTime.now().minusHours(1))
                .active(true)
                .build();
        ReflectionTestUtils.setField(wateringSchedule, "id", 42L);

        mistingSchedule = CareSchedule.builder()
                .plant(plant)
                .taskType(TaskType.MISTING)
                .intervalDays(3)
                .nextDueAt(LocalDateTime.now().minusHours(1))
                .active(true)
                .build();
        ReflectionTestUtils.setField(mistingSchedule, "id", 43L);

        when(callbackQuery.getId()).thenReturn("cb-w");
        when(callbackQuery.getMessage()).thenReturn(message);
        when(message.getChatId()).thenReturn(100L);
        when(message.getMessageId()).thenReturn(77);
    }

    // ===================================================================
    // Step 1: «Полил» для WATERING → вопрос об обильности (НЕ пишем history)
    // ===================================================================

    @Nested
    @DisplayName("Step 1: v1:done:<id> для WATERING")
    class StepOne {

        @Test
        @DisplayName("Editит сообщение в вопрос «Обильно?» с двумя кнопками wabund — историю НЕ пишем")
        void shouldAskAbundanceInsteadOfImmediatelyMarkingDone() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:done:42");
            when(careScheduleRepository.findById(42L)).thenReturn(Optional.of(wateringSchedule));

            service.handleCallback(callbackQuery, telegramClient);

            // История НЕ пишется на этом шаге
            verify(careHistoryRepository, never()).save(any());
            verify(careScheduleRepository, never()).save(any());

            // Edit с двумя кнопками HEAVY / NORMAL
            ArgumentCaptor<EditMessageText> ecap = ArgumentCaptor.forClass(EditMessageText.class);
            verify(telegramClient).execute(ecap.capture());
            EditMessageText edit = ecap.getValue();
            assertThat(edit.getText()).contains("Монстера").contains("как полил");

            InlineKeyboardMarkup kb = (InlineKeyboardMarkup) edit.getReplyMarkup();
            List<String> callbacks = kb.getKeyboard().stream()
                    .flatMap(Collection::stream)
                    .map(InlineKeyboardButton::getCallbackData)
                    .toList();
            assertThat(callbacks).contains("v1:wabund:42:HEAVY");
            assertThat(callbacks).contains("v1:wabund:42:NORMAL");
        }

        @Test
        @DisplayName("Для MISTING — старая логика: пишет историю сразу, без вопросов")
        void shouldNotInterceptDoneForMisting() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:done:43");
            when(careScheduleRepository.findById(43L)).thenReturn(Optional.of(mistingSchedule));
            when(careHistoryRepository.findFirstByPlantIdAndTaskTypeOrderByDoneAtDesc(any(), any()))
                    .thenReturn(Optional.empty());

            service.handleCallback(callbackQuery, telegramClient);

            // Для MISTING пишем сразу
            verify(careHistoryRepository).save(any(CareHistory.class));
            verify(careScheduleRepository).save(any(CareSchedule.class));
        }
    }

    // ===================================================================
    // Step 2: ответ на «Обильно?» → вопрос про сухость грунта
    // ===================================================================

    @Nested
    @DisplayName("Step 2: v1:wabund:<id>:<a>")
    class StepTwo {

        @Test
        @DisplayName("HEAVY → editит сообщение в «Земля сухая?» с тремя soil-кнопками, "
                   + "сохраняет abundance=HEAVY в callback_data")
        void shouldAskSoilDryAndPropagateAbundance() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:wabund:42:HEAVY");
            when(careScheduleRepository.findById(42L)).thenReturn(Optional.of(wateringSchedule));

            service.handleCallback(callbackQuery, telegramClient);

            verify(careHistoryRepository, never()).save(any());
            verify(careScheduleRepository, never()).save(any());

            ArgumentCaptor<EditMessageText> ecap = ArgumentCaptor.forClass(EditMessageText.class);
            verify(telegramClient).execute(ecap.capture());
            EditMessageText edit = ecap.getValue();
            assertThat(edit.getText()).contains("сухая");

            InlineKeyboardMarkup kb = (InlineKeyboardMarkup) edit.getReplyMarkup();
            List<String> callbacks = kb.getKeyboard().stream()
                    .flatMap(Collection::stream)
                    .map(InlineKeyboardButton::getCallbackData)
                    .toList();
            assertThat(callbacks).contains("v1:wsoil:42:HEAVY:DRY");
            assertThat(callbacks).contains("v1:wsoil:42:HEAVY:WET");
            assertThat(callbacks).contains("v1:wsoil:42:HEAVY:UNKNOWN");
        }

        @Test
        @DisplayName("Неверный abundance — алёрт об ошибке, история не трогается")
        void shouldRejectInvalidAbundance() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:wabund:42:CRAZY");
            when(careScheduleRepository.findById(42L)).thenReturn(Optional.of(wateringSchedule));

            service.handleCallback(callbackQuery, telegramClient);

            verify(careHistoryRepository, never()).save(any());
            ArgumentCaptor<AnswerCallbackQuery> acap = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
            verify(telegramClient).execute(acap.capture());
            assertThat(acap.getValue().getText()).contains("❌");
        }

        @Test
        @DisplayName("Расписание не WATERING — отказ (защита от подмены id)")
        void shouldRejectNonWateringSchedule() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:wabund:43:HEAVY");
            when(careScheduleRepository.findById(43L)).thenReturn(Optional.of(mistingSchedule));

            service.handleCallback(callbackQuery, telegramClient);

            verify(careHistoryRepository, never()).save(any());
        }
    }

    // ===================================================================
    // Step 3: финальный ответ — запись CareHistory с abundance+soil_dry
    // ===================================================================

    @Nested
    @DisplayName("Step 3: v1:wsoil:<id>:<a>:<s>")
    class StepThree {

        @Test
        @DisplayName("HEAVY + DRY → CareHistory(wasAbundant=true, soilWasDry=true), schedule сдвинут")
        void shouldRecordHeavyDry() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:wsoil:42:HEAVY:DRY");
            when(careScheduleRepository.findById(42L)).thenReturn(Optional.of(wateringSchedule));
            when(careHistoryRepository.findFirstByPlantIdAndTaskTypeOrderByDoneAtDesc(any(), any()))
                    .thenReturn(Optional.empty());

            LocalDateTime before = LocalDateTime.now();
            service.handleCallback(callbackQuery, telegramClient);

            ArgumentCaptor<CareHistory> hcap = ArgumentCaptor.forClass(CareHistory.class);
            verify(careHistoryRepository).save(hcap.capture());
            CareHistory h = hcap.getValue();
            assertThat(h.getTaskType()).isEqualTo(TaskType.WATERING);
            assertThat(h.getWasAbundant()).isTrue();
            assertThat(h.getSoilWasDry()).isTrue();

            // Schedule сдвинут на now + 7 дней
            assertThat(wateringSchedule.getNextDueAt()).isAfter(before.plusDays(6).plusHours(23));
            verify(careScheduleRepository).save(wateringSchedule);

            // Финальный edit — резюме без кнопок
            ArgumentCaptor<EditMessageText> ecap = ArgumentCaptor.forClass(EditMessageText.class);
            verify(telegramClient).execute(ecap.capture());
            EditMessageText edit = ecap.getValue();
            assertThat(edit.getText())
                    .contains("Полил")
                    .contains("обильно")
                    .contains("сухая")
                    .contains("Следующий полив");
            assertThat(edit.getReplyMarkup()).isNull();

            // После отметки шлём карточку растения новым сообщением (messageId=null)
            verify(plantCardService).showPlantCard(
                    eq(plant.getUser()), eq(plant.getId()),
                    isNull(), eq(PlantCardService.BACK_TO_LIST), eq(telegramClient)
            );
        }

        @Test
        @DisplayName("NORMAL + WET → wasAbundant=false, soilWasDry=false")
        void shouldRecordNormalWet() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:wsoil:42:NORMAL:WET");
            when(careScheduleRepository.findById(42L)).thenReturn(Optional.of(wateringSchedule));
            when(careHistoryRepository.findFirstByPlantIdAndTaskTypeOrderByDoneAtDesc(any(), any()))
                    .thenReturn(Optional.empty());

            service.handleCallback(callbackQuery, telegramClient);

            ArgumentCaptor<CareHistory> hcap = ArgumentCaptor.forClass(CareHistory.class);
            verify(careHistoryRepository).save(hcap.capture());
            CareHistory h = hcap.getValue();
            assertThat(h.getWasAbundant()).isFalse();
            assertThat(h.getSoilWasDry()).isFalse();

            verify(plantCardService).showPlantCard(
                    any(), any(), isNull(), eq(PlantCardService.BACK_TO_LIST), any()
            );
        }

        @Test
        @DisplayName("NORMAL + UNKNOWN → wasAbundant=false, soilWasDry=null")
        void shouldRecordUnknownSoilAsNull() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:wsoil:42:NORMAL:UNKNOWN");
            when(careScheduleRepository.findById(42L)).thenReturn(Optional.of(wateringSchedule));
            when(careHistoryRepository.findFirstByPlantIdAndTaskTypeOrderByDoneAtDesc(any(), any()))
                    .thenReturn(Optional.empty());

            service.handleCallback(callbackQuery, telegramClient);

            ArgumentCaptor<CareHistory> hcap = ArgumentCaptor.forClass(CareHistory.class);
            verify(careHistoryRepository).save(hcap.capture());
            CareHistory h = hcap.getValue();
            assertThat(h.getWasAbundant()).isFalse();
            assertThat(h.getSoilWasDry()).isNull();

            verify(plantCardService).showPlantCard(
                    any(), any(), isNull(), eq(PlantCardService.BACK_TO_LIST), any()
            );
        }

        @Test
        @DisplayName("Дедуп: вторая попытка в окне 60 сек — алёрт «Уже отмечено», без записи")
        void shouldDedupRapidPresses() throws TelegramApiException {
            CareHistory recent = CareHistory.builder()
                    .plant(plant)
                    .taskType(TaskType.WATERING)
                    .doneAt(LocalDateTime.now().minusSeconds(10))
                    .onTime(true)
                    .build();
            when(callbackQuery.getData()).thenReturn("v1:wsoil:42:HEAVY:DRY");
            when(careScheduleRepository.findById(42L)).thenReturn(Optional.of(wateringSchedule));
            when(careHistoryRepository.findFirstByPlantIdAndTaskTypeOrderByDoneAtDesc(any(), any()))
                    .thenReturn(Optional.of(recent));

            service.handleCallback(callbackQuery, telegramClient);

            verify(careHistoryRepository, never()).save(any());
            verify(careScheduleRepository, never()).save(any());
            verify(plantCardService, never()).showPlantCard(any(), any(), any(), any(), any());
            ArgumentCaptor<AnswerCallbackQuery> acap = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
            verify(telegramClient).execute(acap.capture());
            assertThat(acap.getValue().getText()).contains("Уже отмечено");
        }

        @Test
        @DisplayName("Архивированное растение — graceful: без записи, без сдвига schedule")
        void shouldHandleArchivedPlant() throws TelegramApiException {
            plant.archive();
            when(callbackQuery.getData()).thenReturn("v1:wsoil:42:HEAVY:DRY");
            when(careScheduleRepository.findById(42L)).thenReturn(Optional.of(wateringSchedule));

            service.handleCallback(callbackQuery, telegramClient);

            verify(careHistoryRepository, never()).save(any());
            verify(careScheduleRepository, never()).save(any());
            verify(plantCardService, never()).showPlantCard(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Неверный formatted soil — отказ")
        void shouldRejectInvalidSoil() throws TelegramApiException {
            when(callbackQuery.getData()).thenReturn("v1:wsoil:42:HEAVY:MUDDY");

            service.handleCallback(callbackQuery, telegramClient);

            verify(careHistoryRepository, never()).save(any());
            ArgumentCaptor<AnswerCallbackQuery> acap = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
            verify(telegramClient).execute(acap.capture());
            assertThat(acap.getValue().getText()).contains("❌");
        }
    }

    // ===================================================================
    // Bulk-полив остаётся быстрым — без вопросов (issue #19 + #71 решение)
    // ===================================================================

    @Test
    @DisplayName("startWateringDetailsFlow шлёт sendMessage с двумя abundance-кнопками")
    void shouldSendAbundanceQuestionForCardEntry() throws TelegramApiException {
        service.startWateringDetailsFlow(42L, "Монстера", 100L, telegramClient);

        ArgumentCaptor<SendMessage> scap = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(scap.capture());

        SendMessage sent = scap.getValue();
        assertThat(sent.getText()).contains("Монстера").contains("как полил");

        InlineKeyboardMarkup kb = (InlineKeyboardMarkup) sent.getReplyMarkup();
        List<String> callbacks = kb.getKeyboard().stream()
                .flatMap(Collection::stream)
                .map(InlineKeyboardButton::getCallbackData)
                .toList();
        assertThat(callbacks).contains("v1:wabund:42:HEAVY");
        assertThat(callbacks).contains("v1:wabund:42:NORMAL");
    }
}
