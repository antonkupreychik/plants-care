package com.plantcare.api.v1;

import com.plantcare.api.CurrentUserProvider;
import com.plantcare.api.generated.PlantTemplatesApi;
import com.plantcare.api.generated.model.PlantDto;
import com.plantcare.api.generated.model.PlantTemplateCareRuleDto;
import com.plantcare.api.generated.model.PlantTemplateCreateRequest;
import com.plantcare.api.generated.model.PlantTemplateDto;
import com.plantcare.api.generated.model.PlantTemplateInstantiateRequest;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.PlantTemplate;
import com.plantcare.core.domain.PlantTemplateCareRule;
import com.plantcare.core.domain.User;
import com.plantcare.core.service.PlantTemplateService;
import com.plantcare.core.service.UserService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneOffset;
import java.util.List;

/**
 * REST API для управления шаблонами растений (issue #254).
 *
 * <p>Документация и mapping живут в сгенерированном {@link PlantTemplatesApi}
 * из OpenAPI-спеки.
 *
 * <p>Форма «создать растение из шаблона»: отдельный эндпоинт
 * {@code POST /plant-templates/{id}/instantiate} — явная операция, семантически
 * отдельная от обычного {@code POST /plants}.
 */
@RestController
@RequiredArgsConstructor
public class PlantTemplateController implements PlantTemplatesApi {

    private final PlantTemplateService plantTemplateService;
    private final UserService userService;
    private final CurrentUserProvider currentUserProvider;

    @Override
    public List<PlantTemplateDto> listPlantTemplates() {
        Long userId = currentUserProvider.currentUserId();
        return plantTemplateService.getUserTemplates(userId).stream()
                .map(PlantTemplateController::toDto)
                .toList();
    }

    @Override
    public PlantTemplateDto createPlantTemplate(PlantTemplateCreateRequest request) {
        User user = userService.getByIdOrThrow(currentUserProvider.currentUserId());

        PlantTemplate template;
        if (request.getFromPlantId() != null) {
            template = plantTemplateService.saveFromPlant(user, request.getFromPlantId(), request.getName());
        } else {
            template = plantTemplateService.createEmpty(user, request.getName());
        }

        return toDto(template);
    }

    @Override
    public void deletePlantTemplate(Long id) {
        User user = userService.getByIdOrThrow(currentUserProvider.currentUserId());
        // deleteTemplate throws IllegalArgumentException ("Шаблон не найден") when not found;
        // ApiExceptionHandler maps it to 400. We rethrow as EntityNotFoundException for 404.
        plantTemplateService.getTemplate(user.getId(), id)
                .orElseThrow(() -> new EntityNotFoundException("Plant template not found: " + id));
        plantTemplateService.deleteTemplate(user, id);
    }

    @Override
    public PlantDto instantiatePlantTemplate(Long id, PlantTemplateInstantiateRequest request) {
        User user = userService.getByIdOrThrow(currentUserProvider.currentUserId());
        // Validate template exists and belongs to user before instantiating
        plantTemplateService.getTemplate(user.getId(), id)
                .orElseThrow(() -> new EntityNotFoundException("Plant template not found: " + id));

        Plant plant = plantTemplateService.createPlantFromTemplate(user, id, request.getName());
        return toPlantDto(plant);
    }

    // ─────────────────────────────────────────────────────────────────
    // Mappers
    // ─────────────────────────────────────────────────────────────────

    private static PlantTemplateDto toDto(PlantTemplate template) {
        List<PlantTemplateCareRuleDto> rules = template.getCareRules().stream()
                .map(PlantTemplateController::toCareRuleDto)
                .toList();

        PlantTemplateDto dto = new PlantTemplateDto(
                template.getId(),
                template.getName(),
                rules,
                template.getCreatedAt() != null
                        ? template.getCreatedAt().atOffset(ZoneOffset.UTC)
                        : null
        );
        return dto;
    }

    private static PlantTemplateCareRuleDto toCareRuleDto(PlantTemplateCareRule rule) {
        return new PlantTemplateCareRuleDto(
                PlantTemplateCareRuleDto.CareTypeEnum.fromValue(rule.getCareType().name()),
                rule.getIntervalDays()
        );
    }

    private static PlantDto toPlantDto(Plant plant) {
        PlantDto dto = new PlantDto(plant.getId(), plant.getName(), plant.isArchived())
                .notes(plant.getNotes());
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
