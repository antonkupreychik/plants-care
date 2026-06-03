package com.plantcare.api.v1;

import com.plantcare.api.CurrentUserProvider;
import com.plantcare.api.generated.PlantsApi;
import com.plantcare.api.generated.model.DiagnosisIssueDto;
import com.plantcare.api.generated.model.PageResponsePlantDto;
import com.plantcare.api.generated.model.PlantCreateRequest;
import com.plantcare.api.generated.model.PlantDiagnosisDto;
import com.plantcare.api.generated.model.PlantDto;
import com.plantcare.api.generated.model.PlantFamilyMemberDto;
import com.plantcare.api.generated.model.PlantFamilyResponse;
import com.plantcare.api.generated.model.PlantHealthDto;
import com.plantcare.api.generated.model.PlantUpdateRequest;
import com.plantcare.api.generated.model.ScheduleInput;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.service.HealthScoreService;
import com.plantcare.core.service.PlantAcclimationService;
import com.plantcare.core.service.PlantDiagnosisReportService;
import com.plantcare.core.service.PlantService;
import com.plantcare.core.service.PlantService.ScheduleInputDto;
import com.plantcare.core.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * REST API для управления растениями.
 *
 * <p>Документация и mapping живут в сгенерированном {@link PlantsApi}
 * из OpenAPI-спеки.
 */
@RestController
@RequiredArgsConstructor
public class PlantController implements PlantsApi {

    private final PlantService plantService;
    private final PlantAcclimationService plantAcclimationService;
    private final UserService userService;
    private final CurrentUserProvider currentUserProvider;
    private final Clock clock;

    @Override
    public PageResponsePlantDto listPlants(Long locationId, Integer offset, Integer limit) {
        Long userId = currentUserProvider.currentUserId();

        int safeLimit = Math.min(Math.max(limit, 1), 100);
        int safeOffset = Math.max(offset, 0);

        List<PlantService.PlantWithHealth> plantsWithHealth =
                plantService.listPlantsWithHealth(userId, locationId, safeOffset, safeLimit);
        long total = plantService.countPlants(userId, locationId);

        List<PlantDto> items = plantsWithHealth.stream()
                .map(pwh -> toDto(pwh.plant(), pwh.health()))
                .toList();

        return new PageResponsePlantDto(items, (int) total, safeOffset, safeLimit);
    }

    @Override
    public PlantDto getPlant(Long id) {
        Long userId = currentUserProvider.currentUserId();
        PlantService.PlantWithHealth pwh = plantService.getPlantWithHealth(userId, id);

        return toDto(pwh.plant(), pwh.health());
    }

    @Override
    public PlantDto createPlant(PlantCreateRequest request) {
        User user = userService.getByIdOrThrow(currentUserProvider.currentUserId());

        List<ScheduleInputDto> schedules = toScheduleInputDtos(request.getSchedules());

        Plant plant = plantService.createPlant(
                user,
                request.getName(),
                request.getNotes(),
                request.getLocationId(),
                request.getSpeciesId(),
                request.getParentPlantId(),
                request.getAcquiredAt(),
                schedules
        );

        // Акклиматизация вызывается вне транзакции createPlant: она открывает свою транзакцию внутри.
        if (Boolean.TRUE.equals(request.getIsNew())) {
            plantAcclimationService.enable(user, plant.getId());
            // Перечитываем растение с обновлёнными полями акклиматизации.
            plant = plantService.getPlantOrThrow(user.getId(), plant.getId());
        }

        return toDto(plant);
    }

    private static List<ScheduleInputDto> toScheduleInputDtos(List<ScheduleInput> raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        return raw.stream()
                .map(s -> new ScheduleInputDto(
                        TaskType.valueOf(s.getType().getValue()),
                        s.getEvery(),
                        s.getAmountMl()
                ))
                .toList();
    }

    @Override
    public PlantDto updatePlant(Long id, PlantUpdateRequest request) {
        Long userId = currentUserProvider.currentUserId();

        Plant updated = plantService.updatePlant(
                userId,
                id,
                request.getName(),
                request.getNotes(),
                request.getLocationId(),
                request.getSpeciesId(),
                request.getClearSpecies()
        );

        return toDto(updated);
    }

    @Override
    public void deletePlant(Long id) {
        Long userId = currentUserProvider.currentUserId();
        plantService.archivePlant(userId, id);
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

        PlantDiagnosisReportService.DiagnosisReport report =
                plantService.getPlantDiagnosis(userId, id);

        List<DiagnosisIssueDto> issues = report.issues().stream()
                .map(issue -> new DiagnosisIssueDto(
                        issue.code(),
                        DiagnosisIssueDto.SeverityEnum.fromValue(issue.severity().name()),
                        issue.title()
                ))
                .toList();

        return new PlantDiagnosisDto(issues, report.recommendations());
    }

    @Override
    public PlantFamilyResponse getPlantFamily(Long id) {
        Long userId = currentUserProvider.currentUserId();

        PlantService.PlantFamily family = plantService.getPlantFamily(userId, id);

        List<PlantFamilyMemberDto> children = family.children().stream()
                .map(PlantController::toFamilyMemberDto)
                .toList();

        PlantFamilyResponse response = new PlantFamilyResponse(children);

        if (family.parent() != null) {
            response.parent(toFamilyMemberDto(family.parent()));
        }

        return response;
    }

    private PlantDto toDto(Plant plant) {
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

        dto.acquiredAt(plant.getAcquiredAt());

        LocalDateTime now = LocalDateTime.now(clock);
        boolean inAcclimation = plant.isInAcclimation(now);
        dto.inAcclimation(inAcclimation);

        if (plant.getAcclimationUntil() != null) {
            dto.acclimationUntil(plant.getAcclimationUntil().atOffset(ZoneOffset.UTC));
        }

        return dto;
    }

    private PlantDto toDto(Plant plant, HealthScoreService.HealthScore health) {
        PlantDto dto = toDto(plant);
        if (health != null) {
            dto.healthInsufficientData(health.insufficientData());
            if (!health.insufficientData()) {
                dto.healthScore(health.score())
                   .healthZone(PlantDto.HealthZoneEnum.fromValue(health.zone().name()));
            }
        }
        return dto;
    }

    private static PlantFamilyMemberDto toFamilyMemberDto(Plant plant) {
        return new PlantFamilyMemberDto(
                plant.getId(),
                plant.getName()
        );
    }
}