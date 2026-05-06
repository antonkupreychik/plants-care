package com.plantcare.bot.service;

import com.plantcare.bot.domain.Location;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.repository.LocationRepository;
import com.plantcare.bot.repository.PlantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class LocationService {

    private static final int MAX_LOCATIONS_PER_USER = 20;
    private static final String DEFAULT_LOCATION_NAME = "Мои растения";
    private static final String DEFAULT_LOCATION_EMOJI = "🪴";

    private final LocationRepository locationRepository;
    private final PlantRepository plantRepository;

    @Transactional(readOnly = true)
    public List<Location> getUserLocations(Long userId) {
        return locationRepository.findAllByUserIdOrderByDefaultLocationAscNameAsc(userId);
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

                    return locationRepository.save(location);
                });
    }

    @Transactional
    public Location createLocation(User user, String name, String emoji) {
        String normalizedName = normalizeName(name);
        String normalizedEmoji = normalizeEmoji(emoji);

        validateLocationName(normalizedName);
        validateEmoji(normalizedEmoji);

        long locationsCount = locationRepository.countByUserId(user.getId());

        if (locationsCount >= MAX_LOCATIONS_PER_USER) {
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

        return locationRepository.save(location);
    }

    @Transactional
    public Location changeEmoji(Long userId, Long locationId, String emoji) {
        String normalizedEmoji = normalizeEmoji(emoji);

        validateEmoji(normalizedEmoji);

        Location location = getUserLocationOrThrow(userId, locationId);
        location.setEmoji(normalizedEmoji);

        return locationRepository.save(location);
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

        /*
         * Важно:
         * location у Plant может быть LAZY.
         * Если вернуть plant наружу и потом вызвать plant.getLocation().getDisplayName(),
         * Hibernate может упасть с LazyInitializationException.
         *
         * Поэтому инициализируем location внутри транзакции.
         */
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

        plant.setLocation(targetLocation);

        Plant saved = plantRepository.save(plant);

        /*
         * Инициализируем новую location перед возвратом,
         * чтобы код выше мог безопасно вызвать plant.getLocation().getDisplayName().
         */
        if (saved.getLocation() != null) {
            saved.getLocation().getId();
            saved.getLocation().getName();
            saved.getLocation().getEmoji();
            saved.getLocation().isDefaultLocation();
        }

        log.info(
                "Moved plant {} of user {} to location {}",
                plantId,
                userId,
                targetLocationId
        );

        return saved;
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
        if (emoji == null || emoji.isBlank()) {
            throw new IllegalArgumentException("Emoji не может быть пустым");
        }

        if (emoji.length() > 16) {
            throw new IllegalArgumentException("Emoji должен быть одним символом");
        }
    }
}