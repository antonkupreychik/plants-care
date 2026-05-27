package com.plantcare.api.v1;

import com.plantcare.api.generated.LocationsApi;
import com.plantcare.api.generated.model.LocationCreateRequest;
import com.plantcare.api.generated.model.LocationDto;
import com.plantcare.api.generated.model.LocationUpdateRequest;
import com.plantcare.api.LocationNotEmptyException;
import com.plantcare.api.CurrentUserProvider;
import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.User;
import com.plantcare.core.service.LocationService;
import com.plantcare.core.service.UserService;
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
    private final CurrentUserProvider currentUserProvider;

    @Override
    public List<LocationDto> listLocations() {
        Long userId = currentUserProvider.currentUserId();
        return locationService.getUserLocations(userId).stream()
                .map(LocationController::toDto)
                .toList();
    }

    @Override
    public LocationDto getLocation(Long id) {
        Long userId = currentUserProvider.currentUserId();
        return toDto(getLocationOrThrow(userId, id));
    }

    @Override
    public LocationDto createLocation(LocationCreateRequest request) {
        User user = userService.getByIdOrThrow(currentUserProvider.currentUserId());
        Location location = locationService.createLocation(user, request.getName(), request.getEmoji());
        return toDto(location);
    }

    @Override
    public LocationDto updateLocation(Long id, LocationUpdateRequest request) {
        Long userId = currentUserProvider.currentUserId();
        try {
            Location updated = locationService.updateLocation(userId, id, request.getName(), request.getEmoji());
            return toDto(updated);
        } catch (IllegalArgumentException e) {
            throw new EntityNotFoundException("Location not found: " + id);
        }
    }

    @Override
    public void deleteLocation(Long id, Long targetLocationId) {
        Long userId = currentUserProvider.currentUserId();
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
