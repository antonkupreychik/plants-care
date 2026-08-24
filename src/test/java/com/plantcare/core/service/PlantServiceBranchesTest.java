package com.plantcare.core.service;

import com.plantcare.core.domain.CareHistory;
import com.plantcare.core.domain.CareSchedule;
import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.Species;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.HealthZone;
import com.plantcare.core.domain.enums.SeasonalOverride;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.repository.CareHistoryRepository;
import com.plantcare.core.repository.CareScheduleRepository;
import com.plantcare.core.repository.PlantRepository;
import com.plantcare.core.repository.SpeciesRepository;
import com.plantcare.core.seasonal.service.SeasonalIntervalService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ветвевое (branch) покрытие {@link PlantService}, дополняющее интеграционные
 * тесты в {@link PlantServiceTest}. Чистый Mockito без Testcontainers — для
 * веток без похода в реальную БД (валидация, ветвления по optional-полям,
 * clamping пагинации).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PlantService — ветвевое покрытие")
class PlantServiceBranchesTest {

    @Mock private PlantRepository plantRepository;
    @Mock private CareScheduleRepository careScheduleRepository;
    @Mock private CareHistoryRepository careHistoryRepository;
    @Mock private SpeciesRepository speciesRepository;
    @Mock private LocationService locationService;
    @Mock private SeasonalIntervalService seasonalIntervalService;
    @Mock private HealthScoreService healthScoreService;
    @Mock private PlantDiagnosisReportService plantDiagnosisReportService;

    private static final Instant FIXED_INSTANT = LocalDateTime.of(2026, 3, 10, 9, 0)
            .toInstant(ZoneOffset.UTC);
    private final Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    private PlantService service;

    private User user;
    private Location location;

    @BeforeEach
    void setUp() {
        service = new PlantService(
                plantRepository,
                careScheduleRepository,
                careHistoryRepository,
                speciesRepository,
                locationService,
                seasonalIntervalService,
                healthScoreService,
                plantDiagnosisReportService,
                clock
        );

        user = User.builder().telegramChatId(1L).timezone("Europe/Moscow").build();
        ReflectionTestUtils.setField(user, "id", 1L);

        location = Location.builder().user(user).name("Кухня").emoji("🍳").build();
        ReflectionTestUtils.setField(location, "id", 10L);
    }

    private Plant plant(Long id, User owner, boolean archived) {
        Plant p = Plant.builder()
                .user(owner)
                .location(location)
                .name("Монстера")
                .build();
        ReflectionTestUtils.setField(p, "id", id);
        if (archived) {
            p.setArchivedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        }
        return p;
    }

    // ===================== listPlants / queryPlants =====================

    @Nested
    @DisplayName("listPlants")
    class ListPlantsTests {

