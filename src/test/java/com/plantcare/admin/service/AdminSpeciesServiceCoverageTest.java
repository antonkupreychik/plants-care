package com.plantcare.admin.service;

import com.plantcare.admin.dto.SpeciesFormDto;
import com.plantcare.admin.dto.SpeciesListItem;
import com.plantcare.admin.audit.service.AdminAuditService;
import com.plantcare.admin.exception.DuplicateSpeciesNameException;
import com.plantcare.core.domain.Species;
import com.plantcare.core.domain.enums.CareDifficulty;
import com.plantcare.core.domain.enums.LightPreference;
import com.plantcare.core.repository.PlantRepository;
import com.plantcare.core.repository.SpeciesRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты {@link AdminSpeciesService}. Раньше единственный тест (AdminSpeciesServiceTest)
 * был полностью закомментирован (миграция на Spring Boot 4.1 / Testcontainers-версия не
 * компилировалась). Этот класс — чистый Mockito-юнит без контекста Spring: репозитории
 * и аудит-сервис мокаются, что покрывает все ветки patchField/parseDayNumber/parsePopularity/
 * parseEnum/validateUniqueName без поднятия БД.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminSpeciesServiceCoverageTest {

    private static final String ADMIN = "admin";

    @Mock
    private SpeciesRepository speciesRepository;

    @Mock
    private PlantRepository plantRepository;

    @Mock
    private AdminAuditService auditService;

    private AdminSpeciesService service;

    @BeforeEach
    void setUp() {
        service = new AdminSpeciesService(speciesRepository, plantRepository, auditService);
    }

    private Species speciesWithId(Long id, String name) {
        Species s = new Species();
        s.setName(name);
        s.setPopularity(50);
        ReflectionTestUtils.setField(s, "id", id);
        return s;
    }

    private SpeciesFormDto buildDto(String name) {
        SpeciesFormDto dto = new SpeciesFormDto();
        dto.setName(name);
        dto.setPopularity(50);
        dto.setLightPreference(LightPreference.BRIGHT);
        dto.setCareDifficulty(CareDifficulty.EASY);
        return dto;
    }

    // ============================================================ findAllWithLinkedCounts

    @Nested
    class FindAllWithLinkedCounts {

        @Test
        void should_use_search_query_when_query_has_text() {
            // arrange
            when(speciesRepository.findBySearch(any(), any())).thenReturn(new PageImpl<>(List.of()));

            // act
            Page<SpeciesListItem> result = service.findAllWithLinkedCounts("monstera", 0, "popularity", "desc");

            // assert
            assertThat(result.getContent()).isEmpty();
            verify(speciesRepository).findBySearch(any(), any());
            verify(speciesRepository, never()).findAll(any(PageRequest.class));
        }

        @Test
        void should_use_findAll_when_query_is_blank() {
            // arrange
            when(speciesRepository.findAll(any(PageRequest.class))).thenReturn(new PageImpl<>(List.of()));

            // act
            Page<SpeciesListItem> result = service.findAllWithLinkedCounts("", 0, "popularity", "desc");

            // assert
            assertThat(result.getContent()).isEmpty();
            verify(speciesRepository).findAll(any(PageRequest.class));
        }

        @Test
        void should_return_empty_page_when_species_page_is_empty() {
            // arrange
            when(speciesRepository.findAll(any(PageRequest.class))).thenReturn(new PageImpl<>(List.of()));

            // act
            Page<SpeciesListItem> result = service.findAllWithLinkedCounts("", 0, "popularity", "desc");

            // assert — early-return branch, plantRepository never consulted
            assertThat(result.getTotalElements()).isZero();
            verify(plantRepository, never()).countBySpeciesIdIn(any());
        }

        @Test
        void should_attach_linked_plant_counts_when_species_found() {
            // arrange
            Species sp = speciesWithId(1L, "Монстера");
            when(speciesRepository.findAll(any(PageRequest.class)))
                    .thenReturn(new PageImpl<>(List.of(sp)));
            PlantRepository.SpeciesPlantCount count = mock(PlantRepository.SpeciesPlantCount.class);
            when(count.getSpeciesId()).thenReturn(1L);
            when(count.getPlantCount()).thenReturn(3L);
            when(plantRepository.countBySpeciesIdIn(any())).thenReturn(List.of(count));

            // act
            Page<SpeciesListItem> result = service.findAllWithLinkedCounts("", 0, "popularity", "desc");

            // assert
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).linkedPlantsCount()).isEqualTo(3L);
        }

        @Test
        void should_default_to_zero_linked_plants_when_no_count_row() {
            // arrange
            Species sp = speciesWithId(2L, "Без растений");
            when(speciesRepository.findAll(any(PageRequest.class)))
                    .thenReturn(new PageImpl<>(List.of(sp)));
            when(plantRepository.countBySpeciesIdIn(any())).thenReturn(List.of());

            // act
            Page<SpeciesListItem> result = service.findAllWithLinkedCounts("", 0, "popularity", "desc");

            // assert
            assertThat(result.getContent().get(0).linkedPlantsCount()).isZero();
        }

        @Test
        void should_fallback_to_default_sort_when_field_not_whitelisted() {
            // arrange
            when(speciesRepository.findAll(any(PageRequest.class))).thenReturn(new PageImpl<>(List.of()));

            // act
            service.findAllWithLinkedCounts("", 0, "arbitraryField", "desc");

            // assert — captured pageable must fall back to "popularity"
            var captor = org.mockito.ArgumentCaptor.forClass(PageRequest.class);
            verify(speciesRepository).findAll(captor.capture());
            assertThat(captor.getValue().getSort().getOrderFor("popularity")).isNotNull();
        }

        @Test
        void should_sort_ascending_when_dir_is_asc() {
            // arrange
            when(speciesRepository.findAll(any(PageRequest.class))).thenReturn(new PageImpl<>(List.of()));

            // act
            service.findAllWithLinkedCounts("", 0, "name", "asc");

            // assert
            var captor = org.mockito.ArgumentCaptor.forClass(PageRequest.class);
            verify(speciesRepository).findAll(captor.capture());
            assertThat(captor.getValue().getSort().getOrderFor("name").isAscending()).isTrue();
        }

        @Test
        void should_clamp_negative_page_to_zero() {
            // arrange
            when(speciesRepository.findAll(any(PageRequest.class))).thenReturn(new PageImpl<>(List.of()));

            // act
            service.findAllWithLinkedCounts("", -5, "popularity", "desc");

            // assert
            var captor = org.mockito.ArgumentCaptor.forClass(PageRequest.class);
            verify(speciesRepository).findAll(captor.capture());
            assertThat(captor.getValue().getPageNumber()).isZero();
        }
    }

    // ============================================================ findById

    @Nested
    class FindById {

        @Test
        void should_return_species_when_found() {
            // arrange
            Species sp = speciesWithId(5L, "Алоэ");
            when(speciesRepository.findById(5L)).thenReturn(Optional.of(sp));

            // act
            Species result = service.findById(5L);

            // assert
            assertThat(result.getName()).isEqualTo("Алоэ");
        }

        @Test
        void should_throw_entity_not_found_when_missing() {
            // arrange
            when(speciesRepository.findById(999L)).thenReturn(Optional.empty());

            // act + assert
            assertThatThrownBy(() -> service.findById(999L))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("999");
        }
    }

    // ============================================================ create / update

    @Nested
    class CreateAndUpdate {

        @Test
        void should_persist_species_when_name_is_unique() {
            // arrange
            when(speciesRepository.findByNameIgnoreCase("Уникальный")).thenReturn(Optional.empty());
            when(speciesRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // act
            Species created = service.create(buildDto("Уникальный"), ADMIN);

            // assert
            assertThat(created.getName()).isEqualTo("Уникальный");
            verify(auditService).log(any(), eq(ADMIN), any(), any(), any());
        }

        @Test
        void should_throw_duplicate_when_creating_with_existing_name() {
            // arrange
            Species existing = speciesWithId(1L, "Дубликат");
            when(speciesRepository.findByNameIgnoreCase("Дубликат")).thenReturn(Optional.of(existing));

            // act + assert
            assertThatThrownBy(() -> service.create(buildDto("Дубликат"), ADMIN))
                    .isInstanceOf(DuplicateSpeciesNameException.class);
            verify(speciesRepository, never()).save(any());
        }

        @Test
        void should_update_species_when_name_unique_or_same_entity() {
            // arrange
            Species existing = speciesWithId(1L, "Старое имя");
            when(speciesRepository.findById(1L)).thenReturn(Optional.of(existing));
            when(speciesRepository.findByNameIgnoreCase("Старое имя")).thenReturn(Optional.of(existing));
            when(speciesRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // act
            Species updated = service.update(1L, buildDto("Старое имя"), ADMIN);

            // assert
            assertThat(updated.getName()).isEqualTo("Старое имя");
        }

        @Test
        void should_throw_duplicate_when_updating_to_another_species_name() {
            // arrange
            Species other = speciesWithId(2L, "Занятое имя");
            when(speciesRepository.findByNameIgnoreCase("Занятое имя")).thenReturn(Optional.of(other));

            // act + assert
            assertThatThrownBy(() -> service.update(1L, buildDto("Занятое имя"), ADMIN))
                    .isInstanceOf(DuplicateSpeciesNameException.class);
            verify(speciesRepository, never()).save(any());
        }
    }

    // ============================================================ patchField

    @Nested
    class PatchField {

        @Test
        void should_patch_name_when_valid() {
            // arrange
            Species sp = speciesWithId(1L, "Старое");
            when(speciesRepository.findById(1L)).thenReturn(Optional.of(sp));
            when(speciesRepository.findByNameIgnoreCase("Новое")).thenReturn(Optional.empty());
            when(speciesRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // act
            Species result = service.patchField(1L, "name", "Новое", ADMIN);

            // assert
            assertThat(result.getName()).isEqualTo("Новое");
        }

        @Test
        void should_throw_when_patched_name_is_blank() {
            // arrange
            Species sp = speciesWithId(1L, "Старое");
            when(speciesRepository.findById(1L)).thenReturn(Optional.of(sp));

            // act + assert
            assertThatThrownBy(() -> service.patchField(1L, "name", "   ", ADMIN))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("не может быть пустым");
        }

        @Test
        void should_throw_when_patched_name_too_long() {
            // arrange
            Species sp = speciesWithId(1L, "Старое");
            when(speciesRepository.findById(1L)).thenReturn(Optional.of(sp));
            String longName = "a".repeat(101);

            // act + assert
            assertThatThrownBy(() -> service.patchField(1L, "name", longName, ADMIN))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("100 символов");
        }

        @Test
        void should_throw_when_patched_name_duplicates_another_species() {
            // arrange
            Species sp = speciesWithId(1L, "Старое");
            Species other = speciesWithId(2L, "Занято");
            when(speciesRepository.findById(1L)).thenReturn(Optional.of(sp));
            when(speciesRepository.findByNameIgnoreCase("Занято")).thenReturn(Optional.of(other));

            // act + assert
            assertThatThrownBy(() -> service.patchField(1L, "name", "Занято", ADMIN))
                    .isInstanceOf(DuplicateSpeciesNameException.class);
        }

        @Test
        void should_patch_latin_name_when_within_length() {
            // arrange
            Species sp = speciesWithId(1L, "Тест");
            when(speciesRepository.findById(1L)).thenReturn(Optional.of(sp));
            when(speciesRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // act
            Species result = service.patchField(1L, "latinName", "Testus latinus", ADMIN);

            // assert
            assertThat(result.getLatinName()).isEqualTo("Testus latinus");
        }

        @Test
        void should_throw_when_latin_name_too_long() {
            // arrange
            Species sp = speciesWithId(1L, "Тест");
            when(speciesRepository.findById(1L)).thenReturn(Optional.of(sp));
            String longLatin = "a".repeat(151);

            // act + assert
            assertThatThrownBy(() -> service.patchField(1L, "latinName", longLatin, ADMIN))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("150 символов");
        }

        @Test
        void should_patch_watering_days_when_in_range() {
            // arrange
            Species sp = speciesWithId(1L, "Тест");
            when(speciesRepository.findById(1L)).thenReturn(Optional.of(sp));
            when(speciesRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // act
            Species result = service.patchField(1L, "wateringDays", "7", ADMIN);

            // assert
            assertThat(result.getWateringDays()).isEqualTo(7);
        }

        @Test
        void should_clear_watering_days_when_value_blank() {
            // arrange
            Species sp = speciesWithId(1L, "Тест");
            sp.setWateringDays(7);
            when(speciesRepository.findById(1L)).thenReturn(Optional.of(sp));
            when(speciesRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // act
            Species result = service.patchField(1L, "wateringDays", "", ADMIN);

            // assert
            assertThat(result.getWateringDays()).isNull();
        }

        @Test
        void should_throw_when_watering_days_not_a_number() {
            // arrange
            Species sp = speciesWithId(1L, "Тест");
            when(speciesRepository.findById(1L)).thenReturn(Optional.of(sp));

            // act + assert
            assertThatThrownBy(() -> service.patchField(1L, "wateringDays", "abc", ADMIN))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("числом");
        }

        @Test
        void should_throw_when_watering_days_out_of_range() {
            // arrange
            Species sp = speciesWithId(1L, "Тест");
            when(speciesRepository.findById(1L)).thenReturn(Optional.of(sp));

            // act + assert
            assertThatThrownBy(() -> service.patchField(1L, "wateringDays", "9999", ADMIN))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("от 1 до 365");
        }

        @Test
        void should_patch_misting_days_when_in_range() {
            // arrange
            Species sp = speciesWithId(1L, "Тест");
            when(speciesRepository.findById(1L)).thenReturn(Optional.of(sp));
            when(speciesRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // act
            Species result = service.patchField(1L, "mistingDays", "3", ADMIN);

            // assert
            assertThat(result.getMistingDays()).isEqualTo(3);
        }

        @Test
        void should_patch_fertilizing_days_when_in_range() {
            // arrange
            Species sp = speciesWithId(1L, "Тест");
            when(speciesRepository.findById(1L)).thenReturn(Optional.of(sp));
            when(speciesRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // act
            Species result = service.patchField(1L, "fertilizingDays", "30", ADMIN);

            // assert
            assertThat(result.getFertilizingDays()).isEqualTo(30);
        }

        @Test
        void should_default_popularity_to_50_when_blank() {
            // arrange
            Species sp = speciesWithId(1L, "Тест");
            when(speciesRepository.findById(1L)).thenReturn(Optional.of(sp));
            when(speciesRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // act
            Species result = service.patchField(1L, "popularity", "", ADMIN);

            // assert
            assertThat(result.getPopularity()).isEqualTo(50);
        }

        @Test
        void should_throw_when_popularity_not_a_number() {
            // arrange
            Species sp = speciesWithId(1L, "Тест");
            when(speciesRepository.findById(1L)).thenReturn(Optional.of(sp));

            // act + assert
            assertThatThrownBy(() -> service.patchField(1L, "popularity", "xx", ADMIN))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("числом");
        }

        @Test
        void should_throw_when_popularity_out_of_range() {
            // arrange
            Species sp = speciesWithId(1L, "Тест");
            when(speciesRepository.findById(1L)).thenReturn(Optional.of(sp));

            // act + assert
            assertThatThrownBy(() -> service.patchField(1L, "popularity", "-1", ADMIN))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("от 0 до 100");
        }

        @Test
        void should_patch_light_preference_enum_when_valid() {
            // arrange
            Species sp = speciesWithId(1L, "Тест");
            when(speciesRepository.findById(1L)).thenReturn(Optional.of(sp));
            when(speciesRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // act
            Species result = service.patchField(1L, "lightPreference", "DIRECT", ADMIN);

            // assert
            assertThat(result.getLightPreference()).isEqualTo(LightPreference.DIRECT);
        }

        @Test
        void should_clear_light_preference_when_value_blank() {
            // arrange
            Species sp = speciesWithId(1L, "Тест");
            sp.setLightPreference(LightPreference.SHADE);
            when(speciesRepository.findById(1L)).thenReturn(Optional.of(sp));
            when(speciesRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // act
            Species result = service.patchField(1L, "lightPreference", "", ADMIN);

            // assert
            assertThat(result.getLightPreference()).isNull();
        }

        @Test
        void should_throw_when_light_preference_value_invalid() {
            // arrange
            Species sp = speciesWithId(1L, "Тест");
            when(speciesRepository.findById(1L)).thenReturn(Optional.of(sp));

            // act + assert
            assertThatThrownBy(() -> service.patchField(1L, "lightPreference", "NOT_A_VALUE", ADMIN))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Недопустимое значение");
        }

        @Test
        void should_patch_care_difficulty_enum_when_valid() {
            // arrange
            Species sp = speciesWithId(1L, "Тест");
            when(speciesRepository.findById(1L)).thenReturn(Optional.of(sp));
            when(speciesRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // act
            Species result = service.patchField(1L, "careDifficulty", "HARD", ADMIN);

            // assert
            assertThat(result.getCareDifficulty()).isEqualTo(CareDifficulty.HARD);
        }

        @Test
        void should_throw_when_field_unknown() {
            // arrange
            Species sp = speciesWithId(1L, "Тест");
            when(speciesRepository.findById(1L)).thenReturn(Optional.of(sp));

            // act + assert
            assertThatThrownBy(() -> service.patchField(1L, "fakeField", "x", ADMIN))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Неизвестное поле");
        }
    }

    // ============================================================ delete

    @Nested
    class Delete {

        @Test
        void should_delete_species_and_log_audit() {
            // arrange
            Species sp = speciesWithId(7L, "Удаляемый");
            when(speciesRepository.findById(7L)).thenReturn(Optional.of(sp));
            when(plantRepository.countBySpeciesId(7L)).thenReturn(2L);

            // act
            service.delete(7L, ADMIN);

            // assert
            verify(speciesRepository, times(1)).delete(sp);
            verify(auditService).log(any(), eq(ADMIN), any(), eq(7L), any());
        }
    }

    // ============================================================ countLinkedPlants

    @Test
    void should_delegate_count_linked_plants_to_repository() {
        // arrange
        when(plantRepository.countBySpeciesId(3L)).thenReturn(9L);

        // act
        long result = service.countLinkedPlants(3L);

        // assert
        assertThat(result).isEqualTo(9L);
        verify(plantRepository).countBySpeciesId(3L);
    }
}
