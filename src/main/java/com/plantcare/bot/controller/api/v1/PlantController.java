package com.plantcare.bot.controller.api.v1;

import com.plantcare.bot.api.generated.PlantsApi;
import com.plantcare.bot.api.generated.model.PageResponsePlantDto;
import com.plantcare.bot.api.generated.model.PlantCreateRequest;
import com.plantcare.bot.api.generated.model.PlantDto;
import com.plantcare.bot.api.generated.model.PlantUpdateRequest;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.service.PlantService;
import com.plantcare.core.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneOffset;
import java.util.List;

/**
 * REST API для управления растениями (issue #85).
 *
 * <p>Документация и mapping живут в сгенерированном {@link PlantsApi} (см. openapi.yaml).
 */
@RestController
@RequiredArgsConstructor
public class PlantController implements PlantsApi {

    private final PlantService plantService;
    private final UserService userService;

    @Override
    public PageResponsePlantDto listPlants(Long userId, Long locationId, Integer offset, Integer limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        int safeOffset = Math.max(offset, 0);

        List<Plant> plants = plantService.listPlants(userId, locationId, safeOffset, safeLimit);
        long total = plantService.countPlants(userId, locationId);

        List<PlantDto> items = plants.stream().map(PlantController::toDto).toList();
        return new PageResponsePlantDto(items, (int) total, safeOffset, safeLimit);
    }

    @Override
    public PlantDto getPlant(Long userId, Long id) {
        return toDto(plantService.getPlantOrThrow(userId, id));
    }

    @Override
    public PlantDto createPlant(Long userId, PlantCreateRequest request) {
        User user = userService.getByIdOrThrow(userId);
        Plant plant = plantService.createPlant(user, request.getName(), request.getNotes(), request.getLocationId());
        return toDto(plant);
    }

    @Override
    public PlantDto updatePlant(Long userId, Long id, PlantUpdateRequest request) {
        Plant updated = plantService.updatePlant(userId, id, request.getName(), request.getNotes(), request.getLocationId());
        return toDto(updated);
    }

    @Override
    public void deletePlant(Long userId, Long id) {
        plantService.archivePlant(userId, id);
    }

    private static PlantDto toDto(Plant plant) {
        PlantDto dto = new PlantDto(plant.getId(), plant.getName(), plant.isArchived())
                .notes(plant.getNotes())
                .photoFileId(plant.getPhotoFileId());

        if (plant.getLocation() != null) {
            dto.locationId(plant.getLocation().getId())
                    .locationName(plant.getLocation().getName());
        }
        if (plant.getSpecies() != null) {
            dto.speciesId(plant.getSpecies().getId())
                    .speciesName(plant.getSpecies().getName());
        }
        if (plant.getCreatedAt() != null) {
            dto.createdAt(plant.getCreatedAt().atOffset(ZoneOffset.UTC));
        }
        return dto;
    }
}
