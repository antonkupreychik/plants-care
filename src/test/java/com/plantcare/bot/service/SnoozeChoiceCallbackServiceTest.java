package com.plantcare.bot.service;

import com.plantcare.core.service.PlantAcclimationService;
import com.plantcare.core.service.PlantService;
import com.plantcare.core.service.QuietHoursPolicy;
import com.plantcare.core.service.UserService;

import com.plantcare.core.domain.CareSchedule;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.metrics.MetricsService;
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
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты snooze-flow с выбором интервала (issue #118).
 *
 * <p>Покрывают три callback'а:
 * <ul>
 *   <li>{@code v1:snooze:<id>}      — клик «⏰ Отложить» открывает choice-клавиатуру.</li>
 *   <li>{@code v1:snooze_pick:<id>:<option>} — финальный выбор интервала
 *       (hour / evening / tomorrow), включая granted quiet-hours shift.</li>
 *   <li>{@code v1:snooze_back:<id>} — возврат к оригинальной клавиатуре напоминания.</li>
 * </ul>
 *
 * <p>Тесты {@link NotificationCallbackServiceTest#SnoozeAction} проверяют только
 * happy-path первого шага (открыли клавиатуру, не пишем историю). Здесь — все
 * остальные ветки, потому что добавлять восемь Nested-классов в тот файл было бы
 * нечитаемо.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Snooze-flow с выбором интервала (#118)")
class SnoozeChoiceCallbackServiceTest {

    @Mock private CareScheduleRepository careScheduleRepository;
    @Mock private CareHistoryRepository careHistoryRepository;
    @Mock private PlantService plantService;
    @Mock private UserService userService;
    @Mock private SeasonalIntervalService seasonalIntervalService;
    @Mock private PlantCardService plantCardService;
    @Mock private PlantAcclimationService plantAcclimationService;
    @Mock private ReminderKeyboardFactory reminderKeyboardFactory;
    @Mock private BackdatedCareCallbackService backdatedCareCallbackService;
    @Mock private MetricsService metricsService;

    @Mock private TelegramClient telegramClient;
    @Mock private CallbackQuery callbackQuery;
    @Mock private Message message;

    /**
     * Реальная политика — нужна, чтобы проверить честный quiet-hours shift,
     * а не верить, что в проде он сработает.
     */
    private final QuietHoursPolicy quietHoursPolicy = new QuietHoursPolicy();

    /**
     * Меняем в каждом тесте перед {@link #buildService()} — позволяет фиксировать
     * «сейчас» в нужной TZ юзера.
     */
    private Clock clock;

    private NotificationCallbackService service;

    private Plant plant;
    private User user;
    private CareSchedule schedule;

    @BeforeEach
    void setUp() {
        when(callbackQuery.getId()).thenReturn("cb-1");
        when(callbackQuery.getMessage()).thenReturn(message);
        when(message.getChatId()).thenReturn(100L);
        when(message.getMessageId()).thenReturn(7);

        user = User.builder()
                .telegramChatId(100L)
                .timezone("UTC")
                .quietHoursStart(java.time.LocalTime.of(22, 0))
                .quietHoursEnd(java.time.LocalTime.of(9, 0))
                .build();

        plant = Plant.builder()
                .user(user)
                .name("Монстера")
                .build();

        schedule = CareSchedule.builder()
                .plant(plant)
                .taskType(TaskType.WATERING)
                .intervalDays(7)
                .nextDueAt(LocalDateTime.of(2026, 5, 24, 10, 0))
                .active(true)
                .build();
    }

    /**
     * Пересоздаём сервис на каждом тесте, поскольку {@link #clock} мог поменяться
     * (для проверки разных моментов «сейчас»). Использовать @InjectMocks с
     * volatile-фиксированным clock нельзя.
     */
    private void buildService(Clock fixedClock) {
        this.clock = fixedClock;
        this.service = new NotificationCallbackService(
                careScheduleRepository,
                careHistoryRepository,
                plantService,
                userService,
                plantCardService,
                plantAcclimationService,
                seasonalIntervalService,
                quietHoursPolicy,
                reminderKeyboardFactory,
                backdatedCareCallbackService,
                fixedClock,
                metricsService
        );
    }

    // ====================================================================
    // Шаг 1: v1:snooze:<id> → клавиатура выбора, ничего не пишет в БД.
    // ====================================================================

    @Nested
    @DisplayName("v1:snooze:<id> — открывает выбор интервала")
    class OpenChoice {

        @Test
        @DisplayName("Показывает editMessageText с 3 опциями + кнопкой 'Назад'")
        void should_show_choice_keyboard_with_three_options_and_back() throws TelegramApiException {
            buildService(Clock.fixed(Instant.parse("2026-05-24T10:00:00Z"), ZoneOffset.UTC));
            when(callbackQuery.getData()).thenReturn("v1:snooze:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

            service.handleCallback(callbackQuery, telegramClient);

            ArgumentCaptor<EditMessageText> edit = ArgumentCaptor.forClass(EditMessageText.class);
            verify(telegramClient).execute(edit.capture());

            EditMessageText sent = edit.getValue();
            assertThat(sent.getText()).contains("Монстера").contains("когда напомнить");

            InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) sent.getReplyMarkup();
            List<InlineKeyboardRow> rows = keyboard.getKeyboard();
            // 3 опции в первом ряду + back во втором.
            assertThat(rows).hasSize(2);
            assertThat(rows.get(0)).hasSize(3);
            assertThat(rows.get(1)).hasSize(1);

            List<String> callbacks = rows.stream()
                    .flatMap(java.util.Collection::stream)
                    .map(InlineKeyboardButton::getCallbackData)
                    .toList();
            assertThat(callbacks).containsExactly(
                    "v1:snooze_pick:1:hour",
                    "v1:snooze_pick:1:evening",
                    "v1:snooze_pick:1:tomorrow",
                    "v1:snooze_back:1"
            );
        }

        @Test
        @DisplayName("Не пишет CareHistory и не двигает nextDueAt на этом шаге")
        void should_not_persist_anything_on_choice_open() {
            buildService(Clock.fixed(Instant.parse("2026-05-24T10:00:00Z"), ZoneOffset.UTC));
            when(callbackQuery.getData()).thenReturn("v1:snooze:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

            LocalDateTime originalNext = schedule.getNextDueAt();

            service.handleCallback(callbackQuery, telegramClient);

            assertThat(schedule.getNextDueAt()).isEqualTo(originalNext);
            verify(careScheduleRepository, never()).save(any());
            verify(careHistoryRepository, never()).save(any());
        }
    }

    // ====================================================================
    // Шаг 2: v1:snooze_pick:<id>:<option>
    // ====================================================================

    @Nested
    @DisplayName("v1:snooze_pick — итоговый сдвиг")
    class PickOption {

        @Test
        @DisplayName("hour: nextDueAt = now + 1h, без shift из quiet, сообщение содержит 'Напомню'")
        void should_set_next_due_one_hour_ahead_for_hour_option() throws TelegramApiException {
            // 14:00 UTC — далеко от quiet 22-09
            Instant now = Instant.parse("2026-05-24T14:00:00Z");
            buildService(Clock.fixed(now, ZoneOffset.UTC));
            when(callbackQuery.getData()).thenReturn("v1:snooze_pick:1:hour");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

            service.handleCallback(callbackQuery, telegramClient);

            verify(careScheduleRepository).save(schedule);
            LocalDateTime expected = LocalDateTime.ofInstant(
                    now.plusSeconds(3600), ZoneOffset.UTC);
            assertThat(schedule.getNextDueAt()).isEqualTo(expected);

            ArgumentCaptor<EditMessageText> edit = ArgumentCaptor.forClass(EditMessageText.class);
            verify(telegramClient).execute(edit.capture());
            assertThat(edit.getValue().getText())
                    .contains("Напомню")
                    .doesNotContain("сдвинуто из тихих часов");
        }

        @Test
        @DisplayName("evening в 14:00 UTC → сегодня 19:00 UTC")
        void should_set_evening_to_19_when_before_cutoff() {
            Instant now = Instant.parse("2026-05-24T14:00:00Z");
            buildService(Clock.fixed(now, ZoneOffset.UTC));
            when(callbackQuery.getData()).thenReturn("v1:snooze_pick:1:evening");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

            service.handleCallback(callbackQuery, telegramClient);

            // 19:00 UTC того же дня
            assertThat(schedule.getNextDueAt())
                    .isEqualTo(LocalDateTime.of(2026, 5, 24, 19, 0));
        }

        @Test
        @DisplayName("evening в 18:29 UTC — ещё ДО cutoff, evening = сегодня 19:00")
        void should_set_evening_to_today_when_just_before_cutoff() {
            Instant now = Instant.parse("2026-05-24T18:29:00Z");
            buildService(Clock.fixed(now, ZoneOffset.UTC));
            when(callbackQuery.getData()).thenReturn("v1:snooze_pick:1:evening");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

            service.handleCallback(callbackQuery, telegramClient);

            assertThat(schedule.getNextDueAt())
                    .isEqualTo(LocalDateTime.of(2026, 5, 24, 19, 0));
        }

        @Test
        @DisplayName("evening в 18:30 UTC — ровно cutoff, evening = ЗАВТРА 19:00")
        void should_roll_evening_to_tomorrow_when_at_cutoff_boundary() {
            Instant now = Instant.parse("2026-05-24T18:30:00Z");
            buildService(Clock.fixed(now, ZoneOffset.UTC));
            when(callbackQuery.getData()).thenReturn("v1:snooze_pick:1:evening");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

            service.handleCallback(callbackQuery, telegramClient);

            assertThat(schedule.getNextDueAt())
                    .isEqualTo(LocalDateTime.of(2026, 5, 25, 19, 0));
        }

        @Test
        @DisplayName("evening в 23:00 UTC при quiet 22-09 → завтра 19:00 (после cutoff, не двигается quiet)")
        void should_roll_evening_to_next_day_when_late_night() {
            Instant now = Instant.parse("2026-05-24T23:00:00Z");
            buildService(Clock.fixed(now, ZoneOffset.UTC));
            when(callbackQuery.getData()).thenReturn("v1:snooze_pick:1:evening");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

            service.handleCallback(callbackQuery, telegramClient);

            // 19:00 завтрашнего дня — за пределами quiet 22-09, не сдвигается.
            assertThat(schedule.getNextDueAt())
                    .isEqualTo(LocalDateTime.of(2026, 5, 25, 19, 0));
        }

        @Test
        @DisplayName("tomorrow в 14:00 → ровно +24h")
        void should_set_tomorrow_to_plus_24h() {
            Instant now = Instant.parse("2026-05-24T14:00:00Z");
            buildService(Clock.fixed(now, ZoneOffset.UTC));
            when(callbackQuery.getData()).thenReturn("v1:snooze_pick:1:tomorrow");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

            service.handleCallback(callbackQuery, telegramClient);

            assertThat(schedule.getNextDueAt())
                    .isEqualTo(LocalDateTime.of(2026, 5, 25, 14, 0));
        }

        @Test
        @DisplayName("quiet-hours overnight (22-09): tomorrow в 22:00 +24h попадает в quiet → shift на 09:00")
        void should_shift_tomorrow_out_of_overnight_quiet() throws TelegramApiException {
            Instant now = Instant.parse("2026-05-24T22:00:00Z");
            buildService(Clock.fixed(now, ZoneOffset.UTC));
            when(callbackQuery.getData()).thenReturn("v1:snooze_pick:1:tomorrow");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

            service.handleCallback(callbackQuery, telegramClient);

            // +24h = 25.05 22:00 — попадает в quiet → сдвиг на 09:00 26.05
            assertThat(schedule.getNextDueAt())
                    .isEqualTo(LocalDateTime.of(2026, 5, 26, 9, 0));

            ArgumentCaptor<EditMessageText> edit = ArgumentCaptor.forClass(EditMessageText.class);
            verify(telegramClient).execute(edit.capture());
            assertThat(edit.getValue().getText()).contains("сдвинуто из тихих часов");
        }

        @Test
        @DisplayName("quiet-hours daytime (12-14): hour в 11:30 → shift на 14:00, пометка в сообщении")
        void should_shift_hour_out_of_daytime_quiet() throws TelegramApiException {
            user.setQuietHoursStart(java.time.LocalTime.of(12, 0));
            user.setQuietHoursEnd(java.time.LocalTime.of(14, 0));

            Instant now = Instant.parse("2026-05-24T11:30:00Z");
            buildService(Clock.fixed(now, ZoneOffset.UTC));
            when(callbackQuery.getData()).thenReturn("v1:snooze_pick:1:hour");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

            service.handleCallback(callbackQuery, telegramClient);

            // +1h = 12:30 — внутри quiet 12-14, сдвигаем на 14:00.
            assertThat(schedule.getNextDueAt())
                    .isEqualTo(LocalDateTime.of(2026, 5, 24, 14, 0));

            ArgumentCaptor<EditMessageText> edit = ArgumentCaptor.forClass(EditMessageText.class);
            verify(telegramClient).execute(edit.capture());
            assertThat(edit.getValue().getText()).contains("сдвинуто из тихих часов");
        }

        @Test
        @DisplayName("evening для юзера Asia/Almaty (UTC+5) при 09:00 UTC (= 14:00 локально) → 14:00 UTC")
        void should_compute_evening_in_user_timezone() {
            user.setTimezone("Asia/Almaty");
            // 09:00 UTC = 14:00 Asia/Almaty (UTC+5, без DST)
            Instant now = Instant.parse("2026-05-24T09:00:00Z");
            buildService(Clock.fixed(now, ZoneOffset.UTC));
            when(callbackQuery.getData()).thenReturn("v1:snooze_pick:1:evening");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

            service.handleCallback(callbackQuery, telegramClient);

            // 19:00 в Almaty = 14:00 UTC того же календарного дня
            Instant expectedInstant = ZonedDateTime.of(2026, 5, 24, 19, 0, 0, 0,
                    ZoneId.of("Asia/Almaty")).toInstant();
            LocalDateTime expectedUtc = LocalDateTime.ofInstant(expectedInstant, ZoneOffset.UTC);
            assertThat(schedule.getNextDueAt()).isEqualTo(expectedUtc);
            assertThat(expectedUtc).isEqualTo(LocalDateTime.of(2026, 5, 24, 14, 0));
        }

        @Test
        @DisplayName("Расписание выключено (active=false) → не пишем nextDueAt, показываем 'Расписание отключено'")
        void should_reject_pick_when_schedule_inactive() throws TelegramApiException {
            schedule.setActive(false);
            Instant now = Instant.parse("2026-05-24T14:00:00Z");
            buildService(Clock.fixed(now, ZoneOffset.UTC));
            when(callbackQuery.getData()).thenReturn("v1:snooze_pick:1:hour");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

            LocalDateTime original = schedule.getNextDueAt();
            service.handleCallback(callbackQuery, telegramClient);

            assertThat(schedule.getNextDueAt()).isEqualTo(original);
            verify(careScheduleRepository, never()).save(any());

            ArgumentCaptor<EditMessageText> editCap = ArgumentCaptor.forClass(EditMessageText.class);
            ArgumentCaptor<AnswerCallbackQuery> answerCap = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
            verify(telegramClient).execute(editCap.capture());
            verify(telegramClient).execute(answerCap.capture());

            assertThat(editCap.getValue().getText()).contains("отключено");
            assertThat(answerCap.getValue().getText()).contains("отключено");
        }

        @Test
        @DisplayName("Неизвестный option → алёрт с ошибкой, schedule не двигается")
        void should_reject_unknown_option() throws TelegramApiException {
            Instant now = Instant.parse("2026-05-24T14:00:00Z");
            buildService(Clock.fixed(now, ZoneOffset.UTC));
            when(callbackQuery.getData()).thenReturn("v1:snooze_pick:1:bogus");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

            LocalDateTime original = schedule.getNextDueAt();
            service.handleCallback(callbackQuery, telegramClient);

            assertThat(schedule.getNextDueAt()).isEqualTo(original);
            verify(careScheduleRepository, never()).save(any());

            ArgumentCaptor<AnswerCallbackQuery> cap = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
            verify(telegramClient).execute(cap.capture());
            assertThat(cap.getValue().getText()).contains("Неизвестный вариант");
        }
    }

    // ====================================================================
    // Шаг 3: v1:snooze_back:<id>
    // ====================================================================

    @Nested
    @DisplayName("v1:snooze_back — возврат к оригинальной клавиатуре")
    class Back {

        @Test
        @DisplayName("Восстанавливает оригинальную reminder-клавиатуру через editMessageReplyMarkup")
        void should_restore_original_reminder_keyboard() throws TelegramApiException {
            buildService(Clock.fixed(Instant.parse("2026-05-24T10:00:00Z"), ZoneOffset.UTC));

            InlineKeyboardMarkup original = InlineKeyboardMarkup.builder()
                    .keyboardRow(new InlineKeyboardRow(
                            InlineKeyboardButton.builder().text("done").callbackData("v1:done:1").build()
                    ))
                    .build();
            when(reminderKeyboardFactory.buildReminderKeyboard(eq(1L), eq(TaskType.WATERING)))
                    .thenReturn(original);
            when(callbackQuery.getData()).thenReturn("v1:snooze_back:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

            service.handleCallback(callbackQuery, telegramClient);

            ArgumentCaptor<EditMessageReplyMarkup> cap = ArgumentCaptor.forClass(EditMessageReplyMarkup.class);
            verify(telegramClient).execute(cap.capture());
            assertThat(cap.getValue().getReplyMarkup()).isSameAs(original);

            verify(careScheduleRepository, never()).save(any());
        }

        @Test
        @DisplayName("Расписание выключено (active=false) → не восстанавливаем кнопки, пишем 'Расписание уже отключено'")
        void should_clear_keyboard_when_schedule_inactive() throws TelegramApiException {
            schedule.setActive(false);
            buildService(Clock.fixed(Instant.parse("2026-05-24T10:00:00Z"), ZoneOffset.UTC));
            when(callbackQuery.getData()).thenReturn("v1:snooze_back:1");
            when(careScheduleRepository.findById(1L)).thenReturn(Optional.of(schedule));

            service.handleCallback(callbackQuery, telegramClient);

            // Клавиатура напоминания не должна вообще строиться.
            verify(reminderKeyboardFactory, never())
                    .buildReminderKeyboard(any(), any());

            ArgumentCaptor<EditMessageText> editCap = ArgumentCaptor.forClass(EditMessageText.class);
            ArgumentCaptor<AnswerCallbackQuery> answerCap = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
            verify(telegramClient).execute(editCap.capture());
            verify(telegramClient).execute(answerCap.capture());

            assertThat(editCap.getValue().getText()).contains("отключено");
            assertThat(answerCap.getValue().getText()).contains("отключено");
        }

        @Test
        @DisplayName("Несуществующий scheduleId → ошибка алёрт, клавиатура не строится")
        void should_handle_missing_schedule() throws TelegramApiException {
            buildService(Clock.fixed(Instant.parse("2026-05-24T10:00:00Z"), ZoneOffset.UTC));
            when(callbackQuery.getData()).thenReturn("v1:snooze_back:9999");
            when(careScheduleRepository.findById(9999L)).thenReturn(Optional.empty());

            service.handleCallback(callbackQuery, telegramClient);

            verify(reminderKeyboardFactory, never())
                    .buildReminderKeyboard(any(), any());

            ArgumentCaptor<AnswerCallbackQuery> cap = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
            verify(telegramClient).execute(cap.capture());
            assertThat(cap.getValue().getText()).contains("не найдено");
        }
    }
}
