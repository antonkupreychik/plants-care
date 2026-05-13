package com.plantcare.bot.service;

import com.plantcare.bot.domain.CareHistory;
import com.plantcare.bot.domain.CareSchedule;
import com.plantcare.bot.domain.enums.TaskType;
import com.plantcare.bot.repository.CareHistoryRepository;
import com.plantcare.bot.repository.CareScheduleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-тесты для CareHistoryService")
class CareHistoryServiceTest {

    @Mock private CareHistoryRepository historyRepository;
    @Mock private CareScheduleRepository scheduleRepository;

    @InjectMocks
    private CareHistoryService service;

    @Nested
    @DisplayName("computePlantStreak")
    class PlantStreakTests {

        @Test
        @DisplayName("Пустая история → 0")
        void emptyHistory_returnsZero() {
            when(scheduleRepository.findAllByPlantId(1L)).thenReturn(List.of());
            when(historyRepository.findAllByPlantIdOrderByDoneAtDesc(eq(1L), any(Limit.class)))
                    .thenReturn(List.of());

            assertThat(service.computePlantStreak(1L)).isZero();
        }

        @Test
        @DisplayName("Одно on-time действие → 1")
        void singleOnTimeAction_returnsOne() {
            when(scheduleRepository.findAllByPlantId(1L)).thenReturn(List.of());
            when(historyRepository.findAllByPlantIdOrderByDoneAtDesc(eq(1L), any(Limit.class)))
                    .thenReturn(List.of(historyEntry(true, false)));

            assertThat(service.computePlantStreak(1L)).isEqualTo(1);
        }

        @Test
        @DisplayName("Идеальная серия из 5 on-time → 5")
        void perfectStreakOfFive() {
            when(scheduleRepository.findAllByPlantId(1L)).thenReturn(List.of());
            when(historyRepository.findAllByPlantIdOrderByDoneAtDesc(eq(1L), any(Limit.class)))
                    .thenReturn(List.of(
                            historyEntry(true, false),
                            historyEntry(true, false),
                            historyEntry(true, false),
                            historyEntry(true, false),
                            historyEntry(true, false)
                    ));

            assertThat(service.computePlantStreak(1L)).isEqualTo(5);
        }

        @Test
        @DisplayName("Серия 3 on-time + потом late → 3 (стрик ломается на late)")
        void streakBreaksAtFirstLateEntry() {
            when(scheduleRepository.findAllByPlantId(1L)).thenReturn(List.of());
            when(historyRepository.findAllByPlantIdOrderByDoneAtDesc(eq(1L), any(Limit.class)))
                    .thenReturn(List.of(
                            historyEntry(true, false),
                            historyEntry(true, false),
                            historyEntry(true, false),
                            historyEntry(false, false), // late — стоп
                            historyEntry(true, false)   // дальше неважно
                    ));

            assertThat(service.computePlantStreak(1L)).isEqualTo(3);
        }

        @Test
        @DisplayName("Последнее действие late → 0")
        void lastActionLate_returnsZero() {
            when(scheduleRepository.findAllByPlantId(1L)).thenReturn(List.of());
            when(historyRepository.findAllByPlantIdOrderByDoneAtDesc(eq(1L), any(Limit.class)))
                    .thenReturn(List.of(historyEntry(false, false)));

            assertThat(service.computePlantStreak(1L)).isZero();
        }

        @Test
        @DisplayName("Просроченное расписание (>1 дня) обнуляет стрик")
        void overdueScheduleResetsStreak() {
            CareSchedule overdue = CareSchedule.builder()
                    .taskType(TaskType.WATERING)
                    .active(true)
                    .nextDueAt(LocalDateTime.now().minusDays(3))  // просрочено >1 дня
                    .build();
            when(scheduleRepository.findAllByPlantId(1L)).thenReturn(List.of(overdue));

            // История полна on-time, но это не помогает
            lenient().when(historyRepository.findAllByPlantIdOrderByDoneAtDesc(eq(1L), any(Limit.class)))
                    .thenReturn(List.of(
                            historyEntry(true, false),
                            historyEntry(true, false)
                    ));

            assertThat(service.computePlantStreak(1L)).isZero();
        }

        @Test
        @DisplayName("Расписание просрочено на полдня (<1 day) — стрик не ломается")
        void slightlyOverdueDoesNotBreak() {
            CareSchedule slightlyLate = CareSchedule.builder()
                    .taskType(TaskType.WATERING)
                    .active(true)
                    .nextDueAt(LocalDateTime.now().minusHours(12))
                    .build();
            when(scheduleRepository.findAllByPlantId(1L)).thenReturn(List.of(slightlyLate));
            when(historyRepository.findAllByPlantIdOrderByDoneAtDesc(eq(1L), any(Limit.class)))
                    .thenReturn(List.of(historyEntry(true, false)));

            assertThat(service.computePlantStreak(1L)).isEqualTo(1);
        }

