package com.plantcare.core.service;

import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.Room;
import com.plantcare.core.domain.User;
import com.plantcare.core.repository.LocationRepository;
import com.plantcare.core.repository.PlantRepository;
import com.plantcare.core.repository.RoomRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-тесты для RoomService")
class RoomServiceTest {

    @Mock
    private RoomRepository roomRepository;

    @Mock
    private LocationRepository locationRepository;

    @Mock
    private PlantRepository plantRepository;

    @InjectMocks
    private RoomService roomService;

    private User user;
    private Location location;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .telegramChatId(100L)
                .timezone("Europe/Minsk")
                .build();
        ReflectionTestUtils.setField(user, "id", 1L);

        location = Location.builder()
                .user(user)
                .name("Квартира")
                .emoji("🏠")
                .build();
        ReflectionTestUtils.setField(location, "id", 10L);
    }

    // ──────────────────────────── createRoom ────────────────────────────

    @Nested
    @DisplayName("createRoom")
    class CreateRoomTests {

        @Test
        @DisplayName("Создаёт комнату с корректными параметрами")
        void should_createRoom_when_validInputProvided() {
            when(locationRepository.findByUserIdAndId(1L, 10L)).thenReturn(Optional.of(location));
            when(roomRepository.existsByUserIdAndLocationIdAndNameIgnoreCase(1L, 10L, "Гостиная"))
                    .thenReturn(false);
            when(roomRepository.countByUserIdAndLocationId(1L, 10L)).thenReturn(0L);
            when(roomRepository.save(any(Room.class))).thenAnswer(inv -> {
                Room r = inv.getArgument(0);
                ReflectionTestUtils.setField(r, "id", 42L);
                return r;
            });

            Room result = roomService.createRoom(user, 10L, "  Гостиная  ", 1);

            assertThat(result.getId()).isEqualTo(42L);
            assertThat(result.getName()).isEqualTo("Гостиная");
            assertThat(result.getDisplayOrder()).isEqualTo(1);
            assertThat(result.getLocation()).isEqualTo(location);
            assertThat(result.getUser()).isEqualTo(user);
        }

        @Test
        @DisplayName("displayOrder null → устанавливается 0")
        void should_useZeroDisplayOrder_when_displayOrderIsNull() {
            when(locationRepository.findByUserIdAndId(1L, 10L)).thenReturn(Optional.of(location));
            when(roomRepository.existsByUserIdAndLocationIdAndNameIgnoreCase(1L, 10L, "Кухня"))
                    .thenReturn(false);
            when(roomRepository.countByUserIdAndLocationId(1L, 10L)).thenReturn(5L);
            when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));

            Room result = roomService.createRoom(user, 10L, "Кухня", null);

            assertThat(result.getDisplayOrder()).isEqualTo(0);
        }

        @Test
        @DisplayName("Отклоняет создание с пустым названием")
        void should_rejectCreation_when_nameIsBlank() {
            when(locationRepository.findByUserIdAndId(1L, 10L)).thenReturn(Optional.of(location));

            assertThatThrownBy(() -> roomService.createRoom(user, 10L, "   ", 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("не может быть пустым");

            verify(roomRepository, never()).save(any());
        }

        @Test
        @DisplayName("Отклоняет создание с null названием")
        void should_rejectCreation_when_nameIsNull() {
            when(locationRepository.findByUserIdAndId(1L, 10L)).thenReturn(Optional.of(location));

            assertThatThrownBy(() -> roomService.createRoom(user, 10L, null, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("не может быть пустым");

            verify(roomRepository, never()).save(any());
        }

        @Test
        @DisplayName("Отклоняет создание с названием длиннее 100 символов")
        void should_rejectCreation_when_nameTooLong() {
            String longName = "а".repeat(101);
            when(locationRepository.findByUserIdAndId(1L, 10L)).thenReturn(Optional.of(location));

            assertThatThrownBy(() -> roomService.createRoom(user, 10L, longName, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("100 символов");

            verify(roomRepository, never()).save(any());
        }

        @Test
        @DisplayName("Отклоняет дублирующееся название (регистронезависимо)")
        void should_rejectCreation_when_duplicateNameExists() {
            when(locationRepository.findByUserIdAndId(1L, 10L)).thenReturn(Optional.of(location));
            when(roomRepository.existsByUserIdAndLocationIdAndNameIgnoreCase(1L, 10L, "Гостиная"))
                    .thenReturn(true);

            assertThatThrownBy(() -> roomService.createRoom(user, 10L, "Гостиная", 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("уже существует");

            verify(roomRepository, never()).save(any());
        }

        @Test
        @DisplayName("Отклоняет создание при достижении лимита в 50 комнат")
        void should_rejectCreation_when_roomCountAtLimit() {
            when(locationRepository.findByUserIdAndId(1L, 10L)).thenReturn(Optional.of(location));
            when(roomRepository.existsByUserIdAndLocationIdAndNameIgnoreCase(1L, 10L, "Новая"))
                    .thenReturn(false);
            when(roomRepository.countByUserIdAndLocationId(1L, 10L)).thenReturn(50L);

            assertThatThrownBy(() -> roomService.createRoom(user, 10L, "Новая", 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("50");

            verify(roomRepository, never()).save(any());
        }

        @Test
        @DisplayName("Бросает EntityNotFoundException когда локация не найдена при создании")
        void should_throwEntityNotFoundException_when_locationNotFoundOnCreate() {
            when(locationRepository.findByUserIdAndId(1L, 10L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> roomService.createRoom(user, 10L, "Комната", 0))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("10");
        }
    }

    // ──────────────────────────── updateRoom ────────────────────────────

    @Nested
    @DisplayName("updateRoom")
    class UpdateRoomTests {

        private Room existingRoom;

        @BeforeEach
        void setUpRoom() {
            existingRoom = Room.builder()
                    .user(user)
                    .location(location)
                    .name("Старое")
                    .displayOrder(0)
                    .build();
            ReflectionTestUtils.setField(existingRoom, "id", 20L);
        }

        @Test
        @DisplayName("Обновляет название и displayOrder при корректных данных")
        void should_updateNameAndDisplayOrder_when_validInputProvided() {
            when(roomRepository.findByIdAndUserId(20L, 1L)).thenReturn(Optional.of(existingRoom));
            when(roomRepository.existsByUserIdAndLocationIdAndNameIgnoreCaseAndIdNot(
                    1L, 10L, "Новое", 20L)).thenReturn(false);
            when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));

            Room result = roomService.updateRoom(1L, 20L, "  Новое  ", 5);

            assertThat(result.getName()).isEqualTo("Новое");
            assertThat(result.getDisplayOrder()).isEqualTo(5);
        }

        @Test
        @DisplayName("null name не меняет название комнаты")
        void should_notUpdateName_when_nameIsNull() {
            when(roomRepository.findByIdAndUserId(20L, 1L)).thenReturn(Optional.of(existingRoom));
            when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));

            Room result = roomService.updateRoom(1L, 20L, null, null);

            assertThat(result.getName()).isEqualTo("Старое");
        }

        @Test
        @DisplayName("Blank (не null) name молча игнорируется — не вызывает ошибку и не меняет название")
        void should_silentlyIgnoreBlankName_when_nameIsBlankButNotNull() {
            // Поведение: if (name != null && !name.isBlank()) { ... } — blank пропускается
            when(roomRepository.findByIdAndUserId(20L, 1L)).thenReturn(Optional.of(existingRoom));
            when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));

            Room result = roomService.updateRoom(1L, 20L, "   ", null);

            // Blank name молча не применяется — старое остаётся
            assertThat(result.getName()).isEqualTo("Старое");
            verify(roomRepository, never()).existsByUserIdAndLocationIdAndNameIgnoreCaseAndIdNot(
                    anyLong(), anyLong(), anyString(), anyLong());
        }

        @Test
        @DisplayName("Отклоняет обновление названием-дублем другой комнаты")
        void should_rejectUpdate_when_duplicateNameForDifferentRoom() {
            when(roomRepository.findByIdAndUserId(20L, 1L)).thenReturn(Optional.of(existingRoom));
            when(roomRepository.existsByUserIdAndLocationIdAndNameIgnoreCaseAndIdNot(
                    1L, 10L, "Кухня", 20L)).thenReturn(true);

            assertThatThrownBy(() -> roomService.updateRoom(1L, 20L, "Кухня", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("уже существует");

            verify(roomRepository, never()).save(any());
        }

        @Test
        @DisplayName("Обновляет только displayOrder, не меняя название")
        void should_updateDisplayOrderOnly_when_nameIsNull() {
            when(roomRepository.findByIdAndUserId(20L, 1L)).thenReturn(Optional.of(existingRoom));
            when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));

            Room result = roomService.updateRoom(1L, 20L, null, 99);

            assertThat(result.getDisplayOrder()).isEqualTo(99);
            assertThat(result.getName()).isEqualTo("Старое");
        }

        @Test
        @DisplayName("Бросает EntityNotFoundException когда комната не найдена при обновлении")
        void should_throwEntityNotFoundException_when_roomNotFoundOnUpdate() {
            when(roomRepository.findByIdAndUserId(20L, 1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> roomService.updateRoom(1L, 20L, "New", null))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("20");
        }
    }

    // ──────────────────────────── deleteRoom ────────────────────────────

    @Nested
    @DisplayName("deleteRoom")
    class DeleteRoomTests {

        private Room room;

        @BeforeEach
        void setUpRoom() {
            room = Room.builder()
                    .user(user)
                    .location(location)
                    .name("Балкон")
                    .build();
            ReflectionTestUtils.setField(room, "id", 30L);
        }

        @Test
        @DisplayName("Удаляет комнату когда в ней нет активных растений")
        void should_deleteRoom_when_noActivePlants() {
            when(roomRepository.findByIdAndUserId(30L, 1L)).thenReturn(Optional.of(room));
            when(plantRepository.countByRoomIdAndArchivedAtIsNull(30L)).thenReturn(0L);

            roomService.deleteRoom(1L, 30L);

            verify(roomRepository).delete(room);
        }

        @Test
        @DisplayName("Блокирует удаление когда в комнате есть активные растения, сообщение содержит кол-во")
        void should_rejectDeletion_when_activePlantsExist() {
            when(roomRepository.findByIdAndUserId(30L, 1L)).thenReturn(Optional.of(room));
            when(plantRepository.countByRoomIdAndArchivedAtIsNull(30L)).thenReturn(3L);

            assertThatThrownBy(() -> roomService.deleteRoom(1L, 30L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("3");

            verify(roomRepository, never()).delete(any());
        }

        @Test
        @DisplayName("Бросает EntityNotFoundException когда комната не найдена при удалении")
        void should_throwEntityNotFoundException_when_roomNotFoundOnDelete() {
            when(roomRepository.findByIdAndUserId(30L, 1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> roomService.deleteRoom(1L, 30L))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("30");
        }
    }

    // ──────────────────────────── getRoomOrThrow ────────────────────────────

    @Nested
    @DisplayName("getRoomOrThrow")
    class GetRoomOrThrowTests {

        @Test
        @DisplayName("Возвращает комнату если она принадлежит пользователю")
        void should_returnRoom_when_roomExistsAndBelongsToUser() {
            Room room = Room.builder().user(user).name("Спальня").build();
            ReflectionTestUtils.setField(room, "id", 50L);
            when(roomRepository.findByIdAndUserId(50L, 1L)).thenReturn(Optional.of(room));

            Room result = roomService.getRoomOrThrow(1L, 50L);

            assertThat(result.getName()).isEqualTo("Спальня");
        }

        @Test
        @DisplayName("Бросает EntityNotFoundException когда комната не найдена")
        void should_throwEntityNotFoundException_when_roomNotFound() {
            when(roomRepository.findByIdAndUserId(99L, 1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> roomService.getRoomOrThrow(1L, 99L))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("99");
        }
    }

    // ──────────────────────────── getRoomsInLocation ────────────────────────────

    @Nested
    @DisplayName("getRoomsInLocation")
    class GetRoomsInLocationTests {

        @Test
        @DisplayName("Возвращает список комнат в локации")
        void should_listRoomsInLocation_when_locationExists() {
            when(locationRepository.findByUserIdAndId(1L, 10L)).thenReturn(Optional.of(location));
            Room r1 = Room.builder().user(user).location(location).name("Кухня").displayOrder(0).build();
            Room r2 = Room.builder().user(user).location(location).name("Спальня").displayOrder(1).build();
            when(roomRepository.findAllByUserIdAndLocationIdOrderByDisplayOrderAscNameAsc(1L, 10L))
                    .thenReturn(List.of(r1, r2));

            List<Room> result = roomService.getRoomsInLocation(1L, 10L);

            assertThat(result).hasSize(2);
            assertThat(result).extracting(Room::getName).containsExactly("Кухня", "Спальня");
        }

        @Test
        @DisplayName("Бросает EntityNotFoundException если локация не принадлежит пользователю")
        void should_throwEntityNotFoundException_when_locationNotFoundForUser() {
            when(locationRepository.findByUserIdAndId(1L, 10L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> roomService.getRoomsInLocation(1L, 10L))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("10");
        }
    }
}
