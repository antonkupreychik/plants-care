package com.plantcare.bot.controller.api.v1;

import com.plantcare.bot.controller.api.dto.ApiErrorResponse;
import com.plantcare.bot.controller.api.v1.dto.LocationCreateRequest;
import com.plantcare.bot.controller.api.v1.dto.LocationDto;
import com.plantcare.bot.controller.api.v1.dto.LocationUpdateRequest;
import com.plantcare.bot.domain.Location;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.service.LocationService;
import com.plantcare.bot.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST API для управления локациями (issue #85).
 */
@Tag(name = "Locations", description = "Location CRUD")
@RestController
@RequestMapping("/api/v1/locations")
@RequiredArgsConstructor
public class LocationController {

    private final LocationService locationService;
    private final UserService userService;

    @Operation(summary = "List all locations for the authenticated user")
    @ApiResponse(responseCode = "200", description = "List returned")
    @GetMapping
    public List<LocationDto> listLocations(
            @Parameter(description = "Caller user ID", required = true)
            @RequestHeader("X-User-Id") Long userId
    ) {
        return locationService.getUserLocations(userId).stream()
                .map(LocationDto::from)
                .toList();
    }

    @Operation(summary = "Get a single location by ID")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Location found"),
            @ApiResponse(responseCode = "404", description = "Location not found")
    })
    @GetMapping("/{id}")
    public LocationDto getLocation(
            @Parameter(description = "Caller user ID", required = true)
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable Long id
    ) {
        Location location = getLocationOrThrow(userId, id);
        return LocationDto.from(location);
    }

    @Operation(summary = "Create a new location")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Location created"),
            @ApiResponse(responseCode = "400", description = "Validation failed or duplicate name")
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LocationDto createLocation(
            @Parameter(description = "Caller user ID", required = true)
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody LocationCreateRequest request
    ) {
        User user = userService.getByIdOrThrow(userId);
        Location location = locationService.createLocation(user, request.name(), request.emoji());
        return LocationDto.from(location);
    }

    @Operation(summary = "Update a location (name and/or emoji)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Location updated"),
            @ApiResponse(responseCode = "400", description = "Validation failed"),
            @ApiResponse(responseCode = "404", description = "Location not found")
    })
    @PutMapping("/{id}")
    public LocationDto updateLocation(
            @Parameter(description = "Caller user ID", required = true)
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable Long id,
            @Valid @RequestBody LocationUpdateRequest request
    ) {
        try {
            Location updated = locationService.updateLocation(userId, id, request.name(), request.emoji());
            return LocationDto.from(updated);
        } catch (IllegalArgumentException e) {
            throw new jakarta.persistence.EntityNotFoundException("Location not found: " + id);
        }
    }

    @Operation(summary = "Delete a location. If it has plants, provide targetLocationId to move them first.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Location deleted"),
            @ApiResponse(responseCode = "400", description = "Location has plants and no targetLocationId provided"),
            @ApiResponse(responseCode = "404", description = "Location not found")
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteLocation(
            @Parameter(description = "Caller user ID", required = true)
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable Long id,
            @Parameter(description = "Location to move plants into (required if location has plants)")
            @RequestParam(required = false) Long targetLocationId
    ) {
        getLocationOrThrow(userId, id);
        long plantCount = locationService.countPlantsInLocation(userId, id);

        if (plantCount > 0) {
            if (targetLocationId == null) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(ApiErrorResponse.of(
                                "LOCATION_NOT_EMPTY",
                                "Provide targetLocationId to move plants"));
            }
            locationService.deleteLocation(userId, id, targetLocationId);
        } else {
            locationService.deleteEmptyLocation(userId, id);
        }

        return ResponseEntity.noContent().build();
    }

    /**
     * Получить локацию пользователя или бросить EntityNotFoundException (→ 404).
     * LocationService бросает IllegalArgumentException (→ 400), поэтому для REST
     * выполняем явное преобразование.
     */
    private Location getLocationOrThrow(Long userId, Long locationId) {
        try {
            return locationService.getUserLocationOrThrow(userId, locationId);
        } catch (IllegalArgumentException e) {
            throw new jakarta.persistence.EntityNotFoundException("Location not found: " + locationId);
        }
    }
}
