package com.plantcare.bot.service;

import com.plantcare.bot.domain.Location;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.repository.LocationRepository;
import com.plantcare.bot.repository.PlantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-тесты для LocationService")
class LocationServiceTest {

    @Mock
    private LocationRepository locationRepository;

    @Mock
    private PlantRepository plantRepository;

    @Mock
    private AnalyticsService analyticsService;

    @InjectMocks
    private LocationService locationService;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .telegramChatId(123L)
                .timezone("Europe/Riga")
                .build();

        ReflectionTestUtils.setField(user, "id", 1L);
    }

    @Test
    @DisplayName("Создаёт новую комнату")
    void createLocationCreatesNewLocation() {
        when(locationRepository.countByUserId(user.getId())).thenReturn(1L);
        when(locationRepository.existsByUserIdAndNameIgnoreCase(user.getId(), "Кухня"))
                .thenReturn(false);

        when(locationRepository.save(any(Location.class))).thenAnswer(invocation -> {
            Location location = invocation.getArgument(0);
            ReflectionTestUtils.setField(location, "id", 10L);
            return location;
        });

        Location result = locationService.createLocation(user, "  Кухня  ", "🍳");

        assertThat(result.getId()).isEqualTo(10L);
        assertThat(result.getUser()).isEqualTo(user);
        assertThat(result.getName()).isEqualTo("Кухня");
        assertThat(result.getEmoji()).isEqualTo("🍳");
        assertThat(result.isDefaultLocation()).isFalse();

        verify(analyticsService).track(eq(user.getId()), eq("location_created"), anyMap());
    }

    @Test
    @DisplayName("Если emoji пустой — ставит дефолтный")
    void createLocationUsesDefaultEmojiWhenEmojiBlank() {
        when(locationRepository.countByUserId(user.getId())).thenReturn(1L);
        when(locationRepository.existsByUserIdAndNameIgnoreCase(user.getId(), "Балкон"))
                .thenReturn(false);

        when(locationRepository.save(any(Location.class))).thenAnswer(invocation -> {
            Location location = invocation.getArgument(0);
            ReflectionTestUtils.setField(location, "id", 11L);
            return location;
        });

        Location result = locationService.createLocation(user, "Балкон", " ");

        assertThat(result.getEmoji()).isEqualTo(Location.DEFAULT_EMOJI);
    }

    @Test
    @DisplayName("Не создаёт комнату с пустым названием")
    void createLocationRejectsBlankName() {
        assertThatThrownBy(() -> locationService.createLocation(user, "   ", "🛋"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Название комнаты не может быть пустым");

        verify(locationRepository, never()).save(any());
    }

    @Test
    @DisplayName("Не создаёт комнату с названием больше 30 символов")
    void createLocationRejectsLongName() {
        String longName = "а".repeat(31);

        assertThatThrownBy(() -> locationService.createLocation(user, longName, "🛋"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не длиннее 30 символов");

        verify(locationRepository, never()).save(any());
    }

    @Test
    @DisplayName("Не создаёт комнату с дублем названия")
    void createLocationRejectsDuplicateName() {
        when(locationRepository.countByUserId(user.getId())).thenReturn(1L);
        when(locationRepository.existsByUserIdAndNameIgnoreCase(user.getId(), "Кухня"))
                .thenReturn(true);

        assertThatThrownBy(() -> locationService.createLocation(user, "Кухня", "🍳"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Такая комната уже есть");

        verify(locationRepository, never()).save(any());
    }

    @Test
    @DisplayName("Не создаёт больше 20 комнат")
    void createLocationRejectsWhenLimitReached() {
        when(locationRepository.countByUserId(user.getId())).thenReturn(20L);

        assertThatThrownBy(() -> locationService.createLocation(user, "Новая", "🌱"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Можно создать максимум 20 комнат");

        verify(locationRepository, never()).save(any());
    }

    @Test
    @DisplayName("Возвращает существующую дефолтную комнату")
    void getOrCreateDefaultLocationReturnsExistingDefault() {
        Location defaultLocation = Location.builder()
                .user(user)
                .name(Location.DEFAULT_NAME)
                .emoji(Location.DEFAULT_EMOJI)
                .defaultLocation(true)
                .build();

        ReflectionTestUtils.setField(defaultLocation, "id", 100L);

        when(locationRepository.findByUserIdAndDefaultLocationTrue(user.getId()))
                .thenReturn(Optional.of(defaultLocation));

        Location result = locationService.getOrCreateDefaultLocation(user);

        assertThat(result).isEqualTo(defaultLocation);

        verify(locationRepository, never()).save(any());
        verifyNoInteractions(analyticsService);
    }

    @Test
    @DisplayName("Создаёт дефолтную комнату, если её нет")
    void getOrCreateDefaultLocationCreatesDefaultIfMissing() {
        when(locationRepository.findByUserIdAndDefaultLocationTrue(user.getId()))
                .thenReturn(Optional.empty());

        when(locationRepository.save(any(Location.class))).thenAnswer(invocation -> {
            Location location = invocation.getArgument(0);
            ReflectionTestUtils.setField(location, "id", 101L);
            return location;
        });

        Location result = locationService.getOrCreateDefaultLocation(user);

        assertThat(result.getName()).isEqualTo(Location.DEFAULT_NAME);
        assertThat(result.getEmoji()).isEqualTo(Location.DEFAULT_EMOJI);
        assertThat(result.isDefaultLocation()).isTrue();

        verify(analyticsService).track(eq(user.getId()), eq("location_created"), anyMap());
    }

    @Test
    @DisplayName("Переименовывает комнату")
    void renameLocationRenamesLocation() {
        Location location = Location.builder()
                .user(user)
                .name("Старое")
                .emoji("🛋")
                .defaultLocation(false)
                .build();

        ReflectionTestUtils.setField(location, "id", 5L);

        when(locationRepository.findByUserIdAndId(user.getId(), location.getId()))
                .thenReturn(Optional.of(location));

        when(locationRepository.existsByUserIdAndNameIgnoreCaseAndIdNot(
                user.getId(),
                "Новое",
                location.getId()
        )).thenReturn(false);

        when(locationRepository.save(any(Location.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Location result = locationService.renameLocation(user.getId(), location.getId(), "  Новое  ");

        assertThat(result.getName()).isEqualTo("Новое");
    }

    @Test
    @DisplayName("Не переименовывает комнату в уже существующее название")
    void renameLocationRejectsDuplicateName() {
        Location location = Location.builder()
                .user(user)
                .name("Старое")
                .emoji("🛋")
                .defaultLocation(false)
                .build();

        ReflectionTestUtils.setField(location, "id", 5L);

        when(locationRepository.findByUserIdAndId(user.getId(), location.getId()))
                .thenReturn(Optional.of(location));

        when(locationRepository.existsByUserIdAndNameIgnoreCaseAndIdNot(
                user.getId(),
                "Кухня",
                location.getId()
        )).thenReturn(true);

        assertThatThrownBy(() -> locationService.renameLocation(user.getId(), location.getId(), "Кухня"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Такая комната уже есть");

        verify(locationRepository, never()).save(any());
    }

    @Test
    @DisplayName("Меняет emoji комнаты")
    void changeEmojiChangesLocationEmoji() {
        Location location = Location.builder()
                .user(user)
                .name("Кухня")
                .emoji("🍳")
                .defaultLocation(false)
                .build();

        ReflectionTestUtils.setField(location, "id", 6L);

        when(locationRepository.findByUserIdAndId(user.getId(), location.getId()))
                .thenReturn(Optional.of(location));

        when(locationRepository.save(any(Location.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Location result = locationService.changeEmoji(user.getId(), location.getId(), "🌿");

        assertThat(result.getEmoji()).isEqualTo("🌿");
    }

    @Test
    @DisplayName("Перемещает растение в другую комнату")
    void movePlantMovesPlantToTargetLocation() {
        Location oldLocation = Location.builder()
                .user(user)
                .name("Кухня")
                .emoji("🍳")
                .build();
        ReflectionTestUtils.setField(oldLocation, "id", 1L);

        Location targetLocation = Location.builder()
                .user(user)
                .name("Балкон")
                .emoji("🌿")
                .build();
        ReflectionTestUtils.setField(targetLocation, "id", 2L);

        Plant plant = Plant.builder()
                .user(user)
                .name("Монстера")
                .location(oldLocation)
                .build();
        ReflectionTestUtils.setField(plant, "id", 50L);

        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(user.getId(), plant.getId()))
                .thenReturn(Optional.of(plant));

        when(locationRepository.findByUserIdAndId(user.getId(), targetLocation.getId()))
                .thenReturn(Optional.of(targetLocation));

        when(plantRepository.save(any(Plant.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Plant result = locationService.movePlant(user.getId(), plant.getId(), targetLocation.getId());

        assertThat(result.getLocation()).isEqualTo(targetLocation);

        verify(analyticsService).track(eq(user.getId()), eq("plant_moved"), anyMap());
    }

    @Test
    @DisplayName("Удаляет комнату и переносит растения в целевую комнату")
    void deleteLocationMovesPlantsAndDeletesLocation() {
        Location locationToDelete = Location.builder()
                .user(user)
                .name("Старая")
                .emoji("🛋")
                .defaultLocation(false)
                .build();
        ReflectionTestUtils.setField(locationToDelete, "id", 10L);

        Location targetLocation = Location.builder()
                .user(user)
                .name("Мои растения")
                .emoji("🪴")
                .defaultLocation(true)
                .build();
        ReflectionTestUtils.setField(targetLocation, "id", 20L);

        Plant plant1 = Plant.builder().user(user).name("Фикус").location(locationToDelete).build();
        Plant plant2 = Plant.builder().user(user).name("Монстера").location(locationToDelete).build();

        when(locationRepository.findByUserIdAndId(user.getId(), locationToDelete.getId()))
                .thenReturn(Optional.of(locationToDelete));

        when(locationRepository.findByUserIdAndId(user.getId(), targetLocation.getId()))
                .thenReturn(Optional.of(targetLocation));

        when(plantRepository.findAllByUserIdAndLocationIdAndArchivedAtIsNullOrderByNameAsc(
                user.getId(),
                locationToDelete.getId()
        )).thenReturn(List.of(plant1, plant2));

        locationService.deleteLocation(user.getId(), locationToDelete.getId(), targetLocation.getId());

        assertThat(plant1.getLocation()).isEqualTo(targetLocation);
        assertThat(plant2.getLocation()).isEqualTo(targetLocation);

        verify(plantRepository).saveAll(List.of(plant1, plant2));
        verify(locationRepository).delete(locationToDelete);
        verify(analyticsService).track(eq(user.getId()), eq("location_deleted"), anyMap());
    }

    @Test
    @DisplayName("Не удаляет дефолтную комнату")
    void deleteLocationRejectsDefaultLocation() {
        Location defaultLocation = Location.builder()
                .user(user)
                .name(Location.DEFAULT_NAME)
                .emoji(Location.DEFAULT_EMOJI)
                .defaultLocation(true)
                .build();

        ReflectionTestUtils.setField(defaultLocation, "id", 1L);

        when(locationRepository.findByUserIdAndId(user.getId(), defaultLocation.getId()))
                .thenReturn(Optional.of(defaultLocation));

        assertThatThrownBy(() -> locationService.deleteLocation(user.getId(), defaultLocation.getId(), 2L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Дефолтную комнату удалить нельзя");

        verify(locationRepository, never()).delete(any());
    }

    @Test
    @DisplayName("Не удаляет комнату, если целевая комната такая же")
    void deleteLocationRejectsMovingToSameLocation() {
        Location location = Location.builder()
                .user(user)
                .name("Кухня")
                .emoji("🍳")
                .defaultLocation(false)
                .build();

        ReflectionTestUtils.setField(location, "id", 10L);

        when(locationRepository.findByUserIdAndId(user.getId(), location.getId()))
                .thenReturn(Optional.of(location));

        assertThatThrownBy(() -> locationService.deleteLocation(user.getId(), location.getId(), location.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Нельзя перенести растения в удаляемую комнату");

        verify(locationRepository, never()).delete(any());
    }

    @Test
    @DisplayName("Проверяет лимит комнат")
    void hasReachedLocationsLimitWorksCorrectly() {
        when(locationRepository.countByUserId(user.getId())).thenReturn(20L);

        assertThat(locationService.hasReachedLocationsLimit(user.getId())).isTrue();
        assertThat(locationService.getMaxLocationsPerUser()).isEqualTo(20);
    }
}