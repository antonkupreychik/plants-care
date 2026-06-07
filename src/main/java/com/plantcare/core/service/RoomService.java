package com.plantcare.core.service;

import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.Room;
import com.plantcare.core.domain.User;
import com.plantcare.core.repository.LocationRepository;
import com.plantcare.core.repository.PlantRepository;
import com.plantcare.core.repository.RoomRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Бизнес-логика CRUD комнат в рамках локации (issue #283).
 * Комната принадлежит локации; при создании/обновлении проверяется,
 * что локация принадлежит тому же пользователю.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoomService {

    private static final int MAX_ROOMS_PER_LOCATION = 50;

    private final RoomRepository roomRepository;
    private final LocationRepository locationRepository;
    private final PlantRepository plantRepository;

    @Transactional(readOnly = true)
    public List<Room> getRoomsInLocation(Long userId, Long locationId) {
        getLocationOrThrow(userId, locationId);
        return roomRepository.findAllByUserIdAndLocationIdOrderByDisplayOrderAscNameAsc(userId, locationId);
    }

    @Transactional
    public Room createRoom(User user, Long locationId, String name, Integer displayOrder) {
        Location location = getLocationOrThrow(user.getId(), locationId);

        String normalizedName = normalizeName(name);
        validateName(normalizedName);

        if (roomRepository.existsByUserIdAndLocationIdAndNameIgnoreCase(user.getId(), locationId, normalizedName)) {
            throw new IllegalArgumentException("Комната с таким названием уже существует в этой локации");
        }

        long count = roomRepository.countByUserIdAndLocationId(user.getId(), locationId);
        if (count >= MAX_ROOMS_PER_LOCATION) {
            throw new IllegalArgumentException(
                    "Превышен лимит комнат в локации (" + MAX_ROOMS_PER_LOCATION + ")");
        }

        Room room = Room.builder()
                .user(user)
                .location(location)
                .name(normalizedName)
                .displayOrder(displayOrder != null ? displayOrder : 0)
                .build();

        Room saved = roomRepository.save(room);
        log.info("Created room {} '{}' in location {} for user {}", saved.getId(), saved.getName(), locationId, user.getId());
        return saved;
    }

    @Transactional
    public Room updateRoom(Long userId, Long roomId, String name, Integer displayOrder) {
        Room room = getRoomOrThrow(userId, roomId);

        if (name != null && !name.isBlank()) {
            String normalizedName = normalizeName(name);
            validateName(normalizedName);

            Long locationId = room.getLocation() != null ? room.getLocation().getId() : null;
            if (locationId != null) {
                boolean duplicate = roomRepository.existsByUserIdAndLocationIdAndNameIgnoreCaseAndIdNot(
                        userId, locationId, normalizedName, roomId);
                if (duplicate) {
                    throw new IllegalArgumentException("Комната с таким названием уже существует в этой локации");
                }
            }
            room.setName(normalizedName);
        }

        if (displayOrder != null) {
            room.setDisplayOrder(displayOrder);
        }

        Room saved = roomRepository.save(room);
        log.info("Updated room {} for user {}", roomId, userId);
        return saved;
    }

    /**
     * Удалить комнату. Если в ней есть активные растения — бросает
     * {@link IllegalStateException} с количеством растений.
     * Контроллер преобразует это в 409 ROOM_NOT_EMPTY.
     */
    @Transactional
    public void deleteRoom(Long userId, Long roomId) {
        Room room = getRoomOrThrow(userId, roomId);

        long plantCount = plantRepository.countByRoomIdAndArchivedAtIsNull(roomId);
        if (plantCount > 0) {
            throw new IllegalStateException(
                    "Комната содержит " + plantCount + " активных растений. Перенесите их перед удалением."
            );
        }

        roomRepository.delete(room);
        log.info("Deleted room {} for user {}", roomId, userId);
    }

    @Transactional(readOnly = true)
    public Room getRoomOrThrow(Long userId, Long roomId) {
        return roomRepository.findByIdAndUserId(roomId, userId)
                .orElseThrow(() -> new EntityNotFoundException("Room not found: " + roomId));
    }

    // ----------- private helpers -----------

    private Location getLocationOrThrow(Long userId, Long locationId) {
        return locationRepository.findByUserIdAndId(userId, locationId)
                .orElseThrow(() -> new EntityNotFoundException("Location not found: " + locationId));
    }

    private String normalizeName(String name) {
        return name == null ? "" : name.trim();
    }

    private void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Название комнаты не может быть пустым");
        }
        if (name.length() > 100) {
            throw new IllegalArgumentException("Название комнаты должно быть не длиннее 100 символов");
        }
    }
}
