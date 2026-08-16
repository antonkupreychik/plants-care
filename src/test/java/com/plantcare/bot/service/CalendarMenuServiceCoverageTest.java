package com.plantcare.bot.service;

import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.service.CalendarService;
import com.plantcare.core.service.CalendarService.CareTask;
import com.plantcare.core.service.CalendarService.DayView;
import com.plantcare.core.service.CalendarService.WeekView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Дополнительное покрытие {@link CalendarMenuService}: полный путь sendCalendar
 * (send vs edit, пагинация чанков, обработка ошибок Telegram), навигационная
 * клавиатура и заголовки дней. Не дублирует {@link CalendarMenuServiceTest},
 * который тестирует только {@code renderChunks} напрямую.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CalendarMenuService — sendCalendar, клавиатура, обработка ошибок")
class CalendarMenuServiceCoverageTest {

    @Mock private CalendarService calendarService;
    @Mock private TelegramClient telegramClient;

    @InjectMocks
    private CalendarMenuService menuService;

    private User user;
    private Location living;

    @BeforeEach
    void setUp() {
        user = User.builder().telegramChatId(500L).timezone("UTC").build();
        living = Location.builder().name("Гостиная").emoji("🛋").build();

        lenient().when(calendarService.groupByLocation(anyList()))
                .thenAnswer(inv -> {
                    List<CareTask> tasks = inv.getArgument(0);
                    Map<Location, List<CareTask>> out = new LinkedHashMap<>();
                    for (CareTask t : tasks) {
                        out.computeIfAbsent(t.location(), k -> new ArrayList<>()).add(t);
                    }
                    return out;
                });
    }

    private WeekView emptyWeek(int offset) {
        LocalDate today = LocalDate.now();
        List<DayView> days = new ArrayList<>(7);
        for (int i = 0; i < 7; i++) {
            days.add(new DayView(today.plusDays(i), i, List.of()));
        }
        return new WeekView(today, offset, today, days);
    }

    private WeekView weekWithOneTask(Plant plant, Location location, int offset) {
        LocalDate today = LocalDate.now();
        List<DayView> days = new ArrayList<>(7);
        days.add(new DayView(today, 0, List.of(new CareTask(plant, TaskType.WATERING, location))));
        for (int i = 1; i < 7; i++) {
            days.add(new DayView(today.plusDays(i), i, List.of()));
        }
        return new WeekView(today, offset, today, days);
    }

    @Test
    @DisplayName("1-арг sendCalendar делегирует в полную версию с offset=0 и messageId=null → новое сообщение")
    void should_delegate_to_full_overload_with_zero_offset_and_send_new_message() throws TelegramApiException {
        when(calendarService.buildWeekView(user, 0)).thenReturn(emptyWeek(0));
        when(calendarService.distinctLocationsInWeek(any())).thenReturn(0);

        menuService.sendCalendar(user, telegramClient);

        verify(calendarService).buildWeekView(user, 0);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());

