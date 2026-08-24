package com.plantcare.core.service;

import com.plantcare.core.domain.CareHistory;
import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.repository.CareHistoryRepository;
import com.plantcare.core.repository.LocationRepository;
import com.plantcare.core.repository.PlantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-тесты для SyncService")
class SyncServiceTest {

    @Mock
    private PlantRepository plantRepository;

    @Mock
    private LocationRepository locationRepository;

    @Mock
    private CareHistoryRepository careHistoryRepository;

    @InjectMocks
    private SyncService syncService;

    private static final Long USER_ID = 99L;

    @BeforeEach
    void stubDefaults() {
        lenient().when(plantRepository.findChangedSince(anyLong(), any())).thenReturn(List.of());
        lenient().when(locationRepository.findChangedSince(anyLong(), any())).thenReturn(List.of());
        lenient().when(careHistoryRepository.findChangedSince(anyLong(), any())).thenReturn(List.of());
        lenient().when(plantRepository.findDeletedSince(anyLong(), any())).thenReturn(List.of());
        lenient().when(locationRepository.findDeletedSince(anyLong(), any())).thenReturn(List.of());
    }

    // ═══════════════ sync() — sinceTs conversion ═══════════════

    @Nested
    @DisplayName("sync — sinceTs UTC conversion")
    class SinceTsConversionTests {

        @Test
        @DisplayName("should_convert_sinceTs_to_UTC_LocalDateTime_before_passing_to_repositories")
        void should_convert_sinceTs_to_UTC_LocalDateTime_before_passing_to_repositories() {
            // arrange
            Instant sinceTs = Instant.parse("2026-08-16T06:00:00Z");
            LocalDateTime expectedSince = LocalDateTime.of(2026, 8, 16, 6, 0, 0);

            // act
            syncService.sync(USER_ID, sinceTs);

            // assert: all repos receive the exact UTC LocalDateTime derived from sinceTs
            ArgumentCaptor<LocalDateTime> sinceCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(plantRepository).findChangedSince(eq(USER_ID), sinceCaptor.capture());
            assertThat(sinceCaptor.getValue()).isEqualTo(expectedSince);
        }

        @Test
        @DisplayName("should_pass_same_since_value_to_all_three_repositories")
        void should_pass_same_since_value_to_all_three_repositories() {
            Instant sinceTs = Instant.parse("2026-08-16T06:30:00Z");
            LocalDateTime expectedSince = LocalDateTime.ofInstant(sinceTs, ZoneOffset.UTC);

            syncService.sync(USER_ID, sinceTs);

            ArgumentCaptor<LocalDateTime> plantCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
            ArgumentCaptor<LocalDateTime> locationCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
            ArgumentCaptor<LocalDateTime> historyCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(plantRepository).findChangedSince(eq(USER_ID), plantCaptor.capture());
            verify(locationRepository).findChangedSince(eq(USER_ID), locationCaptor.capture());
            verify(careHistoryRepository).findChangedSince(eq(USER_ID), historyCaptor.capture());

            assertThat(plantCaptor.getValue()).isEqualTo(expectedSince);
            assertThat(locationCaptor.getValue()).isEqualTo(expectedSince);
            assertThat(historyCaptor.getValue()).isEqualTo(expectedSince);
        }
    }

    // ═══════════════ sync() — result packaging ═══════════════

    @Nested
    @DisplayName("sync — result packaging")
    class ResultPackagingTests {

        @Test
        @DisplayName("should_return_empty_sync_result_when_no_changes_exist")
        void should_return_empty_sync_result_when_no_changes_exist() {
            SyncService.SyncResult result = syncService.sync(USER_ID, Instant.now());

            assertThat(result.plants()).isEmpty();
            assertThat(result.locations()).isEmpty();
            assertThat(result.careEvents()).isEmpty();
            assertThat(result.deletions()).isEmpty();
        }

