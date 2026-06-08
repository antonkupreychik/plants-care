package com.plantcare.core.service;

import com.plantcare.core.domain.CareHistory;
import com.plantcare.core.domain.CareSchedule;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.repository.CareHistoryRepository;
import com.plantcare.core.repository.CareScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;

import java.time.Clock;
import java.time.Instant;
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
    @Mock private Clock clock;

    @InjectMocks
    private CareHistoryService service;

    @BeforeEach
    void stubClock() {
        Instant now = Instant.now();
        lenient().when(clock.instant()).thenReturn(now);
        lenient().when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        lenient().when(clock.withZone(any(ZoneId.class))).thenAnswer(inv -> {
            ZoneId zone = inv.getArgument(0);
            return Clock.fixed(now, zone);
        });
    }

    @Nested
    @DisplayName("computePlantStreak")
    class PlantStreakTests {

        private final ZoneId UTC = ZoneOffset.UTC;

        @Test
        @DisplayName("Пустая история → 0")
        void emptyHistory_returnsZero() {
            when(scheduleRepository.findAllByPlantId(1L)).thenReturn(List.of());
            when(historyRepository.findAllByPlantIdOrderByDoneAtDesc(eq(1L), any(Limit.class)))
                    .thenReturn(List.of());

            assertThat(service.computePlantStreak(1L, "UTC")).isZero();
        }

        @Test
        @DisplayName("Одно on-time действие → 1")
        void singleOnTimeAction_returnsOne() {
            when(scheduleRepository.findAllByPlantId(1L)).thenReturn(List.of());
            when(historyRepository.findAllByPlantIdOrderByDoneAtDesc(eq(1L), any(Limit.class)))
                    .thenReturn(List.of(historyEntry(true, false)));

            assertThat(service.computePlantStreak(1L, "UTC")).isEqualTo(1);
        }

        @Test
        @DisplayName("Серия из 5 on-time в РАЗНЫЕ дни → 5")
        void perfectStreakOfFive() {
            LocalDate today = LocalDate.now(UTC);
            when(scheduleRepository.findAllByPlantId(1L)).thenReturn(List.of());
            when(historyRepository.findAllByPlantIdOrderByDoneAtDesc(eq(1L), any(Limit.class)))
                    .thenReturn(List.of(
                            historyEntry(true, false, atUtc(today, 12)),
                            historyEntry(true, false, atUtc(today.minusDays(1), 12)),
                            historyEntry(true, false, atUtc(today.minusDays(2), 12)),
                            historyEntry(true, false, atUtc(today.minusDays(3), 12)),
                            historyEntry(true, false, atUtc(today.minusDays(4), 12))
                    ));

            assertThat(service.computePlantStreak(1L, "UTC")).isEqualTo(5);
        }

        @Test
        @DisplayName("Серия 3 on-time-дня + потом late → 3 (стрик ломается на late)")
        void streakBreaksAtFirstLateEntry() {
            LocalDate today = LocalDate.now(UTC);
            when(scheduleRepository.findAllByPlantId(1L)).thenReturn(List.of());
            when(historyRepository.findAllByPlantIdOrderByDoneAtDesc(eq(1L), any(Limit.class)))
                    .thenReturn(List.of(
                            historyEntry(true, false, atUtc(today, 12)),
                            historyEntry(true, false, atUtc(today.minusDays(1), 12)),
                            historyEntry(true, false, atUtc(today.minusDays(2), 12)),
                            historyEntry(false, false, atUtc(today.minusDays(3), 12)), // late — стоп
                            historyEntry(true, false, atUtc(today.minusDays(4), 12))   // дальше неважно
                    ));

            assertThat(service.computePlantStreak(1L, "UTC")).isEqualTo(3);
        }

        @Test
        @DisplayName("Последнее действие late → 0")
        void lastActionLate_returnsZero() {
            when(scheduleRepository.findAllByPlantId(1L)).thenReturn(List.of());
            when(historyRepository.findAllByPlantIdOrderByDoneAtDesc(eq(1L), any(Limit.class)))
                    .thenReturn(List.of(historyEntry(false, false)));

            assertThat(service.computePlantStreak(1L, "UTC")).isZero();
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

            assertThat(service.computePlantStreak(1L, "UTC")).isZero();
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

            assertThat(service.computePlantStreak(1L, "UTC")).isEqualTo(1);
        }

        @Test
        @DisplayName("Неактивное расписание (active=false) не влияет, даже если просрочено")
        void inactiveScheduleIgnored() {
            LocalDate today = LocalDate.now(UTC);
            CareSchedule offSchedule = CareSchedule.builder()
                    .taskType(TaskType.FERTILIZING)
                    .active(false)
                    .nextDueAt(LocalDateTime.now().minusDays(30))
                    .build();
            when(scheduleRepository.findAllByPlantId(1L)).thenReturn(List.of(offSchedule));
            when(historyRepository.findAllByPlantIdOrderByDoneAtDesc(eq(1L), any(Limit.class)))
                    .thenReturn(List.of(
                            historyEntry(true, false, atUtc(today, 12)),
                            historyEntry(true, false, atUtc(today.minusDays(1), 12))
                    ));

            assertThat(service.computePlantStreak(1L, "UTC")).isEqualTo(2);
        }

        @Test
        @DisplayName("Compensating (cancelled) записи пропускаются при подсчёте")
        void cancelledEntriesAreSkipped() {
            LocalDate today = LocalDate.now(UTC);
            when(scheduleRepository.findAllByPlantId(1L)).thenReturn(List.of());
            // Две on-time в разные дни, между ними одна cancelled — должно дать 2.
            when(historyRepository.findAllByPlantIdOrderByDoneAtDesc(eq(1L), any(Limit.class)))
                    .thenReturn(List.of(
                            historyEntry(true, false, atUtc(today, 12)),
                            historyEntry(false, true, atUtc(today, 13)),  // cancelled — пропускаем
                            historyEntry(true, false, atUtc(today.minusDays(1), 12))
                    ));

            assertThat(service.computePlantStreak(1L, "UTC")).isEqualTo(2);
        }

        @Test
        @DisplayName("should_count_same_day_repeats_as_one_when_multiple_on_time_cares")
        void should_count_same_day_repeats_as_one_when_multiple_on_time_cares() {
            LocalDate today = LocalDate.now(UTC);
            when(scheduleRepository.findAllByPlantId(1L)).thenReturn(List.of());
            // 5 on-time уходов в ОДИН день (в TZ юзера) → стрик 1, а не 5.
            when(historyRepository.findAllByPlantIdOrderByDoneAtDesc(eq(1L), any(Limit.class)))
                    .thenReturn(List.of(
                            historyEntry(true, false, atUtc(today, 8)),
                            historyEntry(true, false, atUtc(today, 10)),
                            historyEntry(true, false, atUtc(today, 12)),
                            historyEntry(true, false, atUtc(today, 14)),
                            historyEntry(true, false, atUtc(today, 16))
                    ));

            assertThat(service.computePlantStreak(1L, "UTC")).isEqualTo(1);
        }

        @Test
        @DisplayName("should_count_distinct_days_when_on_time_cares_on_separate_days")
        void should_count_distinct_days_when_on_time_cares_on_separate_days() {
            LocalDate today = LocalDate.now(UTC);
            when(scheduleRepository.findAllByPlantId(1L)).thenReturn(List.of());
            // On-time уходы в 3 разных дня подряд по расписанию → стрик 3.
            when(historyRepository.findAllByPlantIdOrderByDoneAtDesc(eq(1L), any(Limit.class)))
                    .thenReturn(List.of(
                            historyEntry(true, false, atUtc(today, 9)),
                            historyEntry(true, false, atUtc(today.minusDays(1), 9)),
                            historyEntry(true, false, atUtc(today.minusDays(2), 9))
                    ));

            assertThat(service.computePlantStreak(1L, "UTC")).isEqualTo(3);
        }

        @Test
        @DisplayName("should_dedup_by_user_timezone_when_cares_straddle_utc_midnight")
        void should_dedup_by_user_timezone_when_cares_straddle_utc_midnight() {
            // Europe/Moscow = UTC+3. Два ухода по разные стороны UTC-полуночи,
            // но в один локальный день юзера → стрик 1 (дедуп по TZ юзера, не по UTC).
            // 2026-06-07 22:00 UTC → 2026-06-08 01:00 MSK
            // 2026-06-08 02:00 UTC → 2026-06-08 05:00 MSK  (тот же локальный день)
            LocalDateTime beforeUtcMidnight = LocalDateTime.of(2026, 6, 7, 22, 0);
            LocalDateTime afterUtcMidnight = LocalDateTime.of(2026, 6, 8, 2, 0);
            when(scheduleRepository.findAllByPlantId(1L)).thenReturn(List.of());
            when(historyRepository.findAllByPlantIdOrderByDoneAtDesc(eq(1L), any(Limit.class)))
                    .thenReturn(List.of(
                            historyEntry(true, false, afterUtcMidnight),
                            historyEntry(true, false, beforeUtcMidnight)
                    ));

            assertThat(service.computePlantStreak(1L, "Europe/Moscow")).isEqualTo(1);
        }

        @Test
        @DisplayName("Те же два ухода по UTC дают 2 дня — контроль, что дедуп идёт по TZ")
        void sameTwoCares_underUtc_countAsTwoDistinctDays() {
            LocalDateTime beforeUtcMidnight = LocalDateTime.of(2026, 6, 7, 22, 0);
            LocalDateTime afterUtcMidnight = LocalDateTime.of(2026, 6, 8, 2, 0);
            when(scheduleRepository.findAllByPlantId(1L)).thenReturn(List.of());
            when(historyRepository.findAllByPlantIdOrderByDoneAtDesc(eq(1L), any(Limit.class)))
                    .thenReturn(List.of(
                            historyEntry(true, false, afterUtcMidnight),
                            historyEntry(true, false, beforeUtcMidnight)
                    ));

            assertThat(service.computePlantStreak(1L, "UTC")).isEqualTo(2);
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

            CareHistoryService.PlantStats stats = service.getPlantStats(1L, "UTC");

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

            CareHistoryService.PlantStats stats = service.getPlantStats(1L, "UTC");

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

            CareHistoryService.PlantStats stats = service.getPlantStats(1L, "UTC");

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

            CareHistoryService.PlantStats stats = service.getPlantStats(1L, "UTC");
            assertThat(stats.hasEnoughData()).isFalse();
        }
    }

    @Nested
    @DisplayName("countAllByUser")
    class CountAllByUserTests {

        @Test
        @DisplayName("should_return_zero_when_user_has_no_care_history")
        void should_return_zero_when_user_has_no_care_history() {
            when(historyRepository.countAllByUserId(42L)).thenReturn(0L);

            assertThat(service.countAllByUser(42L)).isZero();
        }

        @Test
        @DisplayName("should_return_correct_count_when_user_has_care_events")
        void should_return_correct_count_when_user_has_care_events() {
            when(historyRepository.countAllByUserId(42L)).thenReturn(348L);

            assertThat(service.countAllByUser(42L)).isEqualTo(348L);
        }
    }

    // ===== helpers =====

    /** Создаёт мок-CareHistory с флагами on_time / cancelled и doneAt = now. */
    private CareHistory historyEntry(boolean onTime, boolean cancelled) {
        return historyEntry(onTime, cancelled, LocalDateTime.now());
    }

    /**
     * Создаёт мок-CareHistory с явным {@code doneAt} (хранится как LocalDateTime
     * в UTC) — нужно для проверки дедупа стрика по уникальным дням в TZ юзера.
     */
    private CareHistory historyEntry(boolean onTime, boolean cancelled, LocalDateTime doneAt) {
        CareHistory h = new CareHistory();
        h.setTaskType(TaskType.WATERING);
        h.setDoneAt(doneAt);
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