        SendMessage sent = captor.getValue();
        assertThat(sent.getChatId()).isEqualTo("500");
        assertThat(sent.getText()).contains("На неделе пусто");

        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) sent.getReplyMarkup();
        List<String> callbacks = keyboard.getKeyboard().stream()
                .flatMap(Collection::stream)
                .map(InlineKeyboardButton::getCallbackData)
                .toList();
        // weekOffset == 0 → кнопки «Сегодня» быть не должно.
        assertThat(callbacks).contains("cal:week:-1", "cal:week:1", "MENU:BACK");
        List<String> texts = keyboard.getKeyboard().stream()
                .flatMap(Collection::stream)
                .map(InlineKeyboardButton::getText)
                .toList();
        assertThat(texts).doesNotContain("Сегодня");
    }

    @Test
    @DisplayName("messageId задан и offset != 0 → редактирует сообщение, клавиатура содержит кнопку «Сегодня»")
    void should_edit_message_and_include_today_button_when_offset_nonzero() throws TelegramApiException {
        Plant plant = Plant.builder().name("Монстера").location(living).build();
        when(calendarService.buildWeekView(user, 2)).thenReturn(weekWithOneTask(plant, living, 2));
        when(calendarService.distinctLocationsInWeek(any())).thenReturn(1);

        menuService.sendCalendar(user, 2, 55, telegramClient);

        ArgumentCaptor<EditMessageText> captor = ArgumentCaptor.forClass(EditMessageText.class);
        verify(telegramClient).execute(captor.capture());

        EditMessageText edited = captor.getValue();
        assertThat(edited.getChatId()).isEqualTo("500");
        assertThat(edited.getMessageId()).isEqualTo(55);
        assertThat(edited.getText()).contains("Монстера").contains("полить");

        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) edited.getReplyMarkup();
        List<String> texts = keyboard.getKeyboard().stream()
                .flatMap(Collection::stream)
                .map(InlineKeyboardButton::getText)
                .toList();
        List<String> callbacks = keyboard.getKeyboard().stream()
                .flatMap(Collection::stream)
                .map(InlineKeyboardButton::getCallbackData)
                .toList();
        assertThat(texts).contains("Сегодня");
        assertThat(callbacks).contains("cal:week:0", "cal:week:1", "cal:week:3");
    }

    @Test
    @DisplayName("Несколько чанков с messageId — первый чанк редактируется без клавиатуры, последующие шлются новыми с клавиатурой на последнем")
    void should_edit_first_chunk_without_keyboard_and_send_rest_with_keyboard_on_last() throws TelegramApiException {
        LocalDate today = LocalDate.now();
        List<CareTask> manyTasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            Plant p = Plant.builder()
                    .name("Растение_с_длинным_именем_для_набора_длины_" + i)
                    .location(living)
                    .build();
            manyTasks.add(new CareTask(p, TaskType.WATERING, living));
        }
        List<DayView> days = new ArrayList<>();
        days.add(new DayView(today, 0, manyTasks));
        days.add(new DayView(today.plusDays(1), 1, manyTasks));
        for (int i = 2; i < 7; i++) {
            days.add(new DayView(today.plusDays(i), i, List.of()));
        }
        WeekView bigWeek = new WeekView(today, 0, today, days);

        when(calendarService.buildWeekView(user, 0)).thenReturn(bigWeek);
        when(calendarService.distinctLocationsInWeek(any())).thenReturn(1);

        menuService.sendCalendar(user, 0, 77, telegramClient);

        verify(telegramClient, times(1)).execute(any(EditMessageText.class));
        verify(telegramClient, org.mockito.Mockito.atLeastOnce()).execute(any(SendMessage.class));

        ArgumentCaptor<EditMessageText> editCaptor = ArgumentCaptor.forClass(EditMessageText.class);
        verify(telegramClient).execute(editCaptor.capture());
        assertThat(editCaptor.getValue().getReplyMarkup()).isNull();

        ArgumentCaptor<SendMessage> sendCaptor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient, org.mockito.Mockito.atLeastOnce()).execute(sendCaptor.capture());
        List<SendMessage> sentChunks = sendCaptor.getAllValues();
        // Клавиатура прикреплена только к последнему сообщению.
        for (int i = 0; i < sentChunks.size() - 1; i++) {
            assertThat(sentChunks.get(i).getReplyMarkup()).isNull();
        }
        assertThat(sentChunks.get(sentChunks.size() - 1).getReplyMarkup()).isNotNull();
        assertThat(sentChunks.get(0).getText()).startsWith("_(продолжение)_");
    }

    @Test
    @DisplayName("Ошибка «message is not modified» при редактировании — тихо гасится, fallback не вызывается")
    void should_silently_ignore_not_modified_edit_error() throws TelegramApiException {
        when(calendarService.buildWeekView(user, 0)).thenReturn(emptyWeek(0));
        when(calendarService.distinctLocationsInWeek(any())).thenReturn(0);
        when(telegramClient.execute(any(EditMessageText.class)))
                .thenThrow(new TelegramApiException("Bad Request: message is not modified"));

        menuService.sendCalendar(user, 0, 10, telegramClient);

        verify(telegramClient, times(1)).execute(any(EditMessageText.class));
        verify(telegramClient, never()).execute(any(SendMessage.class));
    }

    @Test
    @DisplayName("Прочая ошибка при редактировании — падает обратно на отправку нового сообщения")
    void should_fallback_to_send_message_on_other_edit_error() throws TelegramApiException {
        when(calendarService.buildWeekView(user, 0)).thenReturn(emptyWeek(0));
        when(calendarService.distinctLocationsInWeek(any())).thenReturn(0);
        when(telegramClient.execute(any(EditMessageText.class)))
                .thenThrow(new TelegramApiException("Bad Request: chat not found"));

        menuService.sendCalendar(user, 0, 10, telegramClient);

        verify(telegramClient, times(1)).execute(any(EditMessageText.class));

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient, times(1)).execute(captor.capture());
        assertThat(captor.getValue().getChatId()).isEqualTo("500");
        assertThat(captor.getValue().getText()).contains("На неделе пусто");
    }

    @Test
    @DisplayName("Ошибка при отправке нового сообщения (без messageId) — гасится, наружу не летит")
    void should_swallow_telegram_exception_on_plain_send() throws TelegramApiException {
        when(calendarService.buildWeekView(user, 0)).thenReturn(emptyWeek(0));
        when(calendarService.distinctLocationsInWeek(any())).thenReturn(0);
        when(telegramClient.execute(any(SendMessage.class)))
                .thenThrow(new TelegramApiException("network error"));

        menuService.sendCalendar(user, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getChatId()).isEqualTo("500");
    }

    @Test
    @DisplayName("renderChunks: заголовки завтра/вчера/обычного дня недели с заглавной буквы")
    void should_render_tomorrow_yesterday_and_regular_weekday_headers() {
        LocalDate today = LocalDate.now();
        DayView tomorrow = new DayView(today.plusDays(1), 1,
                List.of(new CareTask(Plant.builder().name("A").location(living).build(), TaskType.WATERING, living)));
        DayView yesterday = new DayView(today.minusDays(1), -1,
                List.of(new CareTask(Plant.builder().name("B").location(living).build(), TaskType.WATERING, living)));
        DayView farDay = new DayView(today.plusDays(4), 4,
                List.of(new CareTask(Plant.builder().name("C").location(living).build(), TaskType.WATERING, living)));

        List<DayView> days = new ArrayList<>();
        days.add(tomorrow);
        days.add(yesterday);
        days.add(farDay);
        for (int i = days.size(); i < 7; i++) {
            days.add(new DayView(today.plusDays(10 + i), 10 + i, List.of()));
        }
        WeekView view = new WeekView(today, 0, today, days);

        List<String> chunks = menuService.renderChunks(view, false);
        String text = String.join("\n", chunks);

        assertThat(text).contains("Завтра (");
        assertThat(text).contains("Вчера (");
        // Обычный день — день недели с заглавной буквы + дата, без "Сегодня/Завтра/Вчера".
        String weekdayCapitalized = Character.toUpperCase(
                farDay.date().getDayOfWeek()
                        .getDisplayName(java.time.format.TextStyle.SHORT, new java.util.Locale("ru"))
                        .charAt(0)
        ) + farDay.date().getDayOfWeek()
                .getDisplayName(java.time.format.TextStyle.SHORT, new java.util.Locale("ru"))
                .substring(1);
        assertThat(text).contains(weekdayCapitalized + ",");
    }

    @Test
    @DisplayName("Группировка: задача без комнаты попадает в подзаголовок «Без комнаты»")
    void should_group_task_without_location_under_no_room_header() {
        LocalDate today = LocalDate.now();
        Plant plantNoLoc = Plant.builder().name("Кактус").location(null).build();
        DayView day = new DayView(today, 0, List.of(new CareTask(plantNoLoc, TaskType.WATERING, null)));
        List<DayView> days = new ArrayList<>();
        days.add(day);
        for (int i = 1; i < 7; i++) {
            days.add(new DayView(today.plusDays(i), i, List.of()));
        }
        WeekView view = new WeekView(today, 0, today, days);

        List<String> chunks = menuService.renderChunks(view, true);
        String text = String.join("\n", chunks);

        assertThat(text).contains("Без комнаты").contains("Кактус");
    }

    @Test
    @DisplayName("escapeMd: спецсимволы Markdown в имени растения экранируются")
    void should_escape_markdown_special_characters_in_plant_name() {
        LocalDate today = LocalDate.now();
        Plant weird = Plant.builder().name("A_B*C[D]`E\\F").location(living).build();
        DayView day = new DayView(today, 0, List.of(new CareTask(weird, TaskType.WATERING, living)));
        List<DayView> days = new ArrayList<>();
        days.add(day);
        for (int i = 1; i < 7; i++) {
            days.add(new DayView(today.plusDays(i), i, List.of()));
        }
        WeekView view = new WeekView(today, 0, today, days);

        List<String> chunks = menuService.renderChunks(view, false);
        String text = String.join("\n", chunks);

        assertThat(text).contains("A\\_B\\*C\\[D\\]\\`E\\\\F");
    }
}