        @Test
        @DisplayName("Неактивное расписание (active=false) не влияет, даже если просрочено")
        void inactiveScheduleIgnored() {
            CareSchedule offSchedule = CareSchedule.builder()
                    .taskType(TaskType.FERTILIZING)
                    .active(false)
                    .nextDueAt(LocalDateTime.now().minusDays(30))
                    .build();
            when(scheduleRepository.findAllByPlantId(1L)).thenReturn(List.of(offSchedule));
            when(historyRepository.findAllByPlantIdOrderByDoneAtDesc(eq(1L), any(Limit.class)))
                    .thenReturn(List.of(
                            historyEntry(true, false),
                            historyEntry(true, false)
                    ));

            assertThat(service.computePlantStreak(1L)).isEqualTo(2);
        }

        @Test
        @DisplayName("Compensating (cancelled) записи пропускаются при подсчёте")
        void cancelledEntriesAreSkipped() {
            when(scheduleRepository.findAllByPlantId(1L)).thenReturn(List.of());
            // Две on-time, между ними одна cancelled — должно дать 2 (пропустили cancelled).
            when(historyRepository.findAllByPlantIdOrderByDoneAtDesc(eq(1L), any(Limit.class)))
                    .thenReturn(List.of(
                            historyEntry(true, false),
                            historyEntry(false, true),  // cancelled — пропускаем, не ломает стрик
                            historyEntry(true, false)
                    ));

            assertThat(service.computePlantStreak(1L)).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("computeUserStreak")
    class UserStreakTests {

        private final ZoneId UTC = ZoneOffset.UTC;

        @Test
        @DisplayName("Пустая история → 0")
        void emptyHistory_returnsZero() {
            when(historyRepository.findUserDoneAtsDesc(anyLong(), any(Limit.class)))
                    .thenReturn(List.of());

            assertThat(service.computeUserStreak(1L, "UTC")).isZero();
        }

        @Test
        @DisplayName("Активность только сегодня → 1")
        void oneActionToday_returnsOne() {
            LocalDate today = LocalDate.now(UTC);
            when(historyRepository.findUserDoneAtsDesc(anyLong(), any(Limit.class)))
                    .thenReturn(List.of(atUtc(today, 12)));

            assertThat(service.computeUserStreak(1L, "UTC")).isEqualTo(1);
        }

        @Test
        @DisplayName("Активность сегодня + вчера + позавчера → 3")
        void threeConsecutiveDays_returnsThree() {
            LocalDate today = LocalDate.now(UTC);
            when(historyRepository.findUserDoneAtsDesc(anyLong(), any(Limit.class)))
                    .thenReturn(List.of(
                            atUtc(today, 10),
                            atUtc(today.minusDays(1), 18),
                            atUtc(today.minusDays(2), 9)
                    ));

            assertThat(service.computeUserStreak(1L, "UTC")).isEqualTo(3);
        }

        @Test
        @DisplayName("Несколько действий в один день считаются как один день")
        void multipleSameDayCountAsOne() {
            LocalDate today = LocalDate.now(UTC);
            when(historyRepository.findUserDoneAtsDesc(anyLong(), any(Limit.class)))
                    .thenReturn(List.of(
                            atUtc(today, 10),
                            atUtc(today, 11),  // тот же день
                            atUtc(today.minusDays(1), 9)
                    ));

            assertThat(service.computeUserStreak(1L, "UTC")).isEqualTo(2);
        }

        @Test
        @DisplayName("Дыра в один день (без активности позавчера) ломает стрик")
        void gapBreaksStreak() {
            LocalDate today = LocalDate.now(UTC);
            when(historyRepository.findUserDoneAtsDesc(anyLong(), any(Limit.class)))
                    .thenReturn(List.of(
                            atUtc(today, 10),
                            atUtc(today.minusDays(1), 10),
                            // дыра: minusDays(2) отсутствует
                            atUtc(today.minusDays(3), 10)
                    ));

            assertThat(service.computeUserStreak(1L, "UTC")).isEqualTo(2);
        }

        @Test
        @DisplayName("Сегодня нет активности, но вчера была → стрик отсчитывается от вчера")
        void noActivityTodayButYesterday_streakStartsFromYesterday() {
            LocalDate today = LocalDate.now(UTC);
            when(historyRepository.findUserDoneAtsDesc(anyLong(), any(Limit.class)))
                    .thenReturn(List.of(
                            atUtc(today.minusDays(1), 10),
                            atUtc(today.minusDays(2), 10)
                    ));

            assertThat(service.computeUserStreak(1L, "UTC")).isEqualTo(2);
        }

        @Test
        @DisplayName("Последняя активность позавчера или раньше → стрик 0 (просрочен)")
        void lastActivityDayBeforeYesterday_returnsZero() {
            LocalDate today = LocalDate.now(UTC);
            when(historyRepository.findUserDoneAtsDesc(anyLong(), any(Limit.class)))
                    .thenReturn(List.of(
                            atUtc(today.minusDays(2), 10),
                            atUtc(today.minusDays(3), 10)
                    ));

            assertThat(service.computeUserStreak(1L, "UTC")).isZero();
        }

        @Test
        @DisplayName("Невалидная таймзона → fallback на UTC, без NPE")
        void invalidTimezone_fallsBackToUtc() {
            when(historyRepository.findUserDoneAtsDesc(anyLong(), any(Limit.class)))
                    .thenReturn(List.of(atUtc(LocalDate.now(UTC), 12)));

            assertThat(service.computeUserStreak(1L, "Not/A/Zone")).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("getPlantStats")
    class PlantStatsTests {

        @Test
        @DisplayName("Нет истории → total=0, hasEnoughData=false")
        void noHistory() {
            when(historyRepository.countActiveByPlantId(1L)).thenReturn(0L);

            CareHistoryService.PlantStats stats = service.getPlantStats(1L);

            assertThat(stats.total()).isZero();
            assertThat(stats.streak()).isZero();
            assertThat(stats.onTimePct()).isZero();
            assertThat(stats.hasEnoughData()).isFalse();
        }

        @Test
        @DisplayName("4 записи за 30 дней, 3 on-time → on-time 75%")
        void onTimePercentageCalculation() {
            when(historyRepository.countActiveByPlantId(1L)).thenReturn(4L);
            when(historyRepository.countActiveByPlantIdSince(eq(1L), any())).thenReturn(4L);
            when(historyRepository.countActiveOnTimeByPlantIdSince(eq(1L), any())).thenReturn(3L);
            when(scheduleRepository.findAllByPlantId(1L)).thenReturn(List.of());
            when(historyRepository.findAllByPlantIdOrderByDoneAtDesc(eq(1L), any(Limit.class)))
                    .thenReturn(List.of(historyEntry(true, false)));

            CareHistoryService.PlantStats stats = service.getPlantStats(1L);

            assertThat(stats.total()).isEqualTo(4L);
            assertThat(stats.onTimePct()).isEqualTo(75);
            assertThat(stats.streak()).isEqualTo(1);
            assertThat(stats.hasEnoughData()).isTrue();
        }

        @Test
        @DisplayName("0 действий в окне 30 дней → on-time 0%, без деления на ноль")
        void noActionsInWindow_returnsZeroPercent() {
            when(historyRepository.countActiveByPlantId(1L)).thenReturn(5L);
            when(historyRepository.countActiveByPlantIdSince(eq(1L), any())).thenReturn(0L);
            when(historyRepository.countActiveOnTimeByPlantIdSince(eq(1L), any())).thenReturn(0L);
            when(scheduleRepository.findAllByPlantId(1L)).thenReturn(List.of());
            when(historyRepository.findAllByPlantIdOrderByDoneAtDesc(eq(1L), any(Limit.class)))
                    .thenReturn(List.of());

            CareHistoryService.PlantStats stats = service.getPlantStats(1L);

            assertThat(stats.onTimePct()).isZero();
            assertThat(stats.total()).isEqualTo(5L);
            assertThat(stats.hasEnoughData()).isTrue();
        }

        @Test
        @DisplayName("Всего 2 записи → hasEnoughData=false")
        void belowThreshold_hasEnoughDataFalse() {
            when(historyRepository.countActiveByPlantId(1L)).thenReturn(2L);
            when(historyRepository.countActiveByPlantIdSince(eq(1L), any())).thenReturn(2L);
            when(historyRepository.countActiveOnTimeByPlantIdSince(eq(1L), any())).thenReturn(2L);
            when(scheduleRepository.findAllByPlantId(1L)).thenReturn(List.of());
            when(historyRepository.findAllByPlantIdOrderByDoneAtDesc(eq(1L), any(Limit.class)))
                    .thenReturn(List.of(historyEntry(true, false), historyEntry(true, false)));

            CareHistoryService.PlantStats stats = service.getPlantStats(1L);
            assertThat(stats.hasEnoughData()).isFalse();
        }
    }

    // ===== helpers =====

    /** Создаёт мок-CareHistory с указанными флагами on_time / cancelled. */
    private CareHistory historyEntry(boolean onTime, boolean cancelled) {
        CareHistory h = new CareHistory();
        h.setTaskType(TaskType.WATERING);
        h.setDoneAt(LocalDateTime.now());
        h.setOnTime(onTime);
        if (cancelled) {
            // Лёгкий хак: для проверки isCancelled() достаточно non-null cancelledBy.
            h.setCancelledBy(h);
        }
        return h;
    }

    /** doneAt в полночь UTC указанного дня + час. */
    private LocalDateTime atUtc(LocalDate date, int hour) {
        return date.atStartOfDay().plusHours(hour);
    }

    @SuppressWarnings("unused")
    private List<CareHistory> seq(int n, boolean onTime) {
        List<CareHistory> list = new ArrayList<>();
        for (int i = 0; i < n; i++) list.add(historyEntry(onTime, false));
        return list;
    }
}
