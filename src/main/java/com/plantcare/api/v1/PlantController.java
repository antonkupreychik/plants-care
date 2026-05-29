package com.plantcare.api.v1;

import com.plantcare.api.generated.PlantsApi;
import com.plantcare.api.generated.model.DiagnosisIssueDto;
import com.plantcare.api.generated.model.PageResponsePlantDto;
import com.plantcare.api.generated.model.PlantCreateRequest;
import com.plantcare.api.generated.model.PlantDiagnosisDto;
import com.plantcare.api.generated.model.PlantDto;
import com.plantcare.api.generated.model.PlantHealthDto;
import com.plantcare.api.generated.model.PlantUpdateRequest;
import com.plantcare.api.CurrentUserProvider;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.service.HealthScoreService;
import com.plantcare.core.service.PlantDiagnosisReportService;
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
    private final CurrentUserProvider currentUserProvider;

    @Override
    public PageResponsePlantDto listPlants(Long locationId, Integer offset, Integer limit) {
        Long userId = currentUserProvider.currentUserId();
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        int safeOffset = Math.max(offset, 0);

        List<Plant> plants = plantService.listPlants(userId, locationId, safeOffset, safeLimit);
        long total = plantService.countPlants(userId, locationId);

        List<PlantDto> items = plants.stream().map(PlantController::toDto).toList();
        return new PageResponsePlantDto(items, (int) total, safeOffset, safeLimit);
    }

    @Override
    public PlantDto getPlant(Long id) {
        Long userId = currentUserProvider.currentUserId();
        return toDto(plantService.getPlantOrThrow(userId, id));
    }

    @Override
    public PlantDto createPlant(PlantCreateRequest request) {
        User user = userService.getByIdOrThrow(currentUserProvider.currentUserId());
        Plant plant = plantService.createPlant(
                user, request.getName(), request.getNotes(), request.getLocationId(), request.getSpeciesId());
        return toDto(plant);
    }

    @Override
    public PlantDto updatePlant(Long id, PlantUpdateRequest request) {
        Long userId = currentUserProvider.currentUserId();
        Plant updated = plantService.updatePlant(userId, id, request.getName(), request.getNotes(), request.getLocationId());
        return toDto(updated);
    }

    @Override
    public void deletePlant(Long id) {
        plantService.archivePlant(currentUserProvider.currentUserId(), id);
    }

    @Override
    public PlantHealthDto getPlantHealth(Long id) {
        Long userId = currentUserProvider.currentUserId();
        HealthScoreService.HealthScore health = plantService.getPlantHealth(userId, id);

        PlantHealthDto dto = new PlantHealthDto(health.insufficientData());
        if (!health.insufficientData()) {
            dto.score(health.score())
                    .zone(PlantHealthDto.ZoneEnum.fromValue(health.zone().name()));
        }
        return dto;
    }

    @Override
    public PlantDiagnosisDto getPlantDiagnosis(Long id) {
        Long userId = currentUserProvider.currentUserId();
        PlantDiagnosisReportService.DiagnosisReport report = plantService.getPlantDiagnosis(userId, id);

        List<DiagnosisIssueDto> issues = report.issues().stream()
                .map(issue -> new DiagnosisIssueDto(
                        issue.code(),
                        DiagnosisIssueDto.SeverityEnum.fromValue(issue.severity().name()),
                        issue.title()))
                .toList();

        return new PlantDiagnosisDto(issues, report.recommendations());
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