        @Test
        @DisplayName("should_include_plants_and_locations_and_events_from_repositories_in_result")
        void should_include_plants_and_locations_and_events_from_repositories_in_result() {
            Plant plant = mock(Plant.class);
            Location location = mock(Location.class);
            CareHistory careEvent = mock(CareHistory.class);
            when(plantRepository.findChangedSince(anyLong(), any())).thenReturn(List.of(plant));
            when(locationRepository.findChangedSince(anyLong(), any())).thenReturn(List.of(location));
            when(careHistoryRepository.findChangedSince(anyLong(), any())).thenReturn(List.of(careEvent));

            SyncService.SyncResult result = syncService.sync(USER_ID, Instant.now());

            assertThat(result.plants()).containsExactly(plant);
            assertThat(result.locations()).containsExactly(location);
            assertThat(result.careEvents()).containsExactly(careEvent);
        }

        @Test
        @DisplayName("should_return_non_null_serverTime_in_result")
        void should_return_non_null_serverTime_in_result() {
            SyncService.SyncResult result = syncService.sync(USER_ID, Instant.now());

            // NOTE: serverTime is Instant.now() inside sync(), not injected via Clock.
            // This makes it impossible to assert the exact value in a unit test.
            // Tracked as a production code testability issue: SyncService.sync() should
            // accept an injected Clock bean instead of calling Instant.now() directly.
            assertThat(result.serverTime()).isNotNull();
        }

        @Test
        @DisplayName("should_pass_correct_userId_to_all_repositories")
        void should_pass_correct_userId_to_all_repositories() {
            long specificUserId = 777L;

            syncService.sync(specificUserId, Instant.now());

            verify(plantRepository).findChangedSince(eq(specificUserId), any());
            verify(locationRepository).findChangedSince(eq(specificUserId), any());
            verify(careHistoryRepository).findChangedSince(eq(specificUserId), any());
        }
    }

    // ═══════════════ buildDeletions (via sync) ═══════════════

    @Nested
    @DisplayName("buildDeletions — entity type tagging and sort order")
    class BuildDeletionsTests {

        @Test
        @DisplayName("should_return_empty_deletions_when_neither_plants_nor_locations_deleted")
        void should_return_empty_deletions_when_neither_plants_nor_locations_deleted() {
            SyncService.SyncResult result = syncService.sync(USER_ID, Instant.now());

            assertThat(result.deletions()).isEmpty();
        }

        @Test
        @DisplayName("should_tag_plant_deletions_with_entityType_plant")
        void should_tag_plant_deletions_with_entityType_plant() {
            PlantRepository.DeletedRecord rec = mockDeletedPlant(1L, LocalDateTime.of(2026, 8, 10, 12, 0));
            when(plantRepository.findDeletedSince(anyLong(), any())).thenReturn(List.of(rec));

            SyncService.SyncResult result = syncService.sync(USER_ID, Instant.now());

            assertThat(result.deletions()).hasSize(1);
            assertThat(result.deletions().get(0).entityType()).isEqualTo("plant");
            assertThat(result.deletions().get(0).id()).isEqualTo(1L);
        }

        @Test
        @DisplayName("should_tag_location_deletions_with_entityType_location")
        void should_tag_location_deletions_with_entityType_location() {
            LocationRepository.DeletedRecord rec = mockDeletedLocation(10L, LocalDateTime.of(2026, 8, 11, 8, 0));
            when(locationRepository.findDeletedSince(anyLong(), any())).thenReturn(List.of(rec));

            SyncService.SyncResult result = syncService.sync(USER_ID, Instant.now());

            assertThat(result.deletions()).hasSize(1);
            assertThat(result.deletions().get(0).entityType()).isEqualTo("location");
            assertThat(result.deletions().get(0).id()).isEqualTo(10L);
        }

