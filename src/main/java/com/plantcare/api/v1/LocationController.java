package com.plantcare.api.v1;

import com.plantcare.api.generated.LocationsApi;
import com.plantcare.api.generated.model.LocationCreateRequest;
import com.plantcare.api.generated.model.LocationDto;
import com.plantcare.api.generated.model.LocationPauseRequest;
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
 * REST API для управления локациями (issue #85, #250).
 *
 * <p>Документация и mapping живут в сгенерированном {@link LocationsApi} (см. openapi.yaml).
 *
 * <p>Issue #250 добавляет: activate, pause, resume. DELETE теперь возвращает 409,
 * если в локации есть активные растения (без каскадного переноса).
 */
@RestController
@RequiredArgsConstructor
public class LocationController implements LocationsApi {

    private final LocationService locationService;
    private final UserService userService;
    private final CurrentUserProvider currentUserProvider;

    @Override
    public List<LocationDto> listLocations() {
        User user = currentUserProvider.currentUser();
        Long activeLocationId = user.getActiveLocation() != null ? user.getActiveLocation().getId() : null;
        return locationService.getUserLocations(user.getId()).stream()
                .map(loc -> toDto(loc, activeLocationId))
                .toList();
    }

    @Override
    public LocationDto getLocation(Long id) {
        User user = currentUserProvider.currentUser();
        Long activeLocationId = user.getActiveLocation() != null ? user.getActiveLocation().getId() : null;
        return toDto(getLocationOrThrow(user.getId(), id), activeLocationId);
    }

    @Override
    public LocationDto createLocation(LocationCreateRequest request) {
        User user = userService.getByIdOrThrow(currentUserProvider.currentUserId());
        Location location = locationService.createLocation(user, request.getName(), request.getEmoji());
        Long activeLocationId = user.getActiveLocation() != null ? user.getActiveLocation().getId() : null;
        return toDto(location, activeLocationId);
    }

    @Override
    public LocationDto updateLocation(Long id, LocationUpdateRequest request) {
        User user = currentUserProvider.currentUser();
        Long activeLocationId = user.getActiveLocation() != null ? user.getActiveLocation().getId() : null;
        try {
            Location updated = locationService.updateLocation(user.getId(), id, request.getName(), request.getEmoji());
            return toDto(updated, activeLocationId);
        } catch (IllegalArgumentException e) {
            throw new EntityNotFoundException("Location not found: " + id);
        }
    }

    @Override
    public void deleteLocation(Long id) {
        Long userId = currentUserProvider.currentUserId();
        getLocationOrThrow(userId, id);
        long plantCount = locationService.countPlantsInLocation(userId, id);

        if (plantCount > 0) {
            throw new LocationNotEmptyException(
                    "Локация содержит активные растения. Перенесите их перед удалением."
            );
        }

        locationService.deleteEmptyLocation(userId, id);
    }

    @Override
    public LocationDto activateLocation(Long id) {
        User user = userService.getByIdOrThrow(currentUserProvider.currentUserId());
        try {
            Location activated = locationService.setActiveLocation(user, id);
            return toDto(activated, activated.getId());
        } catch (IllegalArgumentException e) {
            throw new EntityNotFoundException("Location not found: " + id);
        }
    }

    @Override
    public LocationDto pauseLocation(Long id, LocationPauseRequest request) {
        User user = userService.getByIdOrThrow(currentUserProvider.currentUserId());
        Long activeLocationId = user.getActiveLocation() != null ? user.getActiveLocation().getId() : null;
        Location paused = locationService.pauseLocation(user, id, request.getDays());
        return toDto(paused, activeLocationId);
    }

    @Override
    public LocationDto resumeLocation(Long id) {
        User user = userService.getByIdOrThrow(currentUserProvider.currentUserId());
        Long activeLocationId = user.getActiveLocation() != null ? user.getActiveLocation().getId() : null;
        Location resumed = locationService.resumeLocation(user, id);
        return toDto(resumed, activeLocationId);
    }

    private Location getLocationOrThrow(Long userId, Long locationId) {
        try {
            return locationService.getUserLocationOrThrow(userId, locationId);
        } catch (IllegalArgumentException e) {
            throw new EntityNotFoundException("Location not found: " + locationId);
        }
    }

    private static LocationDto toDto(Location location, Long activeLocationId) {
        LocationDto dto = new LocationDto()
                .id(location.getId())
                .name(location.getName())
                .emoji(location.getEmoji())
                .defaultLocation(location.isDefaultLocation())
                .isActive(location.getId() != null && location.getId().equals(activeLocationId));
        if (location.getPausedUntil() != null) {
            dto.pausedUntil(location.getPausedUntil().atOffset(ZoneOffset.UTC));
        }
        if (location.getCreatedAt() != null) {
            dto.createdAt(location.getCreatedAt().atOffset(ZoneOffset.UTC));
        }
        return dto;
    }
}
