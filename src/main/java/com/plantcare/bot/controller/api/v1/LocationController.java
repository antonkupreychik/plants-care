package com.plantcare.bot.controller.api.v1;

import com.plantcare.bot.api.generated.LocationsApi;
import com.plantcare.bot.api.generated.model.LocationCreateRequest;
import com.plantcare.bot.api.generated.model.LocationDto;
import com.plantcare.bot.api.generated.model.LocationUpdateRequest;
import com.plantcare.bot.controller.api.LocationNotEmptyException;
import com.plantcare.bot.domain.Location;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.service.LocationService;
import com.plantcare.bot.service.UserService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneOffset;
import java.util.List;

/**
 * REST API для управления локациями (issue #85).
 *
 * <p>Документация и mapping живут в сгенерированном {@link LocationsApi} (см. openapi.yaml).
 */
@RestController
@RequiredArgsConstructor
public class LocationController implements LocationsApi {

    private final LocationService locationService;
    private final UserService userService;

    @Override
    public List<LocationDto> listLocations(Long userId) {
        return locationService.getUserLocations(userId).stream()
                .map(LocationController::toDto)
                .toList();
    }

    @Override
    public LocationDto getLocation(Long userId, Long id) {
        return toDto(getLocationOrThrow(userId, id));
    }

    @Override
    public LocationDto createLocation(Long userId, LocationCreateRequest request) {
        User user = userService.getByIdOrThrow(userId);
        Location location = locationService.createLocation(user, request.getName(), request.getEmoji());
        return toDto(location);
    }

    @Override
    public LocationDto updateLocation(Long userId, Long id, LocationUpdateRequest request) {
        try {
            Location updated = locationService.updateLocation(userId, id, request.getName(), request.getEmoji());
            return toDto(updated);
        } catch (IllegalArgumentException e) {
            throw new EntityNotFoundException("Location not found: " + id);
        }
    }

    @Override
    public void deleteLocation(Long userId, Long id, Long targetLocationId) {
        getLocationOrThrow(userId, id);
        long plantCount = locationService.countPlantsInLocation(userId, id);

        if (plantCount > 0) {
            if (targetLocationId == null) {
                throw new LocationNotEmptyException("Provide targetLocationId to move plants");
            }
            locationService.deleteLocation(userId, id, targetLocationId);
        } else {
            locationService.deleteEmptyLocation(userId, id);
        }
    }

    private Location getLocationOrThrow(Long userId, Long locationId) {
        try {
            return locationService.getUserLocationOrThrow(userId, locationId);
        } catch (IllegalArgumentException e) {
            throw new EntityNotFoundException("Location not found: " + locationId);
        }
    }

    private static LocationDto toDto(Location location) {
        LocationDto dto = new LocationDto()
                .id(location.getId())
                .name(location.getName())
                .emoji(location.getEmoji())
                .defaultLocation(location.isDefaultLocation());
        if (location.getCreatedAt() != null) {
            dto.createdAt(location.getCreatedAt().atOffset(ZoneOffset.UTC));
        }
        return dto;
    }
}