        @Test
        @DisplayName("should_throw_when_offset_is_not_multiple_of_limit")
        void should_throw_when_offset_is_not_multiple_of_limit() {
            assertThatThrownBy(() -> service.listPlants(1L, null, 5, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("offset must be a multiple of limit");
        }

        @Test
        @DisplayName("should_query_by_location_when_location_id_given")
        void should_query_by_location_when_location_id_given() {
            when(plantRepository.findAllByUserIdAndLocationIdAndArchivedAtIsNullOrderByNameAsc(
                    eq(1L), eq(10L), any(Pageable.class)))
                    .thenReturn(List.of(plant(100L, user, false)));

            List<Plant> result = service.listPlants(1L, 10L, 0, 20);

            assertThat(result).hasSize(1);
            verify(plantRepository, never())
                    .findAllByUserIdAndArchivedAtIsNullOrderByNameAsc(eq(1L), any(Pageable.class));
        }

        @Test
        @DisplayName("should_query_without_location_when_location_id_null")
        void should_query_without_location_when_location_id_null() {
            when(plantRepository.findAllByUserIdAndArchivedAtIsNullOrderByNameAsc(eq(1L), any(Pageable.class)))
                    .thenReturn(List.of());

            List<Plant> result = service.listPlants(1L, null, 0, 20);

            assertThat(result).isEmpty();
            verify(plantRepository, never()).findAllByUserIdAndLocationIdAndArchivedAtIsNullOrderByNameAsc(
                    any(), any(), any());
        }

        @Test
        @DisplayName("should_clamp_limit_to_100_when_limit_exceeds_maximum")
        void should_clamp_limit_to_100_when_limit_exceeds_maximum() {
            when(plantRepository.findAllByUserIdAndArchivedAtIsNullOrderByNameAsc(eq(1L), any(Pageable.class)))
                    .thenReturn(List.of());

            service.listPlants(1L, null, 0, 500);

            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(plantRepository).findAllByUserIdAndArchivedAtIsNullOrderByNameAsc(eq(1L), captor.capture());
            assertThat(captor.getValue().getPageSize()).isEqualTo(100);
        }

        @Test
        @DisplayName("should_clamp_limit_to_1_when_limit_below_minimum")
        void should_clamp_limit_to_1_when_limit_below_minimum() {
            when(plantRepository.findAllByUserIdAndArchivedAtIsNullOrderByNameAsc(eq(1L), any(Pageable.class)))
                    .thenReturn(List.of());

            service.listPlants(1L, null, 0, 0);

            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(plantRepository).findAllByUserIdAndArchivedAtIsNullOrderByNameAsc(eq(1L), captor.capture());
            assertThat(captor.getValue().getPageSize()).isEqualTo(1);
        }

        @Test
        @DisplayName("should_clamp_negative_offset_to_zero")
        void should_clamp_negative_offset_to_zero() {
            when(plantRepository.findAllByUserIdAndArchivedAtIsNullOrderByNameAsc(eq(1L), any(Pageable.class)))
                    .thenReturn(List.of());

            service.listPlants(1L, null, -5, 10);

            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(plantRepository).findAllByUserIdAndArchivedAtIsNullOrderByNameAsc(eq(1L), captor.capture());
            assertThat(captor.getValue()).isEqualTo(PageRequest.of(0, 10, org.springframework.data.domain.Sort.by("name").ascending()));
        }
    }

    // ===================== getPlantOrThrow =====================

    @Nested
    @DisplayName("getPlantOrThrow")
    class GetPlantOrThrowTests {

        @Test
        @DisplayName("should_throw_entity_not_found_when_plant_missing")
        void should_throw_entity_not_found_when_plant_missing() {
            when(plantRepository.findByIdWithLocationAndSpecies(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getPlantOrThrow(1L, 99L))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("99");
        }

        @Test
        @DisplayName("should_throw_access_denied_when_plant_belongs_to_another_user")
        void should_throw_access_denied_when_plant_belongs_to_another_user() {
            User otherUser = User.builder().telegramChatId(2L).build();
            ReflectionTestUtils.setField(otherUser, "id", 2L);
            Plant p = plant(5L, otherUser, false);
            when(plantRepository.findByIdWithLocationAndSpecies(5L)).thenReturn(Optional.of(p));

            assertThatThrownBy(() -> service.getPlantOrThrow(1L, 5L))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("should_throw_entity_not_found_when_plant_is_archived")
        void should_throw_entity_not_found_when_plant_is_archived() {
            Plant p = plant(6L, user, true);
            when(plantRepository.findByIdWithLocationAndSpecies(6L)).thenReturn(Optional.of(p));

            assertThatThrownBy(() -> service.getPlantOrThrow(1L, 6L))
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        @DisplayName("should_return_plant_when_owned_and_active")
        void should_return_plant_when_owned_and_active() {
            Plant p = plant(7L, user, false);
            when(plantRepository.findByIdWithLocationAndSpecies(7L)).thenReturn(Optional.of(p));

            Plant result = service.getPlantOrThrow(1L, 7L);

            assertThat(result.getId()).isEqualTo(7L);
        }
    }

    // ===================== countPlants =====================

    @Nested
    @DisplayName("countPlants")
    class CountPlantsTests {

        @Test
        @DisplayName("should_count_by_location_when_location_id_given")
        void should_count_by_location_when_location_id_given() {
            when(plantRepository.countByUserIdAndLocationIdAndArchivedAtIsNull(1L, 10L)).thenReturn(3L);

            long count = service.countPlants(1L, 10L);

            assertThat(count).isEqualTo(3L);
        }

        @Test
        @DisplayName("should_count_all_when_location_id_null")
        void should_count_all_when_location_id_null() {
            when(plantRepository.countByUserIdAndArchivedAtIsNull(1L)).thenReturn(9L);

            long count = service.countPlants(1L, null);

            assertThat(count).isEqualTo(9L);
        }
    }

    // ===================== getPlantWithHealth / listPlantsWithHealth / getPlantDiagnosis =====================

    @Test
    @DisplayName("should_combine_plant_with_health_when_requested")
    void should_combine_plant_with_health_when_requested() {
        Plant p = plant(8L, user, false);
        when(plantRepository.findByIdWithLocationAndSpecies(8L)).thenReturn(Optional.of(p));
        HealthScoreService.HealthScore score = HealthScoreService.HealthScore.of(80, HealthZone.GREEN);
        when(healthScoreService.computeForPlant(p)).thenReturn(score);

        PlantService.PlantWithHealth result = service.getPlantWithHealth(1L, 8L);

        assertThat(result.plant()).isEqualTo(p);
        assertThat(result.health()).isEqualTo(score);
    }

    @Test
    @DisplayName("should_attach_health_to_each_plant_when_listing_with_health")
    void should_attach_health_to_each_plant_when_listing_with_health() {
        Plant p1 = plant(1L, user, false);
        Plant p2 = plant(2L, user, false);
        when(plantRepository.findAllByUserIdAndArchivedAtIsNullOrderByNameAsc(eq(1L), any(Pageable.class)))
                .thenReturn(List.of(p1, p2));
        when(healthScoreService.computeForPlant(any(Plant.class)))
                .thenReturn(HealthScoreService.HealthScore.insufficient());

        List<PlantService.PlantWithHealth> result = service.listPlantsWithHealth(1L, null, 0, 20);

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(r -> r.health().insufficientData());
    }

    @Test
    @DisplayName("should_delegate_diagnosis_to_diagnosis_report_service")
    void should_delegate_diagnosis_to_diagnosis_report_service() {
        Plant p = plant(9L, user, false);
        when(plantRepository.findByIdWithLocationAndSpecies(9L)).thenReturn(Optional.of(p));
        PlantDiagnosisReportService.DiagnosisReport report =
                new PlantDiagnosisReportService.DiagnosisReport(List.of(), List.of());
        when(plantDiagnosisReportService.diagnose(p)).thenReturn(report);

        PlantDiagnosisReportService.DiagnosisReport result = service.getPlantDiagnosis(1L, 9L);

        assertThat(result).isSameAs(report);
    }

    // ===================== createPlant(schedules) =====================

    @Nested
    @DisplayName("createPlant с explicit schedules")
    class CreatePlantWithSchedulesTests {

        @BeforeEach
        void stubBaseCreate() {
            lenient().when(locationService.getOrCreateDefaultLocation(user)).thenReturn(location);
            lenient().when(plantRepository.save(any(Plant.class))).thenAnswer(inv -> {
                Plant p = inv.getArgument(0);
                ReflectionTestUtils.setField(p, "id", 50L);
                return p;
            });
            lenient().when(careScheduleRepository.save(any(CareSchedule.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            lenient().when(seasonalIntervalService.effectiveIntervalDays(any(), any(), anyInt()))
                    .thenAnswer(inv -> inv.getArgument(2));
        }

        @Test
        @DisplayName("should_throw_when_schedules_have_duplicate_task_type")
        void should_throw_when_schedules_have_duplicate_task_type() {
            List<PlantService.ScheduleInputDto> schedules = List.of(
                    new PlantService.ScheduleInputDto(TaskType.WATERING, 5, 200),
                    new PlantService.ScheduleInputDto(TaskType.WATERING, 7, 300)
            );

            assertThatThrownBy(() -> service.createPlant(user, "Фикус", null, null, null, null, null, schedules))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Дублирующийся тип расписания");
        }

        @Test
        @DisplayName("should_throw_when_custom_schedule_interval_invalid")
        void should_throw_when_custom_schedule_interval_invalid() {
            List<PlantService.ScheduleInputDto> schedules = List.of(
                    new PlantService.ScheduleInputDto(TaskType.WATERING, 400, null)
            );

            assertThatThrownBy(() -> service.createPlant(user, "Фикус", null, null, null, null, null, schedules))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Интервал должен быть от 1 до 365");
        }

        @Test
        @DisplayName("should_create_inactive_default_schedule_when_type_missing_from_input")
        void should_create_inactive_default_schedule_when_type_missing_from_input() {
            List<PlantService.ScheduleInputDto> schedules = List.of(
                    new PlantService.ScheduleInputDto(TaskType.WATERING, 5, 250)
            );

            service.createPlant(user, "Фикус", null, null, null, null, null, schedules);

            ArgumentCaptor<CareSchedule> captor = ArgumentCaptor.forClass(CareSchedule.class);
            verify(careScheduleRepository, times(4)).save(captor.capture());

            CareSchedule misting = captor.getAllValues().stream()
                    .filter(s -> s.getTaskType() == TaskType.MISTING)
                    .findFirst().orElseThrow();
            assertThat(misting.isActive()).isFalse();
            assertThat(misting.getAmountMl()).isNull();

            CareSchedule watering = captor.getAllValues().stream()
                    .filter(s -> s.getTaskType() == TaskType.WATERING)
                    .findFirst().orElseThrow();
            assertThat(watering.isActive()).isTrue();
            assertThat(watering.getAmountMl()).isEqualTo(250);
            assertThat(watering.getNextDueAt()).isEqualTo(LocalDateTime.now(clock).plusDays(5));
        }

        @Test
        @DisplayName("should_drop_amount_ml_for_non_watering_type_even_when_provided")
        void should_drop_amount_ml_for_non_watering_type_even_when_provided() {
            List<PlantService.ScheduleInputDto> schedules = List.of(
                    new PlantService.ScheduleInputDto(TaskType.MISTING, 3, 999)
            );

            service.createPlant(user, "Фикус", null, null, null, null, null, schedules);

            ArgumentCaptor<CareSchedule> captor = ArgumentCaptor.forClass(CareSchedule.class);
            verify(careScheduleRepository, times(4)).save(captor.capture());
            CareSchedule misting = captor.getAllValues().stream()
                    .filter(s -> s.getTaskType() == TaskType.MISTING)
                    .findFirst().orElseThrow();
            assertThat(misting.getAmountMl()).isNull();
        }

        @Test
        @DisplayName("should_seed_default_schedules_when_schedules_list_empty")
        void should_seed_default_schedules_when_schedules_list_empty() {
            service.createPlant(user, "Фикус", null, null, null, null, null, List.of());

            ArgumentCaptor<CareSchedule> captor = ArgumentCaptor.forClass(CareSchedule.class);
            verify(careScheduleRepository, times(4)).save(captor.capture());
            CareSchedule watering = captor.getAllValues().stream()
                    .filter(s -> s.getTaskType() == TaskType.WATERING)
                    .findFirst().orElseThrow();
            assertThat(watering.isActive()).isTrue();
            assertThat(watering.getIntervalDays()).isEqualTo(7);
        }
    }

    // ===================== getSchedules =====================

    @Nested
    @DisplayName("getSchedules")
    class GetSchedulesTests {

        @Test
        @DisplayName("should_return_enabled_schedule_with_next_due_at_when_row_exists_and_active")
        void should_return_enabled_schedule_with_next_due_at_when_row_exists_and_active() {
            Plant p = plant(11L, user, false);
            when(plantRepository.findByIdWithLocationAndSpecies(11L)).thenReturn(Optional.of(p));
            CareSchedule row = CareSchedule.builder()
                    .plant(p).taskType(TaskType.WATERING).intervalDays(5)
                    .nextDueAt(LocalDateTime.now(clock).plusDays(5)).active(true).build();
            when(careScheduleRepository.findAllByPlantId(11L)).thenReturn(List.of(row));
            when(seasonalIntervalService.isSeasonalActive(any(), any())).thenReturn(false);
            when(seasonalIntervalService.effectiveIntervalForSeason(any(), any(), anyInt(), any()))
                    .thenAnswer(inv -> inv.getArgument(2));

            List<PlantService.ScheduleView> views = service.getSchedules(1L, 11L);

            PlantService.ScheduleView watering = views.stream()
                    .filter(v -> v.type() == TaskType.WATERING).findFirst().orElseThrow();
            assertThat(watering.enabled()).isTrue();
            assertThat(watering.nextDueAt()).isEqualTo(row.getNextDueAt());
        }

        @Test
        @DisplayName("should_return_null_next_due_at_when_row_exists_but_inactive")
        void should_return_null_next_due_at_when_row_exists_but_inactive() {
            Plant p = plant(12L, user, false);
            when(plantRepository.findByIdWithLocationAndSpecies(12L)).thenReturn(Optional.of(p));
            CareSchedule row = CareSchedule.builder()
                    .plant(p).taskType(TaskType.MISTING).intervalDays(3)
                    .nextDueAt(LocalDateTime.now(clock)).active(false).build();
            when(careScheduleRepository.findAllByPlantId(12L)).thenReturn(List.of(row));
            when(seasonalIntervalService.isSeasonalActive(any(), any())).thenReturn(false);
            when(seasonalIntervalService.effectiveIntervalForSeason(any(), any(), anyInt(), any()))
                    .thenAnswer(inv -> inv.getArgument(2));

            List<PlantService.ScheduleView> views = service.getSchedules(1L, 12L);

            PlantService.ScheduleView misting = views.stream()
                    .filter(v -> v.type() == TaskType.MISTING).findFirst().orElseThrow();
            assertThat(misting.enabled()).isFalse();
            assertThat(misting.nextDueAt()).isNull();
        }

        @Test
        @DisplayName("should_use_default_interval_when_no_row_for_type")
        void should_use_default_interval_when_no_row_for_type() {
            Plant p = plant(13L, user, false);
            when(plantRepository.findByIdWithLocationAndSpecies(13L)).thenReturn(Optional.of(p));
            when(careScheduleRepository.findAllByPlantId(13L)).thenReturn(List.of());
            when(seasonalIntervalService.isSeasonalActive(any(), any())).thenReturn(false);
            when(seasonalIntervalService.effectiveIntervalForSeason(any(), any(), anyInt(), any()))
                    .thenAnswer(inv -> inv.getArgument(2));

            List<PlantService.ScheduleView> views = service.getSchedules(1L, 13L);

            PlantService.ScheduleView fertilizing = views.stream()
                    .filter(v -> v.type() == TaskType.FERTILIZING).findFirst().orElseThrow();
            assertThat(fertilizing.enabled()).isFalse();
            assertThat(fertilizing.every()).isEqualTo(14);
            assertThat(fertilizing.nextDueAt()).isNull();
        }
    }

    // ===================== updateSchedule =====================

    @Nested
    @DisplayName("updateSchedule")
    class UpdateScheduleTests {

        @Test
        @DisplayName("should_throw_when_interval_invalid")
        void should_throw_when_interval_invalid() {
            assertThatThrownBy(() -> service.updateSchedule(1L, 20L, TaskType.WATERING, 0, null, true, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Интервал должен быть");
        }

        @Test
        @DisplayName("should_throw_when_watering_amount_not_positive")
        void should_throw_when_watering_amount_not_positive() {
            assertThatThrownBy(() -> service.updateSchedule(1L, 20L, TaskType.WATERING, 5, -1, true, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Объём должен быть положительным");
        }

        @Test
        @DisplayName("should_set_seasonal_override_when_provided")
        void should_set_seasonal_override_when_provided() {
            Plant p = plant(21L, user, false);
            when(plantRepository.findByIdWithLocationAndSpecies(21L)).thenReturn(Optional.of(p));
            when(careScheduleRepository.findByPlantIdAndTaskType(21L, TaskType.WATERING)).thenReturn(Optional.empty());
            when(seasonalIntervalService.effectiveIntervalDays(any(), any(), anyInt())).thenReturn(5);
            when(seasonalIntervalService.isSeasonalActive(any(), any())).thenReturn(false);
            when(seasonalIntervalService.effectiveIntervalForSeason(any(), any(), anyInt(), any()))
                    .thenAnswer(inv -> inv.getArgument(2));
            when(careScheduleRepository.save(any(CareSchedule.class))).thenAnswer(inv -> inv.getArgument(0));

            service.updateSchedule(1L, 21L, TaskType.WATERING, 5, 200, true, SeasonalOverride.ON);

            assertThat(p.getSeasonalOverride()).isEqualTo(SeasonalOverride.ON);
        }

        @Test
        @DisplayName("should_create_new_schedule_when_none_exists")
        void should_create_new_schedule_when_none_exists() {
            Plant p = plant(22L, user, false);
            when(plantRepository.findByIdWithLocationAndSpecies(22L)).thenReturn(Optional.of(p));
            when(careScheduleRepository.findByPlantIdAndTaskType(22L, TaskType.WATERING)).thenReturn(Optional.empty());
            when(seasonalIntervalService.effectiveIntervalDays(any(), any(), anyInt())).thenReturn(5);
            when(seasonalIntervalService.isSeasonalActive(any(), any())).thenReturn(false);
            when(seasonalIntervalService.effectiveIntervalForSeason(any(), any(), anyInt(), any()))
                    .thenAnswer(inv -> inv.getArgument(2));
            when(careScheduleRepository.save(any(CareSchedule.class))).thenAnswer(inv -> inv.getArgument(0));

            PlantService.ScheduleView view = service.updateSchedule(1L, 22L, TaskType.WATERING, 5, 200, true, null);

            assertThat(view.nextDueAt()).isEqualTo(LocalDateTime.now(clock).plusDays(5));
            assertThat(view.enabled()).isTrue();
        }

        @Test
        @DisplayName("should_update_existing_schedule_and_not_reschedule_when_disabled")
        void should_update_existing_schedule_and_not_reschedule_when_disabled() {
            Plant p = plant(23L, user, false);
            CareSchedule existing = CareSchedule.builder()
                    .plant(p).taskType(TaskType.WATERING).intervalDays(3)
                    .nextDueAt(LocalDateTime.of(2020, 1, 1, 0, 0)).active(true).build();
            when(plantRepository.findByIdWithLocationAndSpecies(23L)).thenReturn(Optional.of(p));
            when(careScheduleRepository.findByPlantIdAndTaskType(23L, TaskType.WATERING)).thenReturn(Optional.of(existing));
            when(seasonalIntervalService.effectiveIntervalDays(any(), any(), anyInt())).thenReturn(10);
            when(seasonalIntervalService.isSeasonalActive(any(), any())).thenReturn(false);
            when(seasonalIntervalService.effectiveIntervalForSeason(any(), any(), anyInt(), any()))
                    .thenAnswer(inv -> inv.getArgument(2));
            when(careScheduleRepository.save(any(CareSchedule.class))).thenAnswer(inv -> inv.getArgument(0));

            PlantService.ScheduleView view = service.updateSchedule(1L, 23L, TaskType.WATERING, 10, 200, false, null);

            assertThat(view.enabled()).isFalse();
            assertThat(view.nextDueAt()).isNull();
            // next_due_at на самой entity НЕ пересчитан, т.к. enabled=false
            assertThat(existing.getNextDueAt()).isEqualTo(LocalDateTime.of(2020, 1, 1, 0, 0));
        }
    }

    // ===================== getPlantFamily =====================

    @Nested
    @DisplayName("getPlantFamily")
    class GetPlantFamilyTests {

        @Test
        @DisplayName("should_return_null_parent_when_plant_has_no_parent")
        void should_return_null_parent_when_plant_has_no_parent() {
            Plant p = plant(30L, user, false);
            when(plantRepository.findByIdWithLocationAndSpecies(30L)).thenReturn(Optional.of(p));
            when(plantRepository.findAllByUserIdAndParentIdAndArchivedAtIsNullOrderByNameAsc(1L, 30L))
                    .thenReturn(List.of());

            PlantService.PlantFamily family = service.getPlantFamily(1L, 30L);

            assertThat(family.parent()).isNull();
            assertThat(family.children()).isEmpty();
        }

        @Test
        @DisplayName("should_return_parent_when_parent_found")
        void should_return_parent_when_parent_found() {
            Plant parent = plant(31L, user, false);
            Plant child = plant(32L, user, false);
            child.setParent(parent);
            when(plantRepository.findByIdWithLocationAndSpecies(32L)).thenReturn(Optional.of(child));
            when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 31L)).thenReturn(Optional.of(parent));
            when(plantRepository.findAllByUserIdAndParentIdAndArchivedAtIsNullOrderByNameAsc(1L, 32L))
                    .thenReturn(List.of());

            PlantService.PlantFamily family = service.getPlantFamily(1L, 32L);

            assertThat(family.parent()).isEqualTo(parent);
        }

        @Test
        @DisplayName("should_return_null_parent_when_parent_reference_is_dangling")
        void should_return_null_parent_when_parent_reference_is_dangling() {
            Plant parent = plant(33L, user, false);
            Plant child = plant(34L, user, false);
            child.setParent(parent);
            when(plantRepository.findByIdWithLocationAndSpecies(34L)).thenReturn(Optional.of(child));
            when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 33L)).thenReturn(Optional.empty());
            when(plantRepository.findAllByUserIdAndParentIdAndArchivedAtIsNullOrderByNameAsc(1L, 34L))
                    .thenReturn(List.of());

            PlantService.PlantFamily family = service.getPlantFamily(1L, 34L);

            assertThat(family.parent()).isNull();
        }
    }

    // ===================== updatePlant =====================

    @Nested
    @DisplayName("updatePlant")
    class UpdatePlantTests {

        @Test
        @DisplayName("should_leave_fields_untouched_when_all_optional_args_null")
        void should_leave_fields_untouched_when_all_optional_args_null() {
            Plant p = plant(40L, user, false);
            p.setNotes("старые заметки");
            when(plantRepository.findByIdWithLocationAndSpecies(40L)).thenReturn(Optional.of(p));
            when(plantRepository.save(any(Plant.class))).thenAnswer(inv -> inv.getArgument(0));

            Plant result = service.updatePlant(1L, 40L, null, null, null, null, null);

            assertThat(result.getName()).isEqualTo("Монстера");
            assertThat(result.getNotes()).isEqualTo("старые заметки");
        }

        @Test
        @DisplayName("should_update_location_when_location_id_given")
        void should_update_location_when_location_id_given() {
            Plant p = plant(41L, user, false);
            Location newLocation = Location.builder().user(user).name("Спальня").build();
            ReflectionTestUtils.setField(newLocation, "id", 20L);
            when(plantRepository.findByIdWithLocationAndSpecies(41L)).thenReturn(Optional.of(p));
            when(locationService.getUserLocationOrThrow(1L, 20L)).thenReturn(newLocation);
            when(plantRepository.save(any(Plant.class))).thenAnswer(inv -> inv.getArgument(0));

            Plant result = service.updatePlant(1L, 41L, "Новое имя", "заметка", 20L);

            assertThat(result.getLocation()).isEqualTo(newLocation);
            assertThat(result.getName()).isEqualTo("Новое имя");
        }

        @Test
        @DisplayName("should_throw_when_species_id_not_found")
        void should_throw_when_species_id_not_found() {
            Plant p = plant(42L, user, false);
            when(plantRepository.findByIdWithLocationAndSpecies(42L)).thenReturn(Optional.of(p));
            when(speciesRepository.findById(777L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updatePlant(1L, 42L, null, null, null, 777L, null))
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        @DisplayName("should_clear_species_when_clear_species_true_and_species_id_null")
        void should_clear_species_when_clear_species_true_and_species_id_null() {
            Plant p = plant(43L, user, false);
            p.setSpecies(Species.builder().name("Монстера деликатесная").build());
            when(plantRepository.findByIdWithLocationAndSpecies(43L)).thenReturn(Optional.of(p));
            when(plantRepository.save(any(Plant.class))).thenAnswer(inv -> inv.getArgument(0));

            Plant result = service.updatePlant(1L, 43L, null, null, null, null, true);

            assertThat(result.getSpecies()).isNull();
        }

        @Test
        @DisplayName("should_set_species_when_species_id_given")
        void should_set_species_when_species_id_given() {
            Plant p = plant(44L, user, false);
            Species species = Species.builder().name("Фикус").build();
            ReflectionTestUtils.setField(species, "id", 5L);
            when(plantRepository.findByIdWithLocationAndSpecies(44L)).thenReturn(Optional.of(p));
            when(speciesRepository.findById(5L)).thenReturn(Optional.of(species));
            when(plantRepository.save(any(Plant.class))).thenAnswer(inv -> inv.getArgument(0));

            Plant result = service.updatePlant(1L, 44L, null, null, null, 5L, null);

            assertThat(result.getSpecies()).isEqualTo(species);
        }
    }

    // ===================== getSpeciesById =====================

    @Test
    @DisplayName("should_return_empty_when_species_id_unknown")
    void should_return_empty_when_species_id_unknown() {
        when(speciesRepository.findById(404L)).thenReturn(Optional.empty());

        assertThat(service.getSpeciesById(404L)).isEmpty();
    }

    @Test
    @DisplayName("should_return_species_when_found_by_id")
    void should_return_species_when_found_by_id() {
        Species species = Species.builder().name("Сансевиерия").build();
        when(speciesRepository.findById(1L)).thenReturn(Optional.of(species));

        assertThat(service.getSpeciesById(1L)).contains(species);
    }

    // ===================== createPlantWithWateringSchedule =====================

    @Nested
    @DisplayName("createPlantWithWateringSchedule")
    class CreatePlantWithWateringScheduleTests {

        @Test
        @DisplayName("should_use_default_location_when_location_id_not_given")
        void should_use_default_location_when_location_id_not_given() {
            when(locationService.getOrCreateDefaultLocation(user)).thenReturn(location);
            when(plantRepository.save(any(Plant.class))).thenAnswer(inv -> {
                Plant p = inv.getArgument(0);
                ReflectionTestUtils.setField(p, "id", 60L);
                return p;
            });
            when(careScheduleRepository.save(any(CareSchedule.class))).thenAnswer(inv -> inv.getArgument(0));

            Plant result = service.createPlantWithWateringSchedule(
                    user, null, "Кактус", 10, LocalDateTime.now(clock).plusDays(10), null);

            assertThat(result.getLocation()).isEqualTo(location);
            assertThat(result.getSpecies()).isNull();
        }

        @Test
        @DisplayName("should_use_given_location_when_location_id_provided")
        void should_use_given_location_when_location_id_provided() {
            when(locationService.getLocation(1L, 10L)).thenReturn(location);
            when(plantRepository.save(any(Plant.class))).thenAnswer(inv -> {
                Plant p = inv.getArgument(0);
                ReflectionTestUtils.setField(p, "id", 61L);
                return p;
            });
            when(careScheduleRepository.save(any(CareSchedule.class))).thenAnswer(inv -> inv.getArgument(0));

            Plant result = service.createPlantWithWateringSchedule(
                    user, null, "Кактус", 10, LocalDateTime.now(clock).plusDays(10), 10L, LocalDate.of(2025, 1, 1));

            assertThat(result.getLocation()).isEqualTo(location);
            assertThat(result.getAcquiredAt()).isEqualTo(LocalDate.of(2025, 1, 1));
            verify(locationService, never()).getOrCreateDefaultLocation(any());
        }

        @Test
        @DisplayName("should_leave_species_null_when_species_id_not_found")
        void should_leave_species_null_when_species_id_not_found() {
            when(speciesRepository.findById(999L)).thenReturn(Optional.empty());
            when(locationService.getOrCreateDefaultLocation(user)).thenReturn(location);
            when(plantRepository.save(any(Plant.class))).thenAnswer(inv -> {
                Plant p = inv.getArgument(0);
                ReflectionTestUtils.setField(p, "id", 62L);
                return p;
            });
            when(careScheduleRepository.save(any(CareSchedule.class))).thenAnswer(inv -> inv.getArgument(0));

            Plant result = service.createPlantWithWateringSchedule(
                    user, 999L, "Кактус", 10, LocalDateTime.now(clock).plusDays(10));

            assertThat(result.getSpecies()).isNull();
        }
    }

    // ===================== movePlantToLocation / updatePhotoFileId / getOwnedPlantOrThrow =====================

    @Test
    @DisplayName("should_delegate_to_location_service_when_moving_plant")
    void should_delegate_to_location_service_when_moving_plant() {
        Plant moved = plant(70L, user, false);
        when(locationService.movePlant(1L, 70L, 20L)).thenReturn(moved);

        Plant result = service.movePlantToLocation(1L, 70L, 20L);

        assertThat(result).isSameAs(moved);
        verify(locationService).movePlant(1L, 70L, 20L);
    }

    @Test
    @DisplayName("should_throw_when_updating_photo_of_missing_plant")
    void should_throw_when_updating_photo_of_missing_plant() {
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 71L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updatePhotoFileId(1L, 71L, "file123"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("should_update_photo_file_id_when_plant_found")
    void should_update_photo_file_id_when_plant_found() {
        Plant p = plant(72L, user, false);
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 72L)).thenReturn(Optional.of(p));
        when(plantRepository.save(any(Plant.class))).thenAnswer(inv -> inv.getArgument(0));

        Plant result = service.updatePhotoFileId(1L, 72L, "file999");

        assertThat(result.getPhotoFileId()).isEqualTo("file999");
    }

    @Test
    @DisplayName("should_throw_not_found_when_owned_plant_missing")
    void should_throw_not_found_when_owned_plant_missing() {
        when(plantRepository.findByIdWithLocationAndSpecies(80L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOwnedPlantOrThrow(1L, 80L))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    @DisplayName("should_throw_not_found_when_owned_plant_belongs_to_another_user")
    void should_throw_not_found_when_owned_plant_belongs_to_another_user() {
        User otherUser = User.builder().telegramChatId(2L).build();
        ReflectionTestUtils.setField(otherUser, "id", 2L);
        Plant p = plant(81L, otherUser, false);
        when(plantRepository.findByIdWithLocationAndSpecies(81L)).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> service.getOwnedPlantOrThrow(1L, 81L))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    @DisplayName("should_return_owned_plant_even_when_archived")
    void should_return_owned_plant_even_when_archived() {
        Plant p = plant(82L, user, true);
        when(plantRepository.findByIdWithLocationAndSpecies(82L)).thenReturn(Optional.of(p));

        Plant result = service.getOwnedPlantOrThrow(1L, 82L);

        assertThat(result.isArchived()).isTrue();
    }

    // ===================== listArchivedPlants / computeTotalCareDays =====================

    @Nested
    @DisplayName("listArchivedPlants")
    class ListArchivedPlantsTests {

        @Test
        @DisplayName("should_compute_care_days_from_acquired_at_when_present")
        void should_compute_care_days_from_acquired_at_when_present() {
            Plant p = plant(90L, user, true);
            p.setArchivedAt(LocalDateTime.of(2026, 1, 11, 0, 0));
            p.setAcquiredAt(LocalDate.of(2026, 1, 1));
            when(plantRepository.findByUserIdAndArchivedAtIsNotNullOrderByArchivedAtDesc(1L)).thenReturn(List.of(p));
            when(careHistoryRepository.countActiveByPlantId(90L)).thenReturn(5L);

            List<PlantService.ArchivedPlant> result = service.listArchivedPlants(1L);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).totalCareDays()).isEqualTo(10);
            assertThat(result.get(0).totalCareEvents()).isEqualTo(5);
        }

        @Test
        @DisplayName("should_fallback_to_created_at_when_acquired_at_missing")
        void should_fallback_to_created_at_when_acquired_at_missing() {
            Plant p = plant(91L, user, true);
            p.setArchivedAt(LocalDateTime.of(2026, 1, 11, 0, 0));
            ReflectionTestUtils.setField(p, "createdAt", LocalDateTime.of(2026, 1, 6, 0, 0));
            when(plantRepository.findByUserIdAndArchivedAtIsNotNullOrderByArchivedAtDesc(1L)).thenReturn(List.of(p));
            when(careHistoryRepository.countActiveByPlantId(91L)).thenReturn(0L);

            List<PlantService.ArchivedPlant> result = service.listArchivedPlants(1L);

            assertThat(result.get(0).totalCareDays()).isEqualTo(5);
        }

        @Test
        @DisplayName("should_return_zero_care_days_when_neither_acquired_nor_created_at_available")
        void should_return_zero_care_days_when_neither_acquired_nor_created_at_available() {
            Plant p = plant(92L, user, true);
            p.setArchivedAt(LocalDateTime.of(2026, 1, 11, 0, 0));
            when(plantRepository.findByUserIdAndArchivedAtIsNotNullOrderByArchivedAtDesc(1L)).thenReturn(List.of(p));
            when(careHistoryRepository.countActiveByPlantId(92L)).thenReturn(0L);

            List<PlantService.ArchivedPlant> result = service.listArchivedPlants(1L);

            assertThat(result.get(0).totalCareDays()).isZero();
        }
    }

    // ===================== postponeSchedule =====================

    @Nested
    @DisplayName("postponeSchedule")
    class PostponeScheduleTests {

        @Test
        @DisplayName("should_throw_when_days_below_minimum")
        void should_throw_when_days_below_minimum() {
            assertThatThrownBy(() -> service.postponeSchedule(1L, 100L, TaskType.WATERING, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("от 1 до 365");
        }

        @Test
        @DisplayName("should_throw_when_days_above_maximum")
        void should_throw_when_days_above_maximum() {
            assertThatThrownBy(() -> service.postponeSchedule(1L, 100L, TaskType.WATERING, 400))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("should_throw_when_plant_not_found")
        void should_throw_when_plant_not_found() {
            when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 101L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.postponeSchedule(1L, 101L, TaskType.WATERING, 5))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("не найдено");
        }

        @Test
        @DisplayName("should_throw_when_schedule_not_configured")
        void should_throw_when_schedule_not_configured() {
            Plant p = plant(102L, user, false);
            when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 102L)).thenReturn(Optional.of(p));
            when(careScheduleRepository.findByPlantIdAndTaskType(102L, TaskType.WATERING)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.postponeSchedule(1L, 102L, TaskType.WATERING, 5))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("не настроено");
        }

        @Test
        @DisplayName("should_shift_next_due_at_by_given_days_from_now")
        void should_shift_next_due_at_by_given_days_from_now() {
            Plant p = plant(103L, user, false);
            CareSchedule schedule = CareSchedule.builder()
                    .plant(p).taskType(TaskType.WATERING).intervalDays(7)
                    .nextDueAt(LocalDateTime.of(2020, 1, 1, 0, 0)).active(true).build();
            when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 103L)).thenReturn(Optional.of(p));
            when(careScheduleRepository.findByPlantIdAndTaskType(103L, TaskType.WATERING)).thenReturn(Optional.of(schedule));
            when(careScheduleRepository.save(any(CareSchedule.class))).thenAnswer(inv -> inv.getArgument(0));
            when(seasonalIntervalService.isSeasonalActive(any(), any())).thenReturn(false);
            when(seasonalIntervalService.effectiveIntervalForSeason(any(), any(), anyInt(), any()))
                    .thenAnswer(inv -> inv.getArgument(2));

            PlantService.ScheduleView view = service.postponeSchedule(1L, 103L, TaskType.WATERING, 3);

            assertThat(view.nextDueAt()).isEqualTo(LocalDateTime.now(clock).plusDays(3));
        }
    }

    // ===================== rescheduleSchedule =====================

    @Nested
    @DisplayName("rescheduleSchedule")
    class RescheduleScheduleTests {

        @Test
        @DisplayName("should_throw_when_next_due_at_null")
        void should_throw_when_next_due_at_null() {
            assertThatThrownBy(() -> service.rescheduleSchedule(1L, 110L, TaskType.WATERING, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("не задан");
        }

        @Test
        @DisplayName("should_throw_when_plant_not_found_on_reschedule")
        void should_throw_when_plant_not_found_on_reschedule() {
            when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 111L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.rescheduleSchedule(1L, 111L, TaskType.WATERING, LocalDateTime.now(clock)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("should_throw_when_schedule_not_configured_on_reschedule")
        void should_throw_when_schedule_not_configured_on_reschedule() {
            Plant p = plant(112L, user, false);
            when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 112L)).thenReturn(Optional.of(p));
            when(careScheduleRepository.findByPlantIdAndTaskType(112L, TaskType.WATERING)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.rescheduleSchedule(1L, 112L, TaskType.WATERING, LocalDateTime.now(clock)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ===================== toggleSchedule =====================

    @Nested
    @DisplayName("toggleSchedule")
    class ToggleScheduleTests {

        @Test
        @DisplayName("should_not_reschedule_when_reactivated_schedule_next_due_at_is_in_future")
        void should_not_reschedule_when_reactivated_schedule_next_due_at_is_in_future() {
            Plant p = plant(120L, user, false);
            LocalDateTime future = LocalDateTime.now(clock).plusDays(10);
            CareSchedule existing = CareSchedule.builder()
                    .plant(p).taskType(TaskType.WATERING).intervalDays(7)
                    .nextDueAt(future).active(false).build();
            when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 120L)).thenReturn(Optional.of(p));
            when(careScheduleRepository.findByPlantIdAndTaskType(120L, TaskType.WATERING)).thenReturn(Optional.of(existing));
            when(careScheduleRepository.save(any(CareSchedule.class))).thenAnswer(inv -> inv.getArgument(0));

            CareSchedule result = service.toggleSchedule(1L, 120L, TaskType.WATERING);

            assertThat(result.isActive()).isTrue();
            assertThat(result.getNextDueAt()).isEqualTo(future);
            verify(seasonalIntervalService, never()).effectiveIntervalDays(any(), any(), anyInt());
        }

        @Test
        @DisplayName("should_deactivate_when_toggling_active_schedule")
        void should_deactivate_when_toggling_active_schedule() {
            Plant p = plant(121L, user, false);
            CareSchedule existing = CareSchedule.builder()
                    .plant(p).taskType(TaskType.WATERING).intervalDays(7)
                    .nextDueAt(LocalDateTime.now(clock).plusDays(1)).active(true).build();
            when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 121L)).thenReturn(Optional.of(p));
            when(careScheduleRepository.findByPlantIdAndTaskType(121L, TaskType.WATERING)).thenReturn(Optional.of(existing));
            when(careScheduleRepository.save(any(CareSchedule.class))).thenAnswer(inv -> inv.getArgument(0));

            CareSchedule result = service.toggleSchedule(1L, 121L, TaskType.WATERING);

            assertThat(result.isActive()).isFalse();
        }

        @Test
        @DisplayName("should_throw_when_plant_not_found_on_toggle")
        void should_throw_when_plant_not_found_on_toggle() {
            when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 122L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.toggleSchedule(1L, 122L, TaskType.WATERING))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ===================== defaultIntervalFor (через createPlantWithDefaultSchedules) =====================

    @Test
    @DisplayName("should_fallback_to_hardcoded_interval_when_species_interval_invalid")
    void should_fallback_to_hardcoded_interval_when_species_interval_invalid() {
        Species species = Species.builder().wateringDays(0).mistingDays(500).fertilizingDays(14).soilCheckDays(3).build();
        when(locationService.getOrCreateDefaultLocation(user)).thenReturn(location);
        when(speciesRepository.findById(1L)).thenReturn(Optional.of(species));
        when(plantRepository.save(any(Plant.class))).thenAnswer(inv -> {
            Plant p = inv.getArgument(0);
            ReflectionTestUtils.setField(p, "id", 200L);
            return p;
        });
        when(careScheduleRepository.save(any(CareSchedule.class))).thenAnswer(inv -> inv.getArgument(0));
        when(seasonalIntervalService.effectiveIntervalDays(any(), any(), anyInt()))
                .thenAnswer(inv -> inv.getArgument(2));

        Plant result = service.createPlantWithDefaultSchedules(user, "Растение", null, null, 1L);

        assertThat(result.getId()).isEqualTo(200L);
        ArgumentCaptor<CareSchedule> captor = ArgumentCaptor.forClass(CareSchedule.class);
        verify(careScheduleRepository, times(4)).save(captor.capture());
        CareSchedule watering = captor.getAllValues().stream()
                .filter(s -> s.getTaskType() == TaskType.WATERING).findFirst().orElseThrow();
        assertThat(watering.getIntervalDays()).isEqualTo(7); // species.wateringDays=0 -> невалидный -> fallback

        CareSchedule misting = captor.getAllValues().stream()
                .filter(s -> s.getTaskType() == TaskType.MISTING).findFirst().orElseThrow();
        assertThat(misting.getIntervalDays()).isEqualTo(3); // species.mistingDays=500 -> невалидный -> fallback
    }

    // ===================== markCareDone: timezone-critical wasOnTime edge =====================

    @Test
    @DisplayName("should_mark_late_when_done_after_grace_period_in_almaty_timezone")
    void should_mark_late_when_done_after_grace_period_in_almaty_timezone() {
        User almatyUser = User.builder().telegramChatId(3L).timezone("Asia/Almaty").build();
        ReflectionTestUtils.setField(almatyUser, "id", 3L);
        Plant p = plant(300L, almatyUser, false);
        CareSchedule schedule = CareSchedule.builder()
                .plant(p).taskType(TaskType.WATERING).intervalDays(7)
                .nextDueAt(LocalDateTime.now(clock).minusHours(30)) // за пределами 24ч grace period
                .active(true).build();
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(3L, 300L)).thenReturn(Optional.of(p));
        when(careScheduleRepository.findByPlantIdAndTaskType(300L, TaskType.WATERING)).thenReturn(Optional.of(schedule));
        when(careHistoryRepository.findFirstByPlantIdAndTaskTypeOrderByDoneAtDesc(300L, TaskType.WATERING))
                .thenReturn(Optional.empty());
        when(careHistoryRepository.save(any(CareHistory.class))).thenAnswer(inv -> inv.getArgument(0));
        when(careScheduleRepository.save(any(CareSchedule.class))).thenAnswer(inv -> inv.getArgument(0));

        PlantService.MarkCareDoneResult result = service.markCareDone(3L, 300L, TaskType.WATERING);

        assertThat(result.wasDuplicate()).isFalse();
        assertThat(result.history().isOnTime()).isFalse();
    }
}