        @Test
        @DisplayName("should_sort_mixed_plant_and_location_deletions_by_deletedAt_ascending")
        void should_sort_mixed_plant_and_location_deletions_by_deletedAt_ascending() {
            // arrange: interleaved timestamps that require sorting
            LocalDateTime t1 = LocalDateTime.of(2026, 8, 10, 8, 0);
            LocalDateTime t2 = LocalDateTime.of(2026, 8, 10, 10, 0);
            LocalDateTime t3 = LocalDateTime.of(2026, 8, 10, 12, 0);
            LocalDateTime t4 = LocalDateTime.of(2026, 8, 10, 14, 0);

            // Pre-create mocks before outer when() calls to avoid UnfinishedStubbing
            PlantRepository.DeletedRecord p1 = mockDeletedPlant(1L, t1);
            PlantRepository.DeletedRecord p3 = mockDeletedPlant(3L, t3);
            LocationRepository.DeletedRecord l20 = mockDeletedLocation(20L, t2);
            LocationRepository.DeletedRecord l40 = mockDeletedLocation(40L, t4);

            // Plants at t1 and t3; locations at t2 and t4 — purposely not pre-sorted
            when(plantRepository.findDeletedSince(anyLong(), any())).thenReturn(List.of(p1, p3));
            when(locationRepository.findDeletedSince(anyLong(), any())).thenReturn(List.of(l20, l40));

            // act
            SyncService.SyncResult result = syncService.sync(USER_ID, Instant.now());

            // assert: merged list sorted by deletedAt ascending
            List<SyncService.DeletionDto> deletions = result.deletions();
            assertThat(deletions).hasSize(4);
            assertThat(deletions.get(0).deletedAt()).isEqualTo(t1.toInstant(ZoneOffset.UTC));
            assertThat(deletions.get(1).deletedAt()).isEqualTo(t2.toInstant(ZoneOffset.UTC));
            assertThat(deletions.get(2).deletedAt()).isEqualTo(t3.toInstant(ZoneOffset.UTC));
            assertThat(deletions.get(3).deletedAt()).isEqualTo(t4.toInstant(ZoneOffset.UTC));
        }

        @Test
        @DisplayName("should_sort_correctly_when_all_deletions_are_plants_only")
        void should_sort_correctly_when_all_deletions_are_plants_only() {
            LocalDateTime t1 = LocalDateTime.of(2026, 8, 5, 6, 0);
            LocalDateTime t2 = LocalDateTime.of(2026, 8, 5, 12, 0);
            LocalDateTime t3 = LocalDateTime.of(2026, 8, 6, 9, 0);

            // Pre-create mocks before outer when() to avoid UnfinishedStubbing
            PlantRepository.DeletedRecord rT3 = mockDeletedPlant(3L, t3);
            PlantRepository.DeletedRecord rT1 = mockDeletedPlant(1L, t1);
            PlantRepository.DeletedRecord rT2 = mockDeletedPlant(2L, t2);

            // Return in reverse order to verify sort is not just trusting input order
            when(plantRepository.findDeletedSince(anyLong(), any())).thenReturn(List.of(rT3, rT1, rT2));

            SyncService.SyncResult result = syncService.sync(USER_ID, Instant.now());

            List<SyncService.DeletionDto> deletions = result.deletions();
            assertThat(deletions).hasSize(3);
            assertThat(deletions.get(0).id()).isEqualTo(1L);
            assertThat(deletions.get(1).id()).isEqualTo(2L);
            assertThat(deletions.get(2).id()).isEqualTo(3L);
        }

        @Test
        @DisplayName("should_convert_deletedAt_to_UTC_Instant_in_DeletionDto")
        void should_convert_deletedAt_to_UTC_Instant_in_DeletionDto() {
            LocalDateTime deletedAt = LocalDateTime.of(2026, 8, 16, 10, 30, 0);
            PlantRepository.DeletedRecord rec = mockDeletedPlant(5L, deletedAt);
            when(plantRepository.findDeletedSince(anyLong(), any())).thenReturn(List.of(rec));

            SyncService.SyncResult result = syncService.sync(USER_ID, Instant.now());

            Instant expectedInstant = deletedAt.toInstant(ZoneOffset.UTC);
            assertThat(result.deletions().get(0).deletedAt()).isEqualTo(expectedInstant);
        }
    }

    // ===== helpers =====

    private PlantRepository.DeletedRecord mockDeletedPlant(Long id, LocalDateTime deletedAt) {
        PlantRepository.DeletedRecord rec = mock(PlantRepository.DeletedRecord.class);
        when(rec.getId()).thenReturn(id);
        when(rec.getDeletedAt()).thenReturn(deletedAt);
        return rec;
    }

    private LocationRepository.DeletedRecord mockDeletedLocation(Long id, LocalDateTime deletedAt) {
        LocationRepository.DeletedRecord rec = mock(LocationRepository.DeletedRecord.class);
        when(rec.getId()).thenReturn(id);
        when(rec.getDeletedAt()).thenReturn(deletedAt);
        return rec;
    }
}
