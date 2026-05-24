package com.plantcare.bot.controller.api.v1;

import com.plantcare.bot.controller.api.v1.dto.PageResponse;
import com.plantcare.bot.controller.api.v1.dto.PlantCreateRequest;
import com.plantcare.bot.controller.api.v1.dto.PlantDto;
import com.plantcare.bot.controller.api.v1.dto.PlantUpdateRequest;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.service.PlantService;
import com.plantcare.bot.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
 * REST API для управления растениями (issue #85).
 */
@Tag(name = "Plants", description = "Plant CRUD")
@RestController
@RequestMapping("/api/v1/plants")
@RequiredArgsConstructor
public class PlantController {

    private final PlantService plantService;
    private final UserService userService;

    @Operation(summary = "List plants for the authenticated user")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List returned"),
            @ApiResponse(responseCode = "400", description = "Invalid query parameters")
    })
    @GetMapping
    public PageResponse<PlantDto> listPlants(
            @Parameter(description = "Caller user ID", required = true)
            @RequestHeader("X-User-Id") Long userId,
            @Parameter(description = "Filter by location ID")
            @RequestParam(required = false) Long locationId,
            @Parameter(description = "Pagination offset", example = "0")
            @RequestParam(defaultValue = "0") int offset,
            @Parameter(description = "Page size (max 100)", example = "20")
            @RequestParam(defaultValue = "20") int limit
    ) {
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        int safeOffset = Math.max(offset, 0);

        List<Plant> plants = plantService.listPlants(userId, locationId, safeOffset, safeLimit);
        long total = plantService.countPlants(userId, locationId);

        List<PlantDto> items = plants.stream().map(PlantDto::from).toList();
        return new PageResponse<>(items, (int) total, safeOffset, safeLimit);
    }

    @Operation(summary = "Get a single plant by ID")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Plant found"),
            @ApiResponse(responseCode = "403", description = "Plant belongs to another user"),
            @ApiResponse(responseCode = "404", description = "Plant not found")
    })
    @GetMapping("/{id}")
    public PlantDto getPlant(
            @Parameter(description = "Caller user ID", required = true)
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable Long id
    ) {
        Plant plant = plantService.getPlantOrThrow(userId, id);
        return PlantDto.from(plant);
    }

    @Operation(summary = "Create a new plant")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Plant created"),
            @ApiResponse(responseCode = "400", description = "Validation failed")
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PlantDto createPlant(
            @Parameter(description = "Caller user ID", required = true)
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody PlantCreateRequest request
    ) {
        User user = userService.getByIdOrThrow(userId);
        Plant plant = plantService.createPlant(user, request.name(), request.notes(), request.locationId());
        return PlantDto.from(plant);
    }

    @Operation(summary = "Update a plant (PATCH-style: only provided fields are updated)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Plant updated"),
            @ApiResponse(responseCode = "400", description = "Validation failed"),
            @ApiResponse(responseCode = "403", description = "Plant belongs to another user"),
            @ApiResponse(responseCode = "404", description = "Plant not found")
    })
    @PutMapping("/{id}")
    public PlantDto updatePlant(
            @Parameter(description = "Caller user ID", required = true)
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable Long id,
            @Valid @RequestBody PlantUpdateRequest request
    ) {
        Plant updated = plantService.updatePlant(userId, id, request.name(), request.notes(), request.locationId());
        return PlantDto.from(updated);
    }

    @Operation(summary = "Soft-delete (archive) a plant")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Plant archived"),
            @ApiResponse(responseCode = "403", description = "Plant belongs to another user"),
            @ApiResponse(responseCode = "404", description = "Plant not found")
    })
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePlant(
            @Parameter(description = "Caller user ID", required = true)
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable Long id
    ) {
        plantService.archivePlant(userId, id);
    }
}
