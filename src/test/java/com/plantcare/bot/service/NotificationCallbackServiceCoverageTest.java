package com.plantcare.bot.service;

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
import com.plantcare.core.service.PlantAcclimationService;
import com.plantcare.core.service.PlantService;
import com.plantcare.core.service.QuietHoursPolicy;
import com.plantcare.core.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Дополнительные тесты для остаточных непокрытых веток {@link NotificationCallbackService}
 * (issue #11): snooze_pick интервалы (в т.ч. вечерний расчёт по Clock), accl_snooze
 * (полностью непокрытый ранее), краевые/ошибочные ветки accl_soil / accl_checkin /
 * wabund / wsoil / soil_water. НЕ дублирует то, что уже покрыто в
 * NotificationCallbackServiceTest / …AcclimationTest / …SoilCheckTest / …WateringDetailsTest.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Доп. unit-тесты для NotificationCallbackService — остаточные ветки")
class NotificationCallbackServiceCoverageTest {

    @Mock
    private CareScheduleRepository careScheduleRepository;
    @Mock
    private CareHistoryRepository careHistoryRepository;
    @Mock
    private PlantService plantService;
    @Mock
    private UserService userService;
    @Mock
    private PlantCardService plantCardService;
    @Mock
    private PlantAcclimationService plantAcclimationService;
    @Mock
    private SeasonalIntervalService seasonalIntervalService;
    @Mock
    private QuietHoursPolicy quietHoursPolicy;
    @Mock
    private ReminderKeyboardFactory reminderKeyboardFactory;
    @Mock
    private BackdatedCareCallbackService backdatedCareCallbackService;
    @Mock
    private MetricsService metricsService;
    @Mock
    private TelegramClient telegramClient;
    @Mock
    private CallbackQuery callbackQuery;
    @Mock
    private Message message;

    private CareSchedule schedule;
    private Plant plant;
    private User user;

    private NotificationCallbackService serviceWithClock(Clock clock) {
        return new NotificationCallbackService(
                careScheduleRepository, careHistoryRepository, plantService, userService,
                plantCardService, plantAcclimationService, seasonalIntervalService,
                quietHoursPolicy, reminderKeyboardFactory, backdatedCareCallbackService,
                clock, metricsService
        );
    }

    @BeforeEach
    void setUp() {
        user = User.builder()
                .telegramChatId(100L)
                .timezone("Europe/Moscow")
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

        when(callbackQuery.getId()).thenReturn("cb-1");
        when(callbackQuery.getMessage()).thenReturn(message);
        when(message.getChatId()).thenReturn(100L);
        when(message.getMessageId()).thenReturn(42);

        when(seasonalIntervalService.effectiveIntervalDays(any(Plant.class), any(User.class), anyInt()))
                .thenAnswer(inv -> inv.getArgument(2));
    }

    // ==================== snooze_pick: интервалы (issue #118) ====================

    @Test
    @DisplayName("snooze_pick:hour сдвигает nextDueAt ровно на 1 час от Clock.instant()")
    void should_setNextDueOneHourAhead_when_optionIsHour() {
        Instant fixedNow = Instant.parse("2026-03-10T10:00:00Z");
        NotificationCallbackService service = serviceWithClock(Clock.fixed(fixedNow, ZoneOffset.UTC));
        when(quietHoursPolicy.shiftOutOfQuietHours(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(callbackQuery.getData()).thenReturn("v1:snooze_pick:1:hour");
        when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

        service.handleCallback(callbackQuery, telegramClient);

        ArgumentCaptor<CareSchedule> captor = ArgumentCaptor.forClass(CareSchedule.class);
        verify(careScheduleRepository).save(captor.capture());
        assertThat(captor.getValue().getNextDueAt())
                .isEqualTo(LocalDateTime.ofInstant(fixedNow.plusSeconds(3600), ZoneOffset.UTC));
        verify(metricsService).recordCallback("snooze_pick", CallbackOutcome.OK);
    }

    @Test
    @DisplayName("snooze_pick:evening до 18:30 по Москве — цель 19:00 сегодняшнего дня")
    void should_targetTodayEvening_when_beforeCutoffInUserZone() {
        // 12:00 MSK (UTC+3) = 09:00 UTC — задолго до локального порога 18:30.
        Instant fixedNow = Instant.parse("2026-03-10T09:00:00Z");
        NotificationCallbackService service = serviceWithClock(Clock.fixed(fixedNow, ZoneOffset.UTC));
        when(quietHoursPolicy.shiftOutOfQuietHours(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(callbackQuery.getData()).thenReturn("v1:snooze_pick:1:evening");
        when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

        service.handleCallback(callbackQuery, telegramClient);

        ArgumentCaptor<CareSchedule> captor = ArgumentCaptor.forClass(CareSchedule.class);
        verify(careScheduleRepository).save(captor.capture());
        Instant expectedTarget = LocalDateTime.of(2026, 3, 10, 19, 0)
                .atZone(ZoneId.of("Europe/Moscow")).toInstant();
        assertThat(captor.getValue().getNextDueAt())
                .isEqualTo(LocalDateTime.ofInstant(expectedTarget, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("snooze_pick:evening после 18:30 по Москве — цель 19:00 СЛЕДУЮЩЕГО дня")
    void should_targetNextDayEvening_when_afterCutoffInUserZone() {
        // 20:00 MSK (UTC+3) = 17:00 UTC — после локального порога 18:30.
        Instant fixedNow = Instant.parse("2026-03-10T17:00:00Z");
        NotificationCallbackService service = serviceWithClock(Clock.fixed(fixedNow, ZoneOffset.UTC));
        when(quietHoursPolicy.shiftOutOfQuietHours(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(callbackQuery.getData()).thenReturn("v1:snooze_pick:1:evening");
        when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

        service.handleCallback(callbackQuery, telegramClient);

        ArgumentCaptor<CareSchedule> captor = ArgumentCaptor.forClass(CareSchedule.class);
        verify(careScheduleRepository).save(captor.capture());
        Instant expectedTarget = LocalDateTime.of(2026, 3, 11, 19, 0)
                .atZone(ZoneId.of("Europe/Moscow")).toInstant();
        assertThat(captor.getValue().getNextDueAt())
                .isEqualTo(LocalDateTime.ofInstant(expectedTarget, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("snooze_pick:evening для Asia/Almaty — расчёт использует TZ юзера, не сервера")
    void should_useUserTimezone_when_computingEveningTargetForNonMoscowZone() {
        user.setTimezone("Asia/Almaty");
        // 10:00 Almaty (UTC+6) = 04:00 UTC — до порога 18:30 локально.
        Instant fixedNow = Instant.parse("2026-06-01T04:00:00Z");
        NotificationCallbackService service = serviceWithClock(Clock.fixed(fixedNow, ZoneOffset.UTC));
        when(quietHoursPolicy.shiftOutOfQuietHours(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(callbackQuery.getData()).thenReturn("v1:snooze_pick:1:evening");
        when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

        service.handleCallback(callbackQuery, telegramClient);

        ArgumentCaptor<CareSchedule> captor = ArgumentCaptor.forClass(CareSchedule.class);
        verify(careScheduleRepository).save(captor.capture());
        Instant expectedTarget = LocalDateTime.of(2026, 6, 1, 19, 0)
                .atZone(ZoneId.of("Asia/Almaty")).toInstant();
        assertThat(captor.getValue().getNextDueAt())
                .isEqualTo(LocalDateTime.ofInstant(expectedTarget, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("snooze_pick: неизвестный вариант интервала — алёрт об ошибке, save не вызывается")
    void should_rejectUnknownOption_when_snoozePickOptionNotRecognized() throws TelegramApiException {
        NotificationCallbackService service = serviceWithClock(Clock.systemUTC());
        when(callbackQuery.getData()).thenReturn("v1:snooze_pick:1:banana");
        when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

        service.handleCallback(callbackQuery, telegramClient);

        verify(careScheduleRepository, never()).save(any());
        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Неизвестный вариант");
        verify(metricsService).recordCallback("snooze_pick", CallbackOutcome.ERROR);
    }

    @Test
    @DisplayName("snooze_pick: неверный формат (меньше частей) — алёрт, без обращения к репозиторию")
    void should_rejectMalformedFormat_when_snoozePickHasTooFewParts() throws TelegramApiException {
        NotificationCallbackService service = serviceWithClock(Clock.systemUTC());
        when(callbackQuery.getData()).thenReturn("v1:snooze_pick:1");

        service.handleCallback(callbackQuery, telegramClient);

        verify(careScheduleRepository, never()).findById(any());
        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Неверный формат");
        verify(metricsService).recordCallback("snooze_pick", CallbackOutcome.ERROR);
    }

    @Test
    @DisplayName("snooze_pick: нечисловой id расписания — алёрт «Неверный ID»")
    void should_rejectNonNumericId_when_snoozePickIdMalformed() throws TelegramApiException {
        NotificationCallbackService service = serviceWithClock(Clock.systemUTC());
        when(callbackQuery.getData()).thenReturn("v1:snooze_pick:abc:hour");

        service.handleCallback(callbackQuery, telegramClient);

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Неверный ID");
        verify(metricsService).recordCallback("snooze_pick", CallbackOutcome.ERROR);
    }

    @Test
    @DisplayName("snooze_pick на архивном растении — идемпотентно, без сдвига расписания")
    void should_treatAsIdempotent_when_snoozePickPlantArchived() throws TelegramApiException {
        plant.archive();
        NotificationCallbackService service = serviceWithClock(Clock.systemUTC());
        when(callbackQuery.getData()).thenReturn("v1:snooze_pick:1:hour");
        when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

        service.handleCallback(callbackQuery, telegramClient);

        verify(careScheduleRepository, never()).save(any());
        ArgumentCaptor<EditMessageText> editCap = ArgumentCaptor.forClass(EditMessageText.class);
        verify(telegramClient).execute(editCap.capture());
        assertThat(editCap.getValue().getText()).isEqualTo("Растение уже удалено");
        verify(metricsService).recordCallback("snooze_pick", CallbackOutcome.IDEMPOTENT);
    }

    @Test
    @DisplayName("snooze_pick: сдвиг из тихих часов отражается в тексте подтверждения")
    void should_mentionQuietHoursShift_when_quietHoursPolicyShiftsTarget() throws TelegramApiException {
        Instant fixedNow = Instant.parse("2026-03-10T10:00:00Z");
        NotificationCallbackService service = serviceWithClock(Clock.fixed(fixedNow, ZoneOffset.UTC));
        Instant shiftedTarget = fixedNow.plusSeconds(7200);
        when(quietHoursPolicy.shiftOutOfQuietHours(any(), any())).thenReturn(shiftedTarget);
        when(callbackQuery.getData()).thenReturn("v1:snooze_pick:1:hour");
        when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

        service.handleCallback(callbackQuery, telegramClient);

        ArgumentCaptor<EditMessageText> editCap = ArgumentCaptor.forClass(EditMessageText.class);
        verify(telegramClient).execute(editCap.capture());
        assertThat(editCap.getValue().getText()).contains("сдвинуто из тихих часов");
    }

    // ==================== accl_snooze (issue #75) — ранее без тестов ====================

    @Test
    @DisplayName("accl_snooze:3 — переносит nextDueAt на +3 дня и пишет «через 3 дня»")
    void should_postponeThreeDays_when_acclSnoozeValidDays() throws TelegramApiException {
        NotificationCallbackService service = serviceWithClock(Clock.systemUTC());
        when(callbackQuery.getData()).thenReturn("v1:accl_snooze:1:3");
        when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

        service.handleCallback(callbackQuery, telegramClient);

        ArgumentCaptor<CareSchedule> saveCap = ArgumentCaptor.forClass(CareSchedule.class);
        verify(careScheduleRepository).save(saveCap.capture());
        assertThat(saveCap.getValue().getNextDueAt()).isAfter(LocalDateTime.now().plusDays(2).minusMinutes(1));
        assertThat(saveCap.getValue().getNextDueAt()).isBefore(LocalDateTime.now().plusDays(4));

        ArgumentCaptor<EditMessageText> editCap = ArgumentCaptor.forClass(EditMessageText.class);
        verify(telegramClient).execute(editCap.capture());
        assertThat(editCap.getValue().getText()).contains("через 3 дня");
        verify(metricsService).recordCallback("accl_snooze", CallbackOutcome.OK);
    }

    @Test
    @DisplayName("accl_snooze:1 — правильная форма единственного числа «день»")
    void should_useSingularDayWord_when_acclSnoozeOneDay() throws TelegramApiException {
        NotificationCallbackService service = serviceWithClock(Clock.systemUTC());
        when(callbackQuery.getData()).thenReturn("v1:accl_snooze:1:1");
        when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

        service.handleCallback(callbackQuery, telegramClient);

        ArgumentCaptor<EditMessageText> editCap = ArgumentCaptor.forClass(EditMessageText.class);
        verify(telegramClient).execute(editCap.capture());
        assertThat(editCap.getValue().getText()).contains("через 1 день").doesNotContain("1 дня").doesNotContain("1 дней");
    }

    @Test
    @DisplayName("accl_snooze:5 — правильная форма множественного числа «дней»")
    void should_usePluralDaysWord_when_acclSnoozeFiveDays() throws TelegramApiException {
        NotificationCallbackService service = serviceWithClock(Clock.systemUTC());
        when(callbackQuery.getData()).thenReturn("v1:accl_snooze:1:5");
        when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

        service.handleCallback(callbackQuery, telegramClient);

        ArgumentCaptor<EditMessageText> editCap = ArgumentCaptor.forClass(EditMessageText.class);
        verify(telegramClient).execute(editCap.capture());
        assertThat(editCap.getValue().getText()).contains("через 5 дней");
    }

    @Test
    @DisplayName("accl_snooze: 0 дней — за пределами допустимого интервала, алёрт об ошибке")
    void should_rejectZeroDays_when_acclSnoozeBelowMinimum() throws TelegramApiException {
        NotificationCallbackService service = serviceWithClock(Clock.systemUTC());
        when(callbackQuery.getData()).thenReturn("v1:accl_snooze:1:0");

        service.handleCallback(callbackQuery, telegramClient);

        verify(careScheduleRepository, never()).findById(any());
        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Неверный интервал");
        verify(metricsService).recordCallback("accl_snooze", CallbackOutcome.ERROR);
    }

    @Test
    @DisplayName("accl_snooze: 8 дней — выше допустимого максимума (7), алёрт об ошибке")
    void should_rejectEightDays_when_acclSnoozeAboveMaximum() throws TelegramApiException {
        NotificationCallbackService service = serviceWithClock(Clock.systemUTC());
        when(callbackQuery.getData()).thenReturn("v1:accl_snooze:1:8");

        service.handleCallback(callbackQuery, telegramClient);

        verify(careScheduleRepository, never()).findById(any());
        verify(metricsService).recordCallback("accl_snooze", CallbackOutcome.ERROR);
    }

    @Test
    @DisplayName("accl_snooze: неверный формат — недостаточно частей")
    void should_rejectMalformedFormat_when_acclSnoozeTooFewParts() throws TelegramApiException {
        NotificationCallbackService service = serviceWithClock(Clock.systemUTC());
        when(callbackQuery.getData()).thenReturn("v1:accl_snooze:1");

        service.handleCallback(callbackQuery, telegramClient);

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Неверный формат");
        verify(metricsService).recordCallback("accl_snooze", CallbackOutcome.ERROR);
    }

    @Test
    @DisplayName("accl_snooze: нечисловой days — «Неверный формат», как и нечисловой id")
    void should_rejectNonNumericDays_when_acclSnoozeDaysMalformed() throws TelegramApiException {
        NotificationCallbackService service = serviceWithClock(Clock.systemUTC());
        when(callbackQuery.getData()).thenReturn("v1:accl_snooze:1:abc");

        service.handleCallback(callbackQuery, telegramClient);

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Неверный формат");
        verify(metricsService).recordCallback("accl_snooze", CallbackOutcome.ERROR);
    }

    @Test
    @DisplayName("accl_snooze: расписание не найдено")
    void should_rejectMissingSchedule_when_acclSnoozeScheduleNotFound() throws TelegramApiException {
        NotificationCallbackService service = serviceWithClock(Clock.systemUTC());
        when(callbackQuery.getData()).thenReturn("v1:accl_snooze:9999:3");
        when(careScheduleRepository.findById(9999L)).thenReturn(Optional.empty());

        service.handleCallback(callbackQuery, telegramClient);

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Расписание не найдено");
        verify(metricsService).recordCallback("accl_snooze", CallbackOutcome.ERROR);
    }

    // ==================== accl_soil — краевые случаи ====================

    @Test
    @DisplayName("accl_soil: неверный формат — недостаточно частей")
    void should_rejectMalformedFormat_when_acclSoilTooFewParts() throws TelegramApiException {
        NotificationCallbackService service = serviceWithClock(Clock.systemUTC());
        when(callbackQuery.getData()).thenReturn("v1:accl_soil:1");

        service.handleCallback(callbackQuery, telegramClient);

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Неверный формат");
        verify(metricsService).recordCallback("accl_soil", CallbackOutcome.ERROR);
    }

    @Test
    @DisplayName("accl_soil: нечисловой id — «Неверный ID»")
    void should_rejectNonNumericId_when_acclSoilIdMalformed() throws TelegramApiException {
        NotificationCallbackService service = serviceWithClock(Clock.systemUTC());
        when(callbackQuery.getData()).thenReturn("v1:accl_soil:abc:DRY");

        service.handleCallback(callbackQuery, telegramClient);

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Неверный ID");
        verify(metricsService).recordCallback("accl_soil", CallbackOutcome.ERROR);
    }

    @Test
    @DisplayName("accl_soil: расписание не WATERING — трактуется как «не найдено»")
    void should_rejectNonWateringSchedule_when_acclSoilWrongTaskType() throws TelegramApiException {
        schedule.setTaskType(TaskType.MISTING);
        NotificationCallbackService service = serviceWithClock(Clock.systemUTC());
        when(callbackQuery.getData()).thenReturn("v1:accl_soil:1:DRY");
        when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

        service.handleCallback(callbackQuery, telegramClient);

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Расписание не найдено");
        verify(metricsService).recordCallback("accl_soil", CallbackOutcome.ERROR);
    }

    @Test
    @DisplayName("accl_soil:DRY на архивном растении — идемпотентно, флоу полива не запускается")
    void should_treatAsIdempotent_when_acclSoilDryPlantArchived() throws TelegramApiException {
        plant.archive();
        NotificationCallbackService service = serviceWithClock(Clock.systemUTC());
        when(callbackQuery.getData()).thenReturn("v1:accl_soil:1:DRY");
        when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

        service.handleCallback(callbackQuery, telegramClient);

        ArgumentCaptor<EditMessageText> editCap = ArgumentCaptor.forClass(EditMessageText.class);
        verify(telegramClient).execute(editCap.capture());
        assertThat(editCap.getValue().getText()).isEqualTo("Растение уже удалено");
        assertThat(editCap.getValue().getReplyMarkup()).isNull();
        verify(metricsService).recordCallback("accl_soil", CallbackOutcome.IDEMPOTENT);
    }

    // ==================== accl_checkin — краевые случаи ====================

    @Test
    @DisplayName("accl_checkin: неверный формат — недостаточно частей")
    void should_rejectMalformedFormat_when_acclCheckinTooFewParts() throws TelegramApiException {
        NotificationCallbackService service = serviceWithClock(Clock.systemUTC());
        when(callbackQuery.getData()).thenReturn("v1:accl_checkin:7");

        service.handleCallback(callbackQuery, telegramClient);

        verify(plantAcclimationService, never()).findById(any());
        verify(metricsService).recordCallback("accl_checkin", CallbackOutcome.ERROR);
    }

    @Test
    @DisplayName("accl_checkin: нечисловой plantId — «Неверный ID»")
    void should_rejectNonNumericId_when_acclCheckinIdMalformed() throws TelegramApiException {
        NotificationCallbackService service = serviceWithClock(Clock.systemUTC());
        when(callbackQuery.getData()).thenReturn("v1:accl_checkin:abc:OK");

        service.handleCallback(callbackQuery, telegramClient);

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Неверный ID");
        verify(metricsService).recordCallback("accl_checkin", CallbackOutcome.ERROR);
    }

    @Test
    @DisplayName("accl_checkin на архивном растении — идемпотентно, следующий check-in не планируется")
    void should_treatAsIdempotent_when_acclCheckinPlantArchived() throws TelegramApiException {
        plant.archive();
        NotificationCallbackService service = serviceWithClock(Clock.systemUTC());
        when(callbackQuery.getData()).thenReturn("v1:accl_checkin:7:OK");
        when(plantAcclimationService.findById(7L)).thenReturn(Optional.of(plant));

        service.handleCallback(callbackQuery, telegramClient);

        verify(plantAcclimationService, never()).scheduleNextCheckin(any());
        ArgumentCaptor<EditMessageText> editCap = ArgumentCaptor.forClass(EditMessageText.class);
        verify(telegramClient).execute(editCap.capture());
        assertThat(editCap.getValue().getText()).isEqualTo("Растение уже удалено");
        verify(metricsService).recordCallback("accl_checkin", CallbackOutcome.IDEMPOTENT);
    }

    // ==================== wabund — краевые случаи (issue #71) ====================

    @Test
    @DisplayName("wabund: неверный формат — недостаточно частей")
    void should_rejectMalformedFormat_when_wabundTooFewParts() throws TelegramApiException {
        NotificationCallbackService service = serviceWithClock(Clock.systemUTC());
        when(callbackQuery.getData()).thenReturn("v1:wabund:1");

        service.handleCallback(callbackQuery, telegramClient);

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Неверный формат");
        verify(metricsService).recordCallback("wabund", CallbackOutcome.ERROR);
    }

    @Test
    @DisplayName("wabund: нечисловой id — «Неверный ID»")
    void should_rejectNonNumericId_when_wabundIdMalformed() throws TelegramApiException {
        NotificationCallbackService service = serviceWithClock(Clock.systemUTC());
        when(callbackQuery.getData()).thenReturn("v1:wabund:abc:HEAVY");

        service.handleCallback(callbackQuery, telegramClient);

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Неверный ID");
        verify(metricsService).recordCallback("wabund", CallbackOutcome.ERROR);
    }

    @Test
    @DisplayName("wabund: расписание не найдено")
    void should_rejectMissingSchedule_when_wabundScheduleNotFound() throws TelegramApiException {
        NotificationCallbackService service = serviceWithClock(Clock.systemUTC());
        when(callbackQuery.getData()).thenReturn("v1:wabund:9999:HEAVY");
        when(careScheduleRepository.findById(9999L)).thenReturn(Optional.empty());

        service.handleCallback(callbackQuery, telegramClient);

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Расписание не найдено");
        verify(metricsService).recordCallback("wabund", CallbackOutcome.ERROR);
    }

    @Test
    @DisplayName("wabund на архивном растении — идемпотентно")
    void should_treatAsIdempotent_when_wabundPlantArchived() throws TelegramApiException {
        plant.archive();
        NotificationCallbackService service = serviceWithClock(Clock.systemUTC());
        when(callbackQuery.getData()).thenReturn("v1:wabund:1:HEAVY");
        when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

        service.handleCallback(callbackQuery, telegramClient);

        ArgumentCaptor<EditMessageText> editCap = ArgumentCaptor.forClass(EditMessageText.class);
        verify(telegramClient).execute(editCap.capture());
        assertThat(editCap.getValue().getText()).isEqualTo("Растение уже удалено");
        verify(metricsService).recordCallback("wabund", CallbackOutcome.IDEMPOTENT);
    }

    // ==================== wsoil — краевые случаи (issue #71) ====================

    @Test
    @DisplayName("wsoil: неверный формат — недостаточно частей")
    void should_rejectMalformedFormat_when_wsoilTooFewParts() throws TelegramApiException {
        NotificationCallbackService service = serviceWithClock(Clock.systemUTC());
        when(callbackQuery.getData()).thenReturn("v1:wsoil:1:HEAVY");

        service.handleCallback(callbackQuery, telegramClient);

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Неверный формат");
        verify(metricsService).recordCallback("wsoil", CallbackOutcome.ERROR);
    }

    @Test
    @DisplayName("wsoil: нечисловой id — «Неверный ID»")
    void should_rejectNonNumericId_when_wsoilIdMalformed() throws TelegramApiException {
        NotificationCallbackService service = serviceWithClock(Clock.systemUTC());
        when(callbackQuery.getData()).thenReturn("v1:wsoil:abc:HEAVY:DRY");

        service.handleCallback(callbackQuery, telegramClient);

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Неверный ID");
        verify(metricsService).recordCallback("wsoil", CallbackOutcome.ERROR);
    }

    @Test
    @DisplayName("wsoil: расписание не найдено")
    void should_rejectMissingSchedule_when_wsoilScheduleNotFound() throws TelegramApiException {
        NotificationCallbackService service = serviceWithClock(Clock.systemUTC());
        when(callbackQuery.getData()).thenReturn("v1:wsoil:9999:HEAVY:DRY");
        when(careScheduleRepository.findById(9999L)).thenReturn(Optional.empty());

        service.handleCallback(callbackQuery, telegramClient);

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Расписание не найдено");
        verify(metricsService).recordCallback("wsoil", CallbackOutcome.ERROR);
    }

    @Test
    @DisplayName("wsoil на архивном растении — идемпотентно, история не пишется")
    void should_treatAsIdempotent_when_wsoilPlantArchived() throws TelegramApiException {
        plant.archive();
        NotificationCallbackService service = serviceWithClock(Clock.systemUTC());
        when(callbackQuery.getData()).thenReturn("v1:wsoil:1:HEAVY:DRY");
        when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

        service.handleCallback(callbackQuery, telegramClient);

        verify(careHistoryRepository, never()).save(any());
        ArgumentCaptor<EditMessageText> editCap = ArgumentCaptor.forClass(EditMessageText.class);
        verify(telegramClient).execute(editCap.capture());
        assertThat(editCap.getValue().getText()).isEqualTo("Растение уже удалено");
        verify(metricsService).recordCallback("wsoil", CallbackOutcome.IDEMPOTENT);
    }

    // ==================== soil_water — краевые случаи (issue #74) ====================

    @Test
    @DisplayName("soil_water: нечисловой id расписания полива — «Неверный ID»")
    void should_rejectNonNumericId_when_soilWaterIdMalformed() throws TelegramApiException {
        NotificationCallbackService service = serviceWithClock(Clock.systemUTC());
        when(callbackQuery.getData()).thenReturn("v1:soil_water:abc");

        service.handleCallback(callbackQuery, telegramClient);

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Неверный ID");
        verify(metricsService).recordCallback("soil_water", CallbackOutcome.ERROR);
    }

    @Test
    @DisplayName("soil_water: расписание полива не найдено")
    void should_rejectMissingSchedule_when_soilWaterScheduleNotFound() throws TelegramApiException {
        NotificationCallbackService service = serviceWithClock(Clock.systemUTC());
        when(callbackQuery.getData()).thenReturn("v1:soil_water:9999");
        when(careScheduleRepository.findById(9999L)).thenReturn(Optional.empty());

        service.handleCallback(callbackQuery, telegramClient);

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Расписание полива не найдено");
        verify(metricsService).recordCallback("soil_water", CallbackOutcome.ERROR);
    }

    @Test
    @DisplayName("soil_water на архивном растении — идемпотентно, история не пишется")
    void should_treatAsIdempotent_when_soilWaterPlantArchived() throws TelegramApiException {
        plant.archive();
        NotificationCallbackService service = serviceWithClock(Clock.systemUTC());
        when(callbackQuery.getData()).thenReturn("v1:soil_water:1");
        when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

        service.handleCallback(callbackQuery, telegramClient);

        verify(careHistoryRepository, never()).save(any());
        ArgumentCaptor<EditMessageText> editCap = ArgumentCaptor.forClass(EditMessageText.class);
        verify(telegramClient).execute(editCap.capture());
        assertThat(editCap.getValue().getText()).isEqualTo("🗑 Растение уже удалено.");
        verify(metricsService).recordCallback("soil_water", CallbackOutcome.IDEMPOTENT);
    }

    @Test
    @DisplayName("soil_water: уже отмечено недавно (double-tap) — идемпотентно, дата не сдвигается")
    void should_treatAsIdempotent_when_soilWaterAlreadyDoneRecently() throws TelegramApiException {
        NotificationCallbackService service = serviceWithClock(Clock.systemUTC());
        CareHistory recent = CareHistory.builder()
                .plant(plant)
                .taskType(TaskType.WATERING)
                .doneAt(LocalDateTime.now().minusSeconds(5))
                .build();
        when(careHistoryRepository.findFirstByPlantIdAndTaskTypeOrderByDoneAtDesc(any(), eq(TaskType.WATERING)))
                .thenReturn(Optional.of(recent));
        when(callbackQuery.getData()).thenReturn("v1:soil_water:1");
        when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

        service.handleCallback(callbackQuery, telegramClient);

        verify(careScheduleRepository, never()).save(any());
        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Уже отмечено");
        verify(metricsService).recordCallback("soil_water", CallbackOutcome.IDEMPOTENT);
    }

    @Test
    @DisplayName("soil_water: успешная отметка полива по CTA — переносит расписание, показывает дату в TZ юзера")
    void should_markWateringDoneAndShowNextDateInUserZone_when_soilWaterSucceeds() throws TelegramApiException {
        NotificationCallbackService service = serviceWithClock(Clock.systemUTC());
        when(careHistoryRepository.findFirstByPlantIdAndTaskTypeOrderByDoneAtDesc(any(), eq(TaskType.WATERING)))
                .thenReturn(Optional.empty());
        when(callbackQuery.getData()).thenReturn("v1:soil_water:1");
        when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

        service.handleCallback(callbackQuery, telegramClient);

        verify(careHistoryRepository).save(any(CareHistory.class));
        verify(careScheduleRepository).save(schedule);
        ArgumentCaptor<EditMessageText> editCap = ArgumentCaptor.forClass(EditMessageText.class);
        verify(telegramClient).execute(editCap.capture());
        assertThat(editCap.getValue().getText()).contains("Полил").contains("Монстера");
        verify(metricsService).recordCallback("soil_water", CallbackOutcome.OK);
    }
}
