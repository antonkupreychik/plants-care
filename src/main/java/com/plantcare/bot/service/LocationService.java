package com.plantcare.bot.service;

import com.plantcare.bot.domain.Location;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.repository.LocationRepository;
import com.plantcare.bot.repository.PlantRepository;
import com.plantcare.bot.util.EmojiValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LocationService {

    private static final int MAX_LOCATIONS_PER_USER = 20;
    private static final String DEFAULT_LOCATION_NAME = "Мои растения";
    private static final String DEFAULT_LOCATION_EMOJI = "🪴";

    private final LocationRepository locationRepository;
    private final PlantRepository plantRepository;
    private final AnalyticsService analyticsService;

    @Transactional(readOnly = true)
    public List<Location> getUserLocations(Long userId) {
        return locationRepository.findAllByUserIdOrderByDefaultLocationAscCreatedAtAsc(userId);
    }

    @Transactional(readOnly = true)
    public Location getLocation(Long userId, Long locationId) {
        return getUserLocationOrThrow(userId, locationId);
    }

    @Transactional(readOnly = true)
    public Location getUserLocationOrThrow(Long userId, Long locationId) {
        return locationRepository.findByUserIdAndId(userId, locationId)
                .orElseThrow(() -> new IllegalArgumentException("Комната не найдена"));
    }

    @Transactional(readOnly = true)
    public boolean hasReachedLocationsLimit(Long userId) {
        return locationRepository.countByUserId(userId) >= MAX_LOCATIONS_PER_USER;
    }

    public int getMaxLocationsPerUser() {
        return MAX_LOCATIONS_PER_USER;
    }

    @Transactional
    public Location getOrCreateDefaultLocation(User user) {
        return locationRepository.findByUserIdAndDefaultLocationTrue(user.getId())
                .orElseGet(() -> {
                    Location location = Location.builder()
                            .user(user)
                            .name(DEFAULT_LOCATION_NAME)
                            .emoji(DEFAULT_LOCATION_EMOJI)
                            .defaultLocation(true)
                            .build();

                    Location saved = locationRepository.save(location);

                    analyticsService.track(user.getId(), "location_created", Map.of(
                            "location_id", saved.getId(),
                            "is_default", true,
                            "source", "default"
                    ));

                    log.info(
                            "Created default location {} for user {}",
                            saved.getId(),
                            user.getTelegramChatId()
                    );

                    return saved;
                });
    }

    @Transactional
    public Location createLocation(User user, String name, String emoji) {
        String normalizedName = normalizeName(name);
        String normalizedEmoji = normalizeEmoji(emoji);

        validateLocationName(normalizedName);
        validateEmoji(normalizedEmoji);

        if (hasReachedLocationsLimit(user.getId())) {
            throw new IllegalArgumentException("Можно создать максимум 20 комнат");
        }

        if (locationRepository.existsByUserIdAndNameIgnoreCase(user.getId(), normalizedName)) {
            throw new IllegalArgumentException("Такая комната уже есть");
        }

        Location location = Location.builder()
                .user(user)
                .name(normalizedName)
                .emoji(normalizedEmoji)
                .defaultLocation(false)
                .build();

        Location saved = locationRepository.save(location);

        analyticsService.track(user.getId(), "location_created", Map.of(
                "location_id", saved.getId(),
                "is_default", false,
                "source", "manual"
        ));

        log.info(
                "Created location {} for user {}",
                saved.getId(),
                user.getTelegramChatId()
        );

        return saved;
    }

    @Transactional
    public Location renameLocation(Long userId, Long locationId, String newName) {
        String normalizedName = normalizeName(newName);

        validateLocationName(normalizedName);

        Location location = getUserLocationOrThrow(userId, locationId);

        boolean duplicateExists = locationRepository.existsByUserIdAndNameIgnoreCaseAndIdNot(
                userId,
                normalizedName,
                locationId
        );

        if (duplicateExists) {
            throw new IllegalArgumentException("Такая комната уже есть");
        }

        location.setName(normalizedName);

        Location saved = locationRepository.save(location);

        log.info(
                "Renamed location {} for user {} to '{}'",
                locationId,
                userId,
                normalizedName
        );

        return saved;
    }

    @Transactional
    public Location changeEmoji(Long userId, Long locationId, String emoji) {
        String normalizedEmoji = normalizeEmoji(emoji);

        validateEmoji(normalizedEmoji);

        Location location = getUserLocationOrThrow(userId, locationId);
        location.setEmoji(normalizedEmoji);

        Location saved = locationRepository.save(location);

        log.info(
                "Changed emoji for location {} of user {} to '{}'",
                locationId,
                userId,
                normalizedEmoji
        );

        return saved;
    }

    @Transactional(readOnly = true)
    public long countPlantsInLocation(Long userId, Long locationId) {
        getUserLocationOrThrow(userId, locationId);

        return plantRepository.countByUserIdAndLocationIdAndArchivedAtIsNull(
                userId,
                locationId
        );
    }

    @Transactional(readOnly = true)
    public List<Plant> getPlantsInLocation(Long userId, Long locationId) {
        getUserLocationOrThrow(userId, locationId);

        return plantRepository.findAllByUserIdAndLocationIdAndArchivedAtIsNullOrderByNameAsc(
                userId,
                locationId
        );
    }

    @Transactional(readOnly = true)
    public Plant getUserPlant(Long userId, Long plantId) {
        Plant plant = plantRepository.findByUserIdAndIdAndArchivedAtIsNull(userId, plantId)
                .orElseThrow(() -> new IllegalArgumentException("Растение не найдено"));

        if (plant.getLocation() != null) {
            plant.getLocation().getId();
            plant.getLocation().getName();
            plant.getLocation().getEmoji();
            plant.getLocation().isDefaultLocation();
        }

        return plant;
    }

    @Transactional
    public Plant movePlant(Long userId, Long plantId, Long targetLocationId) {
        Plant plant = getUserPlant(userId, plantId);
        Location targetLocation = getUserLocationOrThrow(userId, targetLocationId);

        Long previousLocationId = plant.getLocation() != null
                ? plant.getLocation().getId()
                : null;

        plant.setLocation(targetLocation);

        Plant saved = plantRepository.save(plant);

        if (saved.getLocation() != null) {
            saved.getLocation().getId();
            saved.getLocation().getName();
            saved.getLocation().getEmoji();
            saved.getLocation().isDefaultLocation();
        }

        Map<String, Object> properties = new HashMap<>();
        properties.put("plant_id", plantId);
        properties.put("from_location_id", previousLocationId);
        properties.put("to_location_id", targetLocationId);

        analyticsService.track(userId, "plant_moved", properties);

        log.info(
                "Moved plant {} of user {} from location {} to location {}",
                plantId,
                userId,
                previousLocationId,
                targetLocationId
        );

        return saved;
    }

    /**
     * Обновить имя и/или emoji локации (REST API, issue #85).
     * Логика инлайновая — вызов через this.renameLocation / this.changeEmoji
     * обходил бы Spring-прокси и игнорировал их @Transactional,
     * а также приводил к лишним запросам к БД (до 3× getUserLocationOrThrow).
     */
    @Transactional
    public Location updateLocation(Long userId, Long locationId, String name, String emoji) {
        Location location = getUserLocationOrThrow(userId, locationId);
        if (name != null && !name.isBlank()) {
            String normalizedName = normalizeName(name);
            validateLocationName(normalizedName);
            boolean duplicateExists = locationRepository.existsByUserIdAndNameIgnoreCaseAndIdNot(
                    userId, normalizedName, locationId);
            if (duplicateExists) {
                throw new IllegalArgumentException("Такая комната уже есть");
            }
            location.setName(normalizedName);
        }
        if (emoji != null && !emoji.isBlank()) {
            String normalizedEmoji = normalizeEmoji(emoji);
            validateEmoji(normalizedEmoji);
            location.setEmoji(normalizedEmoji);
        }
        return locationRepository.save(location);
    }

    /**
     * Удалить пустую локацию (без активных растений). REST API, issue #85.
     */
    @Transactional
    public void deleteEmptyLocation(Long userId, Long locationId) {
        Location location = getUserLocationOrThrow(userId, locationId);
        if (location.isDefaultLocation()) {
            throw new IllegalArgumentException("Cannot delete default location");
        }
        long plantCount = plantRepository.countByUserIdAndLocationIdAndArchivedAtIsNull(userId, locationId);
        if (plantCount > 0) {
            throw new IllegalArgumentException("Location has plants, provide targetLocationId");
        }
        locationRepository.delete(location);
        log.info("Deleted empty location {} for user {}", locationId, userId);
    }

    @Transactional
    public void deleteLocation(Long userId, Long locationId, Long targetLocationId) {
        Location locationToDelete = getUserLocationOrThrow(userId, locationId);

        if (locationToDelete.isDefaultLocation()) {
            throw new IllegalArgumentException("Дефолтную комнату удалить нельзя");
        }

        Location targetLocation = getUserLocationOrThrow(userId, targetLocationId);

        if (locationToDelete.getId().equals(targetLocation.getId())) {
            throw new IllegalArgumentException("Нельзя перенести растения в удаляемую комнату");
        }

        List<Plant> plants = plantRepository.findAllByUserIdAndLocationIdAndArchivedAtIsNullOrderByNameAsc(
                userId,
                locationId
        );

        for (Plant plant : plants) {
            plant.setLocation(targetLocation);
        }

        plantRepository.saveAll(plants);
        locationRepository.delete(locationToDelete);

        analyticsService.track(userId, "location_deleted", Map.of(
                "location_id", locationId,
                "target_location_id", targetLocationId,
                "moved_plants_count", plants.size()
        ));

        log.info(
                "Deleted location {} for user {}, moved {} plants to location {}",
                locationId,
                userId,
                plants.size(),
                targetLocationId
        );
    }

    private String normalizeName(String name) {
        return name == null ? "" : name.trim();
    }

    private String normalizeEmoji(String emoji) {
        if (emoji == null || emoji.trim().isEmpty()) {
            return DEFAULT_LOCATION_EMOJI;
        }

        return emoji.trim();
    }

    private void validateLocationName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Название комнаты не может быть пустым");
        }

        if (name.length() > 30) {
            throw new IllegalArgumentException("Название комнаты должно быть не длиннее 30 символов");
        }
    }

    private void validateEmoji(String emoji) {
        if (EmojiValidator.isValidEmoji(emoji)) {
            throw new IllegalArgumentException("Emoji должен быть одним emoji-символом");
        }
    }
}