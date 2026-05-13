package com.plantcare.bot.service;

import com.plantcare.bot.domain.Location;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.enums.TaskType;
import com.plantcare.bot.service.CalendarService.CareTask;
import com.plantcare.bot.service.CalendarService.DayView;
import com.plantcare.bot.service.CalendarService.WeekView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-тесты для CalendarMenuService")
class CalendarMenuServiceTest {

    @Mock private CalendarService calendarService;

    @InjectMocks
    private CalendarMenuService menuService;

    private Plant plant1;
    private Plant plant2;
    private Location living;
    private Location kitchen;

    @BeforeEach
    void setUp() {
        living = Location.builder().name("Гостиная").emoji("🛋").build();
        kitchen = Location.builder().name("Кухня").emoji("🍳").build();
        plant1 = Plant.builder().name("Монстера").location(living).build();
        plant2 = Plant.builder().name("Фикус").location(kitchen).build();

        // По умолчанию делегируем реальный группёр-метод обратно в мок
        lenient().when(calendarService.groupByLocation(org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(inv -> {
                    List<CareTask> tasks = inv.getArgument(0);
                    Map<Location, List<CareTask>> out = new LinkedHashMap<>();
                    for (CareTask t : tasks) {
                        out.computeIfAbsent(t.location(), k -> new ArrayList<>()).add(t);
                    }
                    return out;
                });
    }

    @Test
    @DisplayName("Пустая неделя → 1 чанк с сообщением «пусто»")
    void emptyWeek_singleEmptyChunk() {
        WeekView view = weekView(0, emptyDays(7));

        List<String> chunks = menuService.renderChunks(view, false);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0))
                .contains("Календарь ухода")
                .contains("На неделе пусто");
    }

    @Test
    @DisplayName("День с задачами — без группировки выводит плоский список")
    void singleLocation_flatList() {
        LocalDate today = LocalDate.now();
        DayView day0 = new DayView(today, 0, List.of(
                new CareTask(plant1, TaskType.WATERING, living),
                new CareTask(plant1, TaskType.MISTING, living)
        ));
        WeekView view = weekView(0, withDay(day0, 6));

        List<String> chunks = menuService.renderChunks(view, false);

        String text = String.join("\n", chunks);
        assertThat(text)
                .contains("Сегодня")
                .contains("💧 Монстера — полить")
                .contains("💨 Монстера — опрыскать");
        // Без группировки — заголовка комнаты быть не должно
        assertThat(text).doesNotContain("Гостиная_:");
    }

    @Test
    @DisplayName("Несколько комнат → группировка с подзаголовком")
    void multipleLocations_groupedSubheaders() {
        LocalDate today = LocalDate.now();
        DayView day0 = new DayView(today, 0, List.of(
                new CareTask(plant1, TaskType.WATERING, living),
                new CareTask(plant2, TaskType.WATERING, kitchen)
        ));
        WeekView view = weekView(0, withDay(day0, 6));

        List<String> chunks = menuService.renderChunks(view, true);

        String text = String.join("\n", chunks);
        assertThat(text)
                .contains("Гостиная")
                .contains("Кухня")
                .contains("Монстера")
                .contains("Фикус");
    }

    @Test
    @DisplayName("Пустой день — короткая строка «пусто», не разворачиваем подзаголовки")
    void emptyDay_oneLinerOnly() {
        LocalDate today = LocalDate.now();
        DayView emptyDay = new DayView(today.plusDays(2), 2, List.of());
        DayView withTask = new DayView(today, 0, List.of(
                new CareTask(plant1, TaskType.WATERING, living)
        ));
        WeekView view = weekView(0, prepend(withTask, withDay(emptyDay, 6)).subList(0, 7));

        List<String> chunks = menuService.renderChunks(view, true);
        String text = String.join("\n", chunks);

        // Пустой день: «пусто 🌱» — без вложенных подзаголовков
        assertThat(text).contains("пусто 🌱");
    }

    @Test
    @DisplayName("Очень длинная неделя — режется на 2 чанка с пометкой 'продолжение'")
    void veryLongWeek_splitsAcrossChunks() {
        // Соберём день с большим количеством задач, чтобы переполнить SOFT_LIMIT
        LocalDate today = LocalDate.now();
        List<CareTask> manyTasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            Plant p = Plant.builder()
                    .name("Растение_с_длинным_именем_для_набора_длины_" + i)
                    .location(living)
                    .build();
            manyTasks.add(new CareTask(p, TaskType.WATERING, living));
        }
        DayView fatDay1 = new DayView(today, 0, manyTasks);
        DayView fatDay2 = new DayView(today.plusDays(1), 1, manyTasks);
        DayView fatDay3 = new DayView(today.plusDays(2), 2, manyTasks);

        List<DayView> days = new ArrayList<>(List.of(fatDay1, fatDay2, fatDay3));
        for (int i = 3; i < 7; i++) {
            days.add(new DayView(today.plusDays(i), i, List.of()));
        }
        WeekView view = weekView(0, days);

        List<String> chunks = menuService.renderChunks(view, false);

        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks.get(1)).startsWith("_(продолжение)_");
    }

    // ===== helpers =====

    private WeekView weekView(int offset, List<DayView> days) {
        LocalDate today = LocalDate.now();
        return new WeekView(today, offset, today, days);
    }

    private List<DayView> emptyDays(int n) {
        LocalDate today = LocalDate.now();
        List<DayView> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new DayView(today.plusDays(i), i, List.of()));
        }
        return out;
    }

    private List<DayView> withDay(DayView first, int remainingEmpty) {
        LocalDate base = first.date();
        List<DayView> out = new ArrayList<>(remainingEmpty + 1);
        out.add(first);
        for (int i = 1; i <= remainingEmpty; i++) {
            out.add(new DayView(base.plusDays(i), first.dayOffsetFromToday() + i, List.of()));
        }
        return out;
    }

    private List<DayView> prepend(DayView day, List<DayView> tail) {
        List<DayView> out = new ArrayList<>(tail.size() + 1);
        out.add(day);
        out.addAll(tail);
        return out;
    }
}
