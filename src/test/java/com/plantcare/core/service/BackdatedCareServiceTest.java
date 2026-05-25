package com.plantcare.core.service;

import com.plantcare.core.domain.CareHistory;
import com.plantcare.core.domain.CareSchedule;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.repository.CareHistoryRepository;
import com.plantcare.core.repository.CareScheduleRepository;
import com.plantcare.core.seasonal.service.SeasonalIntervalService;
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

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты для {@link BackdatedCareService} (ретро-отметка, issue #118).
 *
 * <p>Сервис чисто-доменный: репозитории мокаются, потому что бизнес-правила
 * (идемпотентность, окно «не старше 7 дней», расчёт UTC-полдня) проверяются
 * без обращения к БД. Интеграция метода {@code existsActiveByPlantIdAndTaskTypeInRange}
 * с реальным Postgres тестируется отдельно в {@code CareHistoryRepositoryTest}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BackdatedCareService — ретро-отметка ухода (#118)")
class BackdatedCareServiceTest {

    @Mock private CareHistoryRepository careHistoryRepository;
    @Mock private CareScheduleRepository careScheduleRepository;
    @Mock private SeasonalIntervalService seasonalIntervalService;

    /** Фиксируем «сегодня» = 2026-05-24, UTC. Часть тестов переопределяет clock. */
    private static final Instant FIXED_NOW_UTC = Instant.parse("2026-05-24T10:00:00Z");

    private BackdatedCareService service;
    private Plant plant;
    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .telegramChatId(100L)
                .timezone("UTC")
                .build();

        plant = Plant.builder()
                .user(user)
                .name("Монстера")
                .build();
        setId(plant, 7L);

        // Сезонная корректировка по умолчанию возвращает базовый интервал —
        // в этом тесте мы её отдельно не проверяем.
        when(seasonalIntervalService.effectiveIntervalDays(any(), any(), anyInt()))
                .thenAnswer(inv -> inv.getArgument(2));

        service = new BackdatedCareService(
                careHistoryRepository,
                careScheduleRepository,
                seasonalIntervalService,
                Clock.fixed(FIXED_NOW_UTC, ZoneOffset.UTC)
        );
    }

    private void rebuildServiceWithClock(Clock clock) {
        service = new BackdatedCareService(
                careHistoryRepository,
                careScheduleRepository,
                seasonalIntervalService,
                clock
        );
    }

    // ====================================================================
    // Happy path
    // ====================================================================

    @Nested
    @DisplayName("CREATED — нормальный happy path")
    class Created {

        @Test
        @DisplayName("Сегодня → CareHistory с doneAt = 12:00 локально (=UTC) и nextDueAt от ретро-даты")
        void should_create_history_and_reschedule_when_recording_today() {
            CareSchedule schedule = scheduleFor(TaskType.WATERING, 7,
                    LocalDateTime.of(2026, 5, 27, 10, 0));
            when(careScheduleRepository.findByPlantIdAndTaskType(7L, TaskType.WATERING))
                    .thenReturn(Optional.of(schedule));
            when(careHistoryRepository.existsActiveByPlantIdAndTaskTypeInRange(any(), any(), any(), any()))
                    .thenReturn(false);

            BackdatedCareService.BackdatedCareResult result = service.recordBackdatedCare(
                    plant, TaskType.WATERING, LocalDate.of(2026, 5, 24));

            assertThat(result.outcome()).isEqualTo(BackdatedCareService.Outcome.CREATED);
            assertThat(result.isCreated()).isTrue();

            ArgumentCaptor<CareHistory> historyCap = ArgumentCaptor.forClass(CareHistory.class);
            verify(careHistoryRepository).save(historyCap.capture());
            CareHistory saved = historyCap.getValue();
            assertThat(saved.getPlant()).isEqualTo(plant);
            assertThat(saved.getTaskType()).isEqualTo(TaskType.WATERING);
            // doneAt = 12:00 UTC, потому что TZ юзера UTC.
            assertThat(saved.getDoneAt()).isEqualTo(LocalDateTime.of(2026, 5, 24, 12, 0));
            assertThat(saved.isOnTime()).isTrue();
            assertThat(saved.getNote()).isEqualTo("backdated");

            // Расписание сдвинуто от 12:00 + 7 дней = 31 мая.
            verify(careScheduleRepository).save(schedule);
            assertThat(schedule.getNextDueAt()).isEqualTo(LocalDateTime.of(2026, 5, 31, 12, 0));
            assertThat(result.nextDueAt()).isEqualTo(LocalDateTime.of(2026, 5, 31, 12, 0));
        }

        @Test
        @DisplayName("Дата -7 дней включительно → CREATED (граница допустима)")
        void should_accept_seven_days_ago_as_inclusive_boundary() {
            CareSchedule schedule = scheduleFor(TaskType.WATERING, 7,
                    LocalDateTime.of(2026, 5, 27, 10, 0));
            when(careScheduleRepository.findByPlantIdAndTaskType(7L, TaskType.WATERING))
                    .thenReturn(Optional.of(schedule));
            when(careHistoryRepository.existsActiveByPlantIdAndTaskTypeInRange(any(), any(), any(), any()))
                    .thenReturn(false);

            BackdatedCareService.BackdatedCareResult result = service.recordBackdatedCare(
                    plant, TaskType.WATERING, LocalDate.of(2026, 5, 17));

            assertThat(result.outcome()).isEqualTo(BackdatedCareService.Outcome.CREATED);
            verify(careHistoryRepository).save(any(CareHistory.class));
        }

        @Test
        @DisplayName("Без активного расписания: история всё равно пишется, nextDueAt = null")
        void should_create_history_even_without_active_schedule() {
            when(careScheduleRepository.findByPlantIdAndTaskType(7L, TaskType.WATERING))
                    .thenReturn(Optional.empty());
            when(careHistoryRepository.existsActiveByPlantIdAndTaskTypeInRange(any(), any(), any(), any()))
                    .thenReturn(false);

            BackdatedCareService.BackdatedCareResult result = service.recordBackdatedCare(
                    plant, TaskType.WATERING, LocalDate.of(2026, 5, 22));

            assertThat(result.outcome()).isEqualTo(BackdatedCareService.Outcome.CREATED);
            assertThat(result.nextDueAt()).isNull();
            verify(careHistoryRepository).save(any(CareHistory.class));
            verify(careScheduleRepository, never()).save(any());
        }

        @Test
        @DisplayName("rescheduleFrom от ретро-даты, не от now() (интервал отсчитывается от doneAt)")
        void should_reschedule_from_backdated_done_at_not_from_now() {
            // doneDate = 2026-05-22 (3 дня назад), interval = 7
            CareSchedule schedule = scheduleFor(TaskType.WATERING, 7,
                    LocalDateTime.of(2026, 5, 30, 10, 0));
            when(careScheduleRepository.findByPlantIdAndTaskType(7L, TaskType.WATERING))
                    .thenReturn(Optional.of(schedule));
            when(careHistoryRepository.existsActiveByPlantIdAndTaskTypeInRange(any(), any(), any(), any()))
                    .thenReturn(false);

            service.recordBackdatedCare(plant, TaskType.WATERING, LocalDate.of(2026, 5, 22));

            // 2026-05-22 12:00 + 7 дней = 2026-05-29 12:00.
            // Если бы считали от now() = 2026-05-24 10:00, получили бы 31 мая — это и проверяем.
            assertThat(schedule.getNextDueAt()).isEqualTo(LocalDateTime.of(2026, 5, 29, 12, 0));
        }
    }

    // ====================================================================
    // Edge: окно
    // ====================================================================

    @Nested
    @DisplayName("Валидация окна дат")
    class Validation {

        @Test
        @DisplayName("Дата +1 день относительно сегодня → IN_FUTURE, ничего не пишется")
        void should_return_in_future_when_date_after_today() {
            BackdatedCareService.BackdatedCareResult result = service.recordBackdatedCare(
                    plant, TaskType.WATERING, LocalDate.of(2026, 5, 25));

            assertThat(result.outcome()).isEqualTo(BackdatedCareService.Outcome.IN_FUTURE);
            verify(careHistoryRepository, never()).save(any());
            verify(careScheduleRepository, never()).save(any());
        }

        @Test
        @DisplayName("Дата -8 дней → TOO_OLD")
        void should_return_too_old_when_more_than_seven_days_ago() {
            BackdatedCareService.BackdatedCareResult result = service.recordBackdatedCare(
                    plant, TaskType.WATERING, LocalDate.of(2026, 5, 16));

            assertThat(result.outcome()).isEqualTo(BackdatedCareService.Outcome.TOO_OLD);
            verify(careHistoryRepository, never()).save(any());
            verify(careScheduleRepository, never()).save(any());
        }
    }

    // ====================================================================
    // Идемпотентность
    // ====================================================================

    @Nested
    @DisplayName("Идемпотентность по локальному дню")
    class Idempotency {

        @Test
        @DisplayName("Повторный вызов в тот же день и тот же taskType → ALREADY_RECORDED, второй history не пишется")
        void should_not_create_duplicate_for_same_day_same_task() {
            when(careHistoryRepository.existsActiveByPlantIdAndTaskTypeInRange(any(), any(), any(), any()))
                    .thenReturn(true);

            BackdatedCareService.BackdatedCareResult result = service.recordBackdatedCare(
                    plant, TaskType.WATERING, LocalDate.of(2026, 5, 24));

            assertThat(result.outcome()).isEqualTo(BackdatedCareService.Outcome.ALREADY_RECORDED);
            verify(careHistoryRepository, never()).save(any());
            verify(careScheduleRepository, never()).save(any());
        }

        @Test
        @DisplayName("Одна дата, разные taskType (WATERING + MISTING) → оба CREATED")
        void should_allow_different_task_types_on_same_day() {
            CareSchedule watering = scheduleFor(TaskType.WATERING, 7,
                    LocalDateTime.of(2026, 5, 30, 10, 0));
            CareSchedule misting = scheduleFor(TaskType.MISTING, 3,
                    LocalDateTime.of(2026, 5, 26, 10, 0));
            when(careScheduleRepository.findByPlantIdAndTaskType(7L, TaskType.WATERING))
                    .thenReturn(Optional.of(watering));
            when(careScheduleRepository.findByPlantIdAndTaskType(7L, TaskType.MISTING))
                    .thenReturn(Optional.of(misting));

            // ВАЖНО: каждый вызов «existsActive» получает свой taskType, поэтому stub честно говорит false
            // для обоих. Симулируем независимость между типами.
            when(careHistoryRepository.existsActiveByPlantIdAndTaskTypeInRange(any(), any(), any(), any()))
                    .thenReturn(false);

            LocalDate yesterday = LocalDate.of(2026, 5, 23);

            BackdatedCareService.BackdatedCareResult resW = service.recordBackdatedCare(
                    plant, TaskType.WATERING, yesterday);
            BackdatedCareService.BackdatedCareResult resM = service.recordBackdatedCare(
                    plant, TaskType.MISTING, yesterday);

            assertThat(resW.outcome()).isEqualTo(BackdatedCareService.Outcome.CREATED);
            assertThat(resM.outcome()).isEqualTo(BackdatedCareService.Outcome.CREATED);
            verify(careHistoryRepository, org.mockito.Mockito.times(2)).save(any(CareHistory.class));
        }
    }

    // ====================================================================
    // Таймзоны
    // ====================================================================

    @Nested
    @DisplayName("Таймзоны (Asia/Almaty)")
    class Timezones {

        @Test
        @DisplayName("Юзер в Asia/Almaty (UTC+5), today=2026-05-24 → doneAt 07:00 UTC (=12:00 локально)")
        void should_store_done_at_at_local_noon_for_non_utc_user() {
            user.setTimezone("Asia/Almaty");
            // Чтобы LocalDate.now(clock.withZone(Almaty)) тоже было 2026-05-24,
            // выставим момент 10:00 UTC = 15:00 Almaty (тот же календарный день).
            rebuildServiceWithClock(Clock.fixed(
                    Instant.parse("2026-05-24T10:00:00Z"), ZoneOffset.UTC));

            when(careScheduleRepository.findByPlantIdAndTaskType(7L, TaskType.WATERING))
                    .thenReturn(Optional.empty());
            when(careHistoryRepository.existsActiveByPlantIdAndTaskTypeInRange(any(), any(), any(), any()))
                    .thenReturn(false);

            BackdatedCareService.BackdatedCareResult result = service.recordBackdatedCare(
                    plant, TaskType.WATERING, LocalDate.of(2026, 5, 24));

            assertThat(result.outcome()).isEqualTo(BackdatedCareService.Outcome.CREATED);

            ArgumentCaptor<CareHistory> cap = ArgumentCaptor.forClass(CareHistory.class);
            verify(careHistoryRepository).save(cap.capture());

            // 12:00 в Almaty = 07:00 UTC (того же календарного дня).
            Instant expectedInstant = ZonedDateTime.of(2026, 5, 24, 12, 0, 0, 0,
                    ZoneId.of("Asia/Almaty")).toInstant();
            LocalDateTime expectedUtc = LocalDateTime.ofInstant(expectedInstant, ZoneOffset.UTC);
            assertThat(cap.getValue().getDoneAt()).isEqualTo(expectedUtc);
            assertThat(expectedUtc).isEqualTo(LocalDateTime.of(2026, 5, 24, 7, 0));
        }
    }

    // ====================================================================
    // Контрактные проверки
    // ====================================================================

    @Nested
    @DisplayName("Контрактные проверки входа")
    class Contracts {

        @Test
        @DisplayName("null plant → IllegalArgumentException")
        void should_reject_null_plant() {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    service.recordBackdatedCare(null, TaskType.WATERING, LocalDate.of(2026, 5, 24)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null taskType → IllegalArgumentException")
        void should_reject_null_task_type() {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    service.recordBackdatedCare(plant, null, LocalDate.of(2026, 5, 24)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null doneDate → IllegalArgumentException")
        void should_reject_null_done_date() {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    service.recordBackdatedCare(plant, TaskType.WATERING, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ====================================================================
    // helpers
    // ====================================================================

    private CareSchedule scheduleFor(TaskType type, int interval, LocalDateTime nextDueAt) {
        return CareSchedule.builder()
                .plant(plant)
                .taskType(type)
                .intervalDays(interval)
                .nextDueAt(nextDueAt)
                .active(true)
                .build();
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
