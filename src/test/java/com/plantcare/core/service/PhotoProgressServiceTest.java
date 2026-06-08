package com.plantcare.core.service;

import com.plantcare.core.domain.Photo;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.PlantProgressPhoto;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.PhotoProgressFrequency;
import com.plantcare.core.repository.PlantProgressPhotoRepository;
import com.plantcare.core.repository.PlantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Unit-тесты PhotoProgressService (issue #72)")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PhotoProgressServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long PLANT_ID = 42L;
    private static final String FILE_ID = "AgACAgI...";

    @Mock
    private PlantRepository plantRepository;

    @Mock
    private PlantProgressPhotoRepository photoRepository;

    @InjectMocks
    private PhotoProgressService service;

    private User user;
    private Plant plant;

    @BeforeEach
    void setUp() {
        user = User.builder().telegramChatId(12345L).build();
        ReflectionTestUtils.setField(user, "id", USER_ID);

        plant = Plant.builder()
                .user(user)
                .name("Монстера")
                .photoProgressFrequency(PhotoProgressFrequency.OFF)
                .build();
        ReflectionTestUtils.setField(plant, "id", PLANT_ID);

        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(USER_ID, PLANT_ID))
                .thenReturn(Optional.of(plant));
        when(plantRepository.save(any(Plant.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void should_set_frequency_and_next_due_at_when_enabling_p2w() {
        LocalDateTime before = LocalDateTime.now();

        Plant result = service.setFrequency(USER_ID, PLANT_ID, PhotoProgressFrequency.P2W);

        assertThat(result.getPhotoProgressFrequency()).isEqualTo(PhotoProgressFrequency.P2W);
        assertThat(result.getNextPhotoDueAt())
                .isAfterOrEqualTo(before.plusWeeks(2).minusSeconds(5))
                .isBeforeOrEqualTo(LocalDateTime.now().plusWeeks(2).plusSeconds(5));
        assertThat(result.getLastPhotoPromptSentAt()).isNull();
    }

    @Test
    void should_clear_next_due_at_when_setting_off() {
        plant.setPhotoProgressFrequency(PhotoProgressFrequency.P1M);
        plant.setNextPhotoDueAt(LocalDateTime.now().plusMonths(1));

        Plant result = service.setFrequency(USER_ID, PLANT_ID, PhotoProgressFrequency.OFF);

        assertThat(result.getPhotoProgressFrequency()).isEqualTo(PhotoProgressFrequency.OFF);
        assertThat(result.getNextPhotoDueAt()).isNull();
    }

    @Test
    void should_throw_when_plant_not_found_on_set_frequency() {
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(USER_ID, 999L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setFrequency(USER_ID, 999L, PhotoProgressFrequency.P2W))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Plant not found");
    }

    @Test
    void should_save_photo_and_reschedule_when_frequency_enabled() {
        plant.setPhotoProgressFrequency(PhotoProgressFrequency.P2W);
        when(photoRepository.existsByPlantIdAndTakenAtAfter(eq(PLANT_ID), any(LocalDateTime.class)))
                .thenReturn(false);
        when(photoRepository.save(any(PlantProgressPhoto.class)))
                .thenAnswer(i -> {
                    PlantProgressPhoto p = i.getArgument(0);
                    ReflectionTestUtils.setField(p, "id", 100L);
                    return p;
                });

        LocalDateTime before = LocalDateTime.now();
        PlantProgressPhoto saved = service.addPhoto(USER_ID, PLANT_ID, FILE_ID);

        assertThat(saved.getTelegramFileId()).isEqualTo(FILE_ID);
        assertThat(saved.getPlant()).isEqualTo(plant);
        assertThat(saved.getUser()).isEqualTo(user);
        assertThat(saved.getTakenAt()).isAfterOrEqualTo(before);
        assertThat(plant.getNextPhotoDueAt())
                .isAfterOrEqualTo(before.plusWeeks(2).minusSeconds(5));
        assertThat(plant.getLastPhotoPromptSentAt()).isNull();
        verify(plantRepository).save(plant);
    }

    @Test
    void should_save_photo_without_reschedule_when_frequency_off() {
        plant.setPhotoProgressFrequency(PhotoProgressFrequency.OFF);
        when(photoRepository.existsByPlantIdAndTakenAtAfter(eq(PLANT_ID), any(LocalDateTime.class)))
                .thenReturn(false);
        when(photoRepository.save(any(PlantProgressPhoto.class)))
                .thenAnswer(i -> i.getArgument(0));

        service.addPhoto(USER_ID, PLANT_ID, FILE_ID);

        assertThat(plant.getNextPhotoDueAt()).isNull();
    }

    @Test
    void should_throw_when_anti_spam_24h_violated() {
        when(photoRepository.existsByPlantIdAndTakenAtAfter(eq(PLANT_ID), any(LocalDateTime.class)))
                .thenReturn(true);

        assertThatThrownBy(() -> service.addPhoto(USER_ID, PLANT_ID, FILE_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("24 часа");

        verify(photoRepository, never()).save(any());
    }

    @Test
    void should_throw_when_telegram_file_id_blank() {
        assertThatThrownBy(() -> service.addPhoto(USER_ID, PLANT_ID, "   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.addPhoto(USER_ID, PLANT_ID, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------- addPhotoFromStorage (issue #253)

    @Test
    void should_save_s3_photo_and_reschedule_when_frequency_enabled() {
        plant.setPhotoProgressFrequency(PhotoProgressFrequency.P1M);
        when(photoRepository.existsByPlantIdAndTakenAtAfter(eq(PLANT_ID), any(LocalDateTime.class)))
                .thenReturn(false);
        when(photoRepository.save(any(PlantProgressPhoto.class)))
                .thenAnswer(i -> {
                    PlantProgressPhoto p = i.getArgument(0);
                    ReflectionTestUtils.setField(p, "id", 200L);
                    return p;
                });
        Photo s3photo = s3Photo(77L);

        LocalDateTime before = LocalDateTime.now();
        PlantProgressPhoto saved = service.addPhotoFromStorage(USER_ID, PLANT_ID, s3photo, "новый лист");

        assertThat(saved.getPhoto()).isEqualTo(s3photo);
        assertThat(saved.getTelegramFileId()).isNull();
        assertThat(saved.getCaption()).isEqualTo("новый лист");
        assertThat(saved.getPlant()).isEqualTo(plant);
        assertThat(saved.getUser()).isEqualTo(user);
        assertThat(saved.getTakenAt()).isAfterOrEqualTo(before);
        assertThat(plant.getNextPhotoDueAt())
                .isAfterOrEqualTo(before.plusMonths(1).minusSeconds(5));
        assertThat(plant.getLastPhotoPromptSentAt()).isNull();
    }

    @Test
    void should_save_s3_photo_without_reschedule_when_frequency_off() {
        plant.setPhotoProgressFrequency(PhotoProgressFrequency.OFF);
        when(photoRepository.existsByPlantIdAndTakenAtAfter(eq(PLANT_ID), any(LocalDateTime.class)))
                .thenReturn(false);
        when(photoRepository.save(any(PlantProgressPhoto.class))).thenAnswer(i -> i.getArgument(0));

        service.addPhotoFromStorage(USER_ID, PLANT_ID, s3Photo(77L), null);

        assertThat(plant.getNextPhotoDueAt()).isNull();
    }

    @Test
    void should_throw_when_s3_photo_null() {
        assertThatThrownBy(() -> service.addPhotoFromStorage(USER_ID, PLANT_ID, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("photo is required");

        verify(photoRepository, never()).save(any());
    }

    @Test
    void should_throw_when_s3_photo_added_to_foreign_plant() {
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(USER_ID, 999L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addPhotoFromStorage(USER_ID, 999L, s3Photo(77L), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Plant not found");

        verify(photoRepository, never()).save(any());
    }

    @Test
    void should_throw_when_s3_photo_anti_spam_24h_violated() {
        when(photoRepository.existsByPlantIdAndTakenAtAfter(eq(PLANT_ID), any(LocalDateTime.class)))
                .thenReturn(true);

        assertThatThrownBy(() -> service.addPhotoFromStorage(USER_ID, PLANT_ID, s3Photo(77L), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("24 часа");

        verify(photoRepository, never()).save(any());
    }

    @Test
    void should_reschedule_next_due_at_on_skip_prompt() {
        plant.setPhotoProgressFrequency(PhotoProgressFrequency.P1M);
        LocalDateTime before = LocalDateTime.now();

        Plant result = service.skipPrompt(USER_ID, PLANT_ID);

        assertThat(result.getNextPhotoDueAt())
                .isAfterOrEqualTo(before.plusMonths(1).minusSeconds(5));
        assertThat(result.getLastPhotoPromptSentAt())
                .isAfterOrEqualTo(before.minusSeconds(1));
    }

    @Test
    void should_noop_skip_when_frequency_off() {
        plant.setPhotoProgressFrequency(PhotoProgressFrequency.OFF);
        plant.setNextPhotoDueAt(null);

        Plant result = service.skipPrompt(USER_ID, PLANT_ID);

        assertThat(result.getNextPhotoDueAt()).isNull();
        verify(plantRepository, never()).save(any());
    }

    @Test
    void should_return_pair_when_compare_first_last_with_two_photos() {
        PlantProgressPhoto first = photo(1L, LocalDateTime.now().minusDays(30));
        PlantProgressPhoto last = photo(2L, LocalDateTime.now());
        when(photoRepository.countByPlantId(PLANT_ID)).thenReturn(2L);
        when(photoRepository.findFirstByPlantIdOrderByTakenAtAsc(PLANT_ID)).thenReturn(Optional.of(first));
        when(photoRepository.findFirstByPlantIdOrderByTakenAtDesc(PLANT_ID)).thenReturn(Optional.of(last));

        Optional<PhotoProgressService.PhotoPair> pair = service.compareFirstVsLast(USER_ID, PLANT_ID);

        assertThat(pair).isPresent();
        assertThat(pair.get().before()).isEqualTo(first);
        assertThat(pair.get().after()).isEqualTo(last);
    }

    @Test
    void should_return_empty_when_compare_first_last_has_less_than_two_photos() {
        when(photoRepository.countByPlantId(PLANT_ID)).thenReturn(1L);

        Optional<PhotoProgressService.PhotoPair> pair = service.compareFirstVsLast(USER_ID, PLANT_ID);

        assertThat(pair).isEmpty();
    }

    @Test
    void should_return_empty_when_compare_first_last_returns_same_photo() {
        PlantProgressPhoto only = photo(1L, LocalDateTime.now());
        when(photoRepository.countByPlantId(PLANT_ID)).thenReturn(2L);
        when(photoRepository.findFirstByPlantIdOrderByTakenAtAsc(PLANT_ID)).thenReturn(Optional.of(only));
        when(photoRepository.findFirstByPlantIdOrderByTakenAtDesc(PLANT_ID)).thenReturn(Optional.of(only));

        Optional<PhotoProgressService.PhotoPair> pair = service.compareFirstVsLast(USER_ID, PLANT_ID);

        assertThat(pair).isEmpty();
    }

    @Test
    void should_compare_month_vs_now_returning_closest_before() {
        PlantProgressPhoto monthOld = photo(1L, LocalDateTime.now().minusMonths(1).minusDays(2));
        PlantProgressPhoto latest = photo(2L, LocalDateTime.now());
        when(photoRepository.findFirstByPlantIdOrderByTakenAtDesc(PLANT_ID)).thenReturn(Optional.of(latest));
        when(photoRepository.findClosestBefore(eq(PLANT_ID), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(monthOld));

        Optional<PhotoProgressService.PhotoPair> pair = service.compareMonthVsNow(USER_ID, PLANT_ID);

        assertThat(pair).isPresent();
        assertThat(pair.get().before()).isEqualTo(monthOld);
        assertThat(pair.get().after()).isEqualTo(latest);
    }

    @Test
    void should_return_empty_when_no_photo_for_month_compare() {
        when(photoRepository.findFirstByPlantIdOrderByTakenAtDesc(PLANT_ID)).thenReturn(Optional.empty());

        assertThat(service.compareMonthVsNow(USER_ID, PLANT_ID)).isEmpty();
    }

    @Test
    void should_compare_by_ids_ordering_by_taken_at() {
        PlantProgressPhoto older = photo(1L, LocalDateTime.now().minusDays(30));
        PlantProgressPhoto newer = photo(2L, LocalDateTime.now());
        older.setPlant(plant);
        newer.setPlant(plant);
        when(photoRepository.findById(1L)).thenReturn(Optional.of(older));
        when(photoRepository.findById(2L)).thenReturn(Optional.of(newer));

        // Передаём в обратном порядке (left=newer, right=older) — сервис должен переставить.
        Optional<PhotoProgressService.PhotoPair> pair =
                service.compareByIds(USER_ID, PLANT_ID, 2L, 1L);

        assertThat(pair).isPresent();
        assertThat(pair.get().before()).isEqualTo(older);
        assertThat(pair.get().after()).isEqualTo(newer);
    }

    @Test
    void should_use_id_tiebreaker_when_compare_by_ids_have_equal_taken_at() {
        LocalDateTime same = LocalDateTime.now();
        PlantProgressPhoto smallerId = photo(1L, same);
        PlantProgressPhoto biggerId = photo(2L, same);
        smallerId.setPlant(plant);
        biggerId.setPlant(plant);
        when(photoRepository.findById(1L)).thenReturn(Optional.of(smallerId));
        when(photoRepository.findById(2L)).thenReturn(Optional.of(biggerId));

        // Передаём в обратном порядке (left=biggerId, right=smallerId) —
        // tie-breaker по id должен поставить меньший id в "до".
        Optional<PhotoProgressService.PhotoPair> pair =
                service.compareByIds(USER_ID, PLANT_ID, 2L, 1L);

        assertThat(pair).isPresent();
        assertThat(pair.get().before()).isEqualTo(smallerId);
        assertThat(pair.get().after()).isEqualTo(biggerId);
    }

    @Test
    void should_reject_compare_by_ids_when_photo_belongs_to_another_plant() {
        Plant otherPlant = Plant.builder().user(user).name("X").build();
        ReflectionTestUtils.setField(otherPlant, "id", 999L);
        PlantProgressPhoto foreign = photo(1L, LocalDateTime.now());
        foreign.setPlant(otherPlant);
        PlantProgressPhoto mine = photo(2L, LocalDateTime.now());
        mine.setPlant(plant);
        when(photoRepository.findById(1L)).thenReturn(Optional.of(foreign));
        when(photoRepository.findById(2L)).thenReturn(Optional.of(mine));

        Optional<PhotoProgressService.PhotoPair> pair =
                service.compareByIds(USER_ID, PLANT_ID, 1L, 2L);

        assertThat(pair).isEmpty();
    }

    @Test
    void should_return_empty_compare_by_ids_when_same_id() {
        Optional<PhotoProgressService.PhotoPair> pair =
                service.compareByIds(USER_ID, PLANT_ID, 5L, 5L);

        assertThat(pair).isEmpty();
        verify(photoRepository, never()).findById(anyLong());
    }

    @Test
    void should_return_history_with_pagination() {
        PlantProgressPhoto photo = photo(1L, LocalDateTime.now());
        when(photoRepository.findAllByPlantIdOrderByTakenAtDesc(eq(PLANT_ID), any(Pageable.class)))
                .thenReturn(List.of(photo));

        List<PlantProgressPhoto> page = service.getHistory(USER_ID, PLANT_ID, 0);

        assertThat(page).containsExactly(photo);
    }

    @Test
    void should_normalize_negative_page_number_in_history() {
        when(photoRepository.findAllByPlantIdOrderByTakenAtDesc(eq(PLANT_ID), any(Pageable.class)))
                .thenReturn(List.of());

        // Не должен бросать исключение даже на отрицательной странице.
        List<PlantProgressPhoto> page = service.getHistory(USER_ID, PLANT_ID, -5);

        assertThat(page).isEmpty();
    }

    @Test
    void should_filter_foreign_photo_in_get_photo_for_user() {
        User other = User.builder().build();
        ReflectionTestUtils.setField(other, "id", 999L);
        PlantProgressPhoto p = photo(7L, LocalDateTime.now());
        p.setUser(other);
        when(photoRepository.findById(7L)).thenReturn(Optional.of(p));

        Optional<PlantProgressPhoto> result = service.getPhotoForUser(USER_ID, 7L);

        assertThat(result).isEmpty();
    }

    private PlantProgressPhoto photo(Long id, LocalDateTime takenAt) {
        PlantProgressPhoto p = new PlantProgressPhoto();
        ReflectionTestUtils.setField(p, "id", id);
        p.setTakenAt(takenAt);
        p.setPlant(plant);
        p.setUser(user);
        p.setTelegramFileId("file-" + id);
        return p;
    }

    private static Photo s3Photo(long id) {
        Photo photo = new Photo("photos/" + id, "image/jpeg", 1024L, USER_ID, java.time.Instant.now());
        ReflectionTestUtils.setField(photo, "id", id);
        return photo;
    }
}
