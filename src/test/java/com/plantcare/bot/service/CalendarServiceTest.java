package com.plantcare.bot.service;

import com.plantcare.bot.domain.CareSchedule;
import com.plantcare.bot.domain.Location;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.TaskType;
import com.plantcare.bot.repository.CareScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-тесты для CalendarService")
class CalendarServiceTest {

    @Mock private CareScheduleRepository scheduleRepository;

    @InjectMocks
    private CalendarService service;

    private User user;
    private Plant plant;
    private Location location;
    private final ZoneId UTC = ZoneOffset.UTC;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .telegramChatId(100L)
                .timezone("UTC")  // тесты в UTC для предсказуемости
                .build();
        setId(user, 1L);

        location = Location.builder().name("Гостиная").emoji("🛋").build();
        setId(location, 10L);

        plant = Plant.builder().name("Монстера").location(location).build();
        setId(plant, 20L);
    }

    @Test
    @DisplayName("Пустой список расписаний → 7 пустых дней, всего 0 задач")
    void emptySchedules_returnSevenEmptyDays() {
        when(scheduleRepository.findActiveSchedulesByUserId(1L)).thenReturn(List.of());

        CalendarService.WeekView view = service.buildWeekView(user, 0);

        assertThat(view.days()).hasSize(7);
        assertThat(view.totalTasks()).isZero();
        assertThat(view.days()).allMatch(CalendarService.DayView::isEmpty);
        assertThat(view.days().get(0).isToday()).isTrue();
    }

    @Test
    @DisplayName("Расписание с интервалом 3 дня появляется в неделе 2-3 раза")
    void shortInterval_producesMultipleEventsInWeek() {
        LocalDate today = LocalDate.now(UTC);
        CareSchedule s = schedule(TaskType.MISTING, 3, today.atStartOfDay());
        when(scheduleRepository.findActiveSchedulesByUserId(1L)).thenReturn(List.of(s));

        CalendarService.WeekView view = service.buildWeekView(user, 0);

        // 7-дневное окно с шагом 3: события на 0, 3, 6 день — 3 события.
        assertThat(view.totalTasks()).isEqualTo(3);
        assertThat(view.days().get(0).tasks()).hasSize(1);
        assertThat(view.days().get(3).tasks()).hasSize(1);
        assertThat(view.days().get(6).tasks()).hasSize(1);
        assertThat(view.days().get(1).tasks()).isEmpty();
    }

    @Test
    @DisplayName("Длинный интервал (>7 дней) — может не появиться в окне вообще")
    void longInterval_mayNotAppearInWeek() {
        LocalDate today = LocalDate.now(UTC);
        // Событие через 10 дней — выходит за пределы текущей недели.
        CareSchedule s = schedule(TaskType.FERTILIZING, 30, today.plusDays(10).atStartOfDay());
        when(scheduleRepository.findActiveSchedulesByUserId(1L)).thenReturn(List.of(s));

        CalendarService.WeekView view = service.buildWeekView(user, 0);

        assertThat(view.totalTasks()).isZero();
    }

    @Test
    @DisplayName("offset=1 (следующая неделя) показывает события через 7 дней")
    void nextWeekOffset_showsFutureEvents() {
        LocalDate today = LocalDate.now(UTC);
        CareSchedule s = schedule(TaskType.WATERING, 7, today.plusDays(8).atStartOfDay());
        when(scheduleRepository.findActiveSchedulesByUserId(1L)).thenReturn(List.of(s));

        CalendarService.WeekView thisWeek = service.buildWeekView(user, 0);
        CalendarService.WeekView nextWeek = service.buildWeekView(user, 1);

        assertThat(thisWeek.totalTasks()).isZero();
        assertThat(nextWeek.totalTasks()).isEqualTo(1);
        assertThat(nextWeek.weekStart()).isEqualTo(today.plusDays(7));
    }

    @Test
    @DisplayName("Просроченное расписание (nextDueAt в прошлом, weekly) — событие проецируется на следующее срабатывание")
    void overdueSchedule_projectsForward() {
        LocalDate today = LocalDate.now(UTC);
        // nextDueAt = 3 дня назад, интервал 7. Следующее событие — через 4 дня.
        CareSchedule s = schedule(TaskType.WATERING, 7, today.minusDays(3).atStartOfDay());
        when(scheduleRepository.findActiveSchedulesByUserId(1L)).thenReturn(List.of(s));

        CalendarService.WeekView view = service.buildWeekView(user, 0);

        // Событие должно быть на 4-м дне (today + 4).
        assertThat(view.totalTasks()).isEqualTo(1);
        assertThat(view.days().get(4).tasks()).hasSize(1);
    }

    @Test
    @DisplayName("Очень старое просроченное расписание — fast-forward, не зависает в цикле")
    void veryOldOverdue_fastForwards() {
        LocalDate today = LocalDate.now(UTC);
        // 365 дней назад, daily — наивный цикл проитерируется 365 раз, fast-forward — за один шаг.
        CareSchedule s = schedule(TaskType.WATERING, 1, today.minusDays(365).atStartOfDay());
        when(scheduleRepository.findActiveSchedulesByUserId(1L)).thenReturn(List.of(s));

        long start = System.nanoTime();
        CalendarService.WeekView view = service.buildWeekView(user, 0);
        long durationMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(view.totalTasks()).isEqualTo(7);  // daily, 7 дней = 7 событий
        // Sanity: построение недели не должно быть медленным даже на годовом отставании.
        assertThat(durationMs).isLessThan(100);
    }

    @Test
    @DisplayName("Несколько расписаний одного растения → задачи на одном дне")
    void multipleSchedulesPerPlant_appearTogether() {
        LocalDate today = LocalDate.now(UTC);
        CareSchedule watering = schedule(TaskType.WATERING, 7, today.atStartOfDay());
        CareSchedule misting = schedule(TaskType.MISTING, 3, today.atStartOfDay());
        when(scheduleRepository.findActiveSchedulesByUserId(1L))
                .thenReturn(List.of(watering, misting));

        CalendarService.WeekView view = service.buildWeekView(user, 0);

        // Сегодня: оба события на одной дате.
        assertThat(view.days().get(0).tasks()).hasSize(2);
        // Внутри дня: один полив + одно опрыскивание.
        assertThat(view.days().get(0).tasks())
                .extracting(CalendarService.CareTask::taskType)
                .containsExactlyInAnyOrder(TaskType.WATERING, TaskType.MISTING);
    }

    @Test
    @DisplayName("Неактивное расписание не попадает в выборку (фильтр в репо)")
    void inactiveSchedulesExcludedByRepo() {
        // Контракт: репо уже фильтрует active=true.
        when(scheduleRepository.findActiveSchedulesByUserId(1L)).thenReturn(List.of());

        CalendarService.WeekView view = service.buildWeekView(user, 0);

        assertThat(view.totalTasks()).isZero();
    }

    @Test
    @DisplayName("Кэш: повторный вызов с тем же offset не дёргает репо")
    void cache_hitsOnSecondCall() {
        when(scheduleRepository.findActiveSchedulesByUserId(1L)).thenReturn(List.of());

        service.buildWeekView(user, 0);
        service.buildWeekView(user, 0);
        service.buildWeekView(user, 0);

        verify(scheduleRepository, times(1)).findActiveSchedulesByUserId(anyLong());
    }

    @Test
    @DisplayName("Кэш: разные offset кэшируются отдельно")
    void cache_separateByOffset() {
        when(scheduleRepository.findActiveSchedulesByUserId(1L)).thenReturn(List.of());

        service.buildWeekView(user, 0);
        service.buildWeekView(user, 1);
        service.buildWeekView(user, -1);

        verify(scheduleRepository, times(3)).findActiveSchedulesByUserId(anyLong());
    }

    @Test
    @DisplayName("Группировка по комнатам считается корректно")
    void distinctLocationsInWeek_counts() {
        Location kitchen = Location.builder().name("Кухня").build();
        setId(kitchen, 11L);

        Plant plantA = Plant.builder().name("A").location(location).build();
        setId(plantA, 21L);
        Plant plantB = Plant.builder().name("B").location(kitchen).build();
        setId(plantB, 22L);

        LocalDate today = LocalDate.now(UTC);
        CareSchedule sA = schedule(plantA, TaskType.WATERING, 1, today.atStartOfDay());
        CareSchedule sB = schedule(plantB, TaskType.WATERING, 1, today.atStartOfDay());
        when(scheduleRepository.findActiveSchedulesByUserId(1L)).thenReturn(List.of(sA, sB));

        CalendarService.WeekView view = service.buildWeekView(user, 0);

        assertThat(service.distinctLocationsInWeek(view)).isEqualTo(2);
    }

    @Test
    @DisplayName("nextDueAt=null или interval<=0 → расписание скипается без ошибок")
    void degenerateSchedule_isSafelyIgnored() {
        CareSchedule nullDue = CareSchedule.builder()
                .plant(plant)
                .taskType(TaskType.WATERING)
                .intervalDays(7)
                .active(true)
                .nextDueAt(null)
                .build();
        CareSchedule zeroInterval = CareSchedule.builder()
                .plant(plant)
                .taskType(TaskType.WATERING)
                .intervalDays(0)
                .active(true)
                .nextDueAt(LocalDateTime.now())
                .build();
        when(scheduleRepository.findActiveSchedulesByUserId(1L))
                .thenReturn(List.of(nullDue, zeroInterval));

        CalendarService.WeekView view = service.buildWeekView(user, 0);

        assertThat(view.totalTasks()).isZero();
    }

    // ===== helpers =====

    private CareSchedule schedule(TaskType type, int intervalDays, LocalDateTime nextDueAt) {
        return schedule(plant, type, intervalDays, nextDueAt);
    }

    private CareSchedule schedule(Plant p, TaskType type, int intervalDays, LocalDateTime nextDueAt) {
        return CareSchedule.builder()
                .plant(p)
                .taskType(type)
                .intervalDays(intervalDays)
                .nextDueAt(nextDueAt)
                .active(true)
                .build();
    }

    private void setId(Object target, Long id) {
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
            if (field == null) throw new RuntimeException("no id field");
            field.setAccessible(true);
            field.set(target, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
