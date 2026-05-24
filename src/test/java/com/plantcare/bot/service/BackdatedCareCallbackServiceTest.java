package com.plantcare.bot.service;

import com.plantcare.bot.domain.CareSchedule;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.domain.enums.TaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты Telegram-обвязки ретро-flow (issue #118).
 *
 * <p>Бизнес-логика (валидация окна, идемпотентность, UTC-полдень) живёт в
 * {@link BackdatedCareService} — здесь мокается и проверяется только что
 * callback-сервис делегирует ему правильные параметры и формирует корректные
 * сообщения юзеру.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BackdatedCareCallbackService (#118)")
class BackdatedCareCallbackServiceTest {

    @Mock private PlantService plantService;
    @Mock private UserService userService;
    @Mock private BackdatedCareService backdatedCareService;
    @Mock private TelegramClient client;

    private static final Instant FIXED_NOW = Instant.parse("2026-05-24T10:00:00Z");

    private BackdatedCareCallbackService service;
    private Plant plant;
    private User user;

    @BeforeEach
    void setUp() {
        service = new BackdatedCareCallbackService(
                plantService,
                userService,
                backdatedCareService,
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC)
        );

        user = User.builder()
                .telegramChatId(100L)
                .timezone("UTC")
                .build();
        setId(user, 42L);

        plant = Plant.builder()
                .user(user)
                .name("Монстера")
                .build();
        setId(plant, 7L);

        when(userService.findByChatId(100L)).thenReturn(Optional.of(user));
        when(plantService.getPlantForUser(42L, 7L)).thenReturn(Optional.of(plant));
    }

    // ====================================================================
    // STEP 1: v1:back_care:<plantId>
    // ====================================================================

    @Nested
    @DisplayName("v1:back_care:<plantId> — старт flow в карточке")
    class BackCareStart {

        @Test
        @DisplayName("1 активная задача → сразу date keyboard (4 пресета + 'Выбрать дату' + back)")
        void should_skip_task_type_choice_when_only_one_active_care_type() throws TelegramApiException {
            CareSchedule watering = scheduleFor(TaskType.WATERING);
            when(plantService.getActiveSchedules(7L)).thenReturn(List.of(watering));

            service.handleCallback("back_care", new String[]{"v1", "back_care", "7"},
                    100L, 555, "cb-1", client);

            ArgumentCaptor<EditMessageText> cap = ArgumentCaptor.forClass(EditMessageText.class);
            verify(client).execute(cap.capture());

            EditMessageText edit = cap.getValue();
            // Текст приглашения именно для date-keyboard, а не task-type-keyboard.
            assertThat(edit.getText()).contains("Когда").contains("полил");
            List<String> callbacks = callbacksOf(edit.getReplyMarkup());
            // 4 пресета + кастом + back
            assertThat(callbacks).containsExactly(
                    "v1:back_pick:7:WATERING:0",
                    "v1:back_pick:7:WATERING:1",
                    "v1:back_pick:7:WATERING:2",
                    "v1:back_pick:7:WATERING:3",
                    "v1:back_custom:7:WATERING",
                    "PLANT:VIEW:7"
            );
        }

        @Test
        @DisplayName("2+ активные задачи → промежуточный выбор taskType, дата ещё не предлагается")
        void should_show_task_type_chooser_when_multiple_active_care_types() throws TelegramApiException {
            CareSchedule watering = scheduleFor(TaskType.WATERING);
            CareSchedule misting = scheduleFor(TaskType.MISTING);
            when(plantService.getActiveSchedules(7L)).thenReturn(List.of(watering, misting));

            service.handleCallback("back_care", new String[]{"v1", "back_care", "7"},
                    100L, 555, "cb-1", client);

            ArgumentCaptor<EditMessageText> cap = ArgumentCaptor.forClass(EditMessageText.class);
            verify(client).execute(cap.capture());

            EditMessageText edit = cap.getValue();
            assertThat(edit.getText()).contains("Что именно");

            List<String> callbacks = callbacksOf(edit.getReplyMarkup());
            // -1 — синтетический маркер «после выбора покажи date keyboard».
            assertThat(callbacks).contains(
                    "v1:back_pick:7:WATERING:-1",
                    "v1:back_pick:7:MISTING:-1",
                    "PLANT:VIEW:7"
            );
        }

        @Test
        @DisplayName("Нет активных задач → сообщение 'нечего отмечать', date-keyboard не показывается")
        void should_show_empty_state_when_no_active_care() throws TelegramApiException {
            when(plantService.getActiveSchedules(7L)).thenReturn(List.of());

            service.handleCallback("back_care", new String[]{"v1", "back_care", "7"},
                    100L, 555, "cb-1", client);

            ArgumentCaptor<EditMessageText> cap = ArgumentCaptor.forClass(EditMessageText.class);
            verify(client).execute(cap.capture());
            assertThat(cap.getValue().getText()).contains("нет активных задач");
        }

        @Test
        @DisplayName("SOIL_CHECK игнорируется при выборе типа (ретро-отметка для него не имеет смысла)")
        void should_ignore_soil_check_in_care_type_choices() throws TelegramApiException {
            CareSchedule watering = scheduleFor(TaskType.WATERING);
            CareSchedule soilCheck = scheduleFor(TaskType.SOIL_CHECK);
            when(plantService.getActiveSchedules(7L)).thenReturn(List.of(watering, soilCheck));

            service.handleCallback("back_care", new String[]{"v1", "back_care", "7"},
                    100L, 555, "cb-1", client);

            ArgumentCaptor<EditMessageText> cap = ArgumentCaptor.forClass(EditMessageText.class);
            verify(client).execute(cap.capture());
            // Один тип после фильтра — сразу к датам, без taskType-chooser.
            assertThat(cap.getValue().getText()).contains("Когда");
        }

        @Test
        @DisplayName("Неизвестный chatId → алёрт '/start', plant не запрашивается")
        void should_reject_unknown_user() throws TelegramApiException {
            when(userService.findByChatId(100L)).thenReturn(Optional.empty());

            service.handleCallback("back_care", new String[]{"v1", "back_care", "7"},
                    100L, 555, "cb-1", client);

            verify(plantService, never()).getActiveSchedules(any());
            ArgumentCaptor<AnswerCallbackQuery> cap = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
            verify(client).execute(cap.capture());
            assertThat(cap.getValue().getText()).contains("/start");
        }
    }

    // ====================================================================
    // STEP 2: v1:back_pick:<plantId>:<taskType>:<daysAgo>
    // ====================================================================

    @Nested
    @DisplayName("v1:back_pick — выбор пресетной даты")
    class BackPick {

        @Test
        @DisplayName("daysAgo=1 (вчера) WATERING → service.recordBackdatedCare с правильной датой; на CREATED шлёт подтверждение")
        void should_delegate_to_service_and_send_confirmation_on_success() throws TelegramApiException {
            // 24.05.2026 - 1 = 23.05.2026 (в UTC-зоне юзера)
            LocalDate expectedDate = LocalDate.of(2026, 5, 23);
            when(backdatedCareService.recordBackdatedCare(eq(plant), eq(TaskType.WATERING), eq(expectedDate)))
                    .thenReturn(new BackdatedCareService.BackdatedCareResult(
                            BackdatedCareService.Outcome.CREATED, expectedDate,
                            java.time.LocalDateTime.of(2026, 5, 30, 12, 0)));

            service.handleCallback("back_pick",
                    new String[]{"v1", "back_pick", "7", "WATERING", "1"},
                    100L, 555, "cb-1", client);

            verify(backdatedCareService).recordBackdatedCare(plant, TaskType.WATERING, expectedDate);

            ArgumentCaptor<EditMessageText> cap = ArgumentCaptor.forClass(EditMessageText.class);
            verify(client).execute(cap.capture());
            assertThat(cap.getValue().getText())
                    .contains("✅")
                    .contains("полив")
                    .contains("23.05")
                    .contains("30.05.2026");
        }

        @Test
        @DisplayName("ALREADY_RECORDED → сообщение 'уже отмечено', service.recordBackdatedCare всё равно вызван (валидация на стороне сервиса)")
        void should_show_already_recorded_message() throws TelegramApiException {
            LocalDate yesterday = LocalDate.of(2026, 5, 23);
            when(backdatedCareService.recordBackdatedCare(any(), any(), any()))
                    .thenReturn(new BackdatedCareService.BackdatedCareResult(
                            BackdatedCareService.Outcome.ALREADY_RECORDED, yesterday, null));

            service.handleCallback("back_pick",
                    new String[]{"v1", "back_pick", "7", "WATERING", "1"},
                    100L, 555, "cb-1", client);

            verify(backdatedCareService).recordBackdatedCare(plant, TaskType.WATERING, yesterday);

            ArgumentCaptor<EditMessageText> editCap = ArgumentCaptor.forClass(EditMessageText.class);
            verify(client).execute(editCap.capture());
            assertThat(editCap.getValue().getText())
                    .contains("23.05")
                    .containsIgnoringCase("уже отмечено");
        }

        @Test
        @DisplayName("daysAgo=0 (сегодня) → сервис вызывается с today")
        void should_pass_today_for_daysAgo_zero() {
            LocalDate today = LocalDate.of(2026, 5, 24);
            when(backdatedCareService.recordBackdatedCare(any(), any(), any()))
                    .thenReturn(new BackdatedCareService.BackdatedCareResult(
                            BackdatedCareService.Outcome.CREATED, today,
                            java.time.LocalDateTime.of(2026, 5, 31, 12, 0)));

            service.handleCallback("back_pick",
                    new String[]{"v1", "back_pick", "7", "WATERING", "0"},
                    100L, 555, "cb-1", client);

            verify(backdatedCareService).recordBackdatedCare(plant, TaskType.WATERING, today);
        }

        @Test
        @DisplayName("daysAgo=-1 → показывает date keyboard, recordBackdatedCare НЕ вызывается")
        void should_show_date_keyboard_for_synthetic_minus_one_marker() throws TelegramApiException {
            service.handleCallback("back_pick",
                    new String[]{"v1", "back_pick", "7", "WATERING", "-1"},
                    100L, 555, "cb-1", client);

            verify(backdatedCareService, never()).recordBackdatedCare(any(), any(), any());

            ArgumentCaptor<EditMessageText> editCap = ArgumentCaptor.forClass(EditMessageText.class);
            verify(client).execute(editCap.capture());
            List<String> callbacks = callbacksOf(editCap.getValue().getReplyMarkup());
            assertThat(callbacks).contains("v1:back_pick:7:WATERING:0",
                    "v1:back_pick:7:WATERING:1");
        }

        @Test
        @DisplayName("daysAgo > MAX_BACKDATE_DAYS → алёрт 'Слишком давно', service не дёргается")
        void should_reject_too_old_days_ago() throws TelegramApiException {
            service.handleCallback("back_pick",
                    new String[]{"v1", "back_pick", "7", "WATERING", "10"},
                    100L, 555, "cb-1", client);

            verify(backdatedCareService, never()).recordBackdatedCare(any(), any(), any());

            ArgumentCaptor<AnswerCallbackQuery> cap = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
            verify(client).execute(cap.capture());
            assertThat(cap.getValue().getText()).contains("Слишком давно");
        }

        @Test
        @DisplayName("Битый формат (мало частей) → ошибка, service не дёргается")
        void should_reject_malformed_callback() throws TelegramApiException {
            service.handleCallback("back_pick",
                    new String[]{"v1", "back_pick", "7"},
                    100L, 555, "cb-1", client);

            verify(backdatedCareService, never()).recordBackdatedCare(any(), any(), any());

            ArgumentCaptor<AnswerCallbackQuery> cap = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
            verify(client).execute(cap.capture());
            assertThat(cap.getValue().getText()).contains("Неверный формат");
        }
    }

    // ====================================================================
    // STEP 3: v1:back_custom:<plantId>:<taskType>
    // ====================================================================

    @Nested
    @DisplayName("v1:back_custom — переход в state AWAITING_CARE_DATE")
    class BackCustom {

        @Test
        @DisplayName("Сохраняет plantId + taskType в stateData и переводит в AWAITING_CARE_DATE")
        void should_set_state_and_save_context() throws TelegramApiException {
            service.handleCallback("back_custom",
                    new String[]{"v1", "back_custom", "7", "WATERING"},
                    100L, 555, "cb-1", client);

            verify(userService).setStateData(user,
                    BackdatedCareCallbackService.STATE_PLANT_ID, "7");
            verify(userService).setStateData(user,
                    BackdatedCareCallbackService.STATE_TASK_TYPE, "WATERING");
            verify(userService).updateState(user, ConversationState.AWAITING_CARE_DATE);

            ArgumentCaptor<EditMessageText> editCap = ArgumentCaptor.forClass(EditMessageText.class);
            verify(client).execute(editCap.capture());
            assertThat(editCap.getValue().getText())
                    .contains("DD.MM")
                    .contains(String.valueOf(BackdatedCareService.MAX_BACKDATE_DAYS));
        }

        @Test
        @DisplayName("Невалидный taskType → ошибка, state НЕ выставляется")
        void should_reject_invalid_task_type() throws TelegramApiException {
            service.handleCallback("back_custom",
                    new String[]{"v1", "back_custom", "7", "NONSENSE"},
                    100L, 555, "cb-1", client);

            verify(userService, never()).updateState(any(), any());
            verify(userService, never()).setStateData(any(), anyString(), anyString());
        }
    }

    // ====================================================================
    // parseCareDate
    // ====================================================================

    @Nested
    @DisplayName("parseCareDate — DD.MM[.YYYY]")
    class ParseDate {

        @Test
        @DisplayName("DD.MM в прошлом этого же года → текущий год")
        void should_parse_short_format_as_current_year_when_in_past() {
            // today=2026-05-24, ввод 22.05 → 2026-05-22
            Optional<LocalDate> got = service.parseCareDate("22.05", user);
            assertThat(got).contains(LocalDate.of(2026, 5, 22));
        }

        @Test
        @DisplayName("DD.MM в будущем этого года → ПРЕДЫДУЩИЙ год (юзер 01.01 пишет 28.12)")
        void should_roll_short_format_to_previous_year_if_in_future() {
            // today=2026-05-24, ввод 28.12 → 2025-12-28 (а не 2026-12-28)
            Optional<LocalDate> got = service.parseCareDate("28.12", user);
            assertThat(got).contains(LocalDate.of(2025, 12, 28));
        }

        @Test
        @DisplayName("DD.MM.YYYY используется как есть")
        void should_parse_full_format_as_is() {
            Optional<LocalDate> got = service.parseCareDate("22.05.2026", user);
            assertThat(got).contains(LocalDate.of(2026, 5, 22));
        }

        @Test
        @DisplayName("Невалидный формат → Optional.empty()")
        void should_return_empty_on_garbage() {
            assertThat(service.parseCareDate("abc", user)).isEmpty();
            assertThat(service.parseCareDate("2026-05-22", user)).isEmpty();
            assertThat(service.parseCareDate("", user)).isEmpty();
            assertThat(service.parseCareDate(null, user)).isEmpty();
        }

        @Test
        @DisplayName("Несуществующая дата (32.13) → Optional.empty()")
        void should_return_empty_on_impossible_date() {
            assertThat(service.parseCareDate("32.13", user)).isEmpty();
        }

        @Test
        @DisplayName("DD.MM «завтра» (today=24.05, ввод 25.05) → НЕ ролим в прошлый год — оставляем ближайшее будущее, applyResult вернёт IN_FUTURE")
        void should_keep_near_future_short_date_in_current_year() {
            // today=2026-05-24, ввод 25.05 — выглядит как «завтра», не как «декабрь прошлого года»
            Optional<LocalDate> got = service.parseCareDate("25.05", user);
            assertThat(got).contains(LocalDate.of(2026, 5, 25));
        }

        @Test
        @DisplayName("DD.MM ближнее будущее в пределах порога (через 2 недели) → НЕ ролим")
        void should_keep_short_date_within_near_future_threshold() {
            // today=2026-05-24, ввод 07.06 — ~14 дней вперёд, в пределах 30-дневного порога
            Optional<LocalDate> got = service.parseCareDate("07.06", user);
            assertThat(got).contains(LocalDate.of(2026, 6, 7));
        }
    }

    // ====================================================================
    // helpers
    // ====================================================================

    private CareSchedule scheduleFor(TaskType type) {
        return CareSchedule.builder()
                .plant(plant)
                .taskType(type)
                .intervalDays(7)
                .nextDueAt(java.time.LocalDateTime.now())
                .active(true)
                .build();
    }

    private List<String> callbacksOf(InlineKeyboardMarkup keyboard) {
        return keyboard.getKeyboard().stream()
                .flatMap((InlineKeyboardRow row) -> row.stream())
                .map(InlineKeyboardButton::getCallbackData)
                .toList();
    }

    private static void setId(Object target, Long id) {
        try {
            Class<?> clazz = target.getClass();
            Field field = null;
            while (clazz != null) {
                try {
                    field = clazz.getDeclaredField("id");
                    break;
                } catch (NoSuchFieldException e) {
                    clazz = clazz.getSuperclass();
                }
            }
            if (field == null) throw new RuntimeException("id field not found");
            field.setAccessible(true);
            field.set(target, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
