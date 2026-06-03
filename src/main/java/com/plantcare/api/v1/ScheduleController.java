package com.plantcare.api.v1;

import com.plantcare.api.CurrentUserProvider;
import com.plantcare.api.generated.SchedulesApi;
import com.plantcare.api.generated.model.CareScheduleDto;
import com.plantcare.api.generated.model.CareScheduleUpdateRequest;
import com.plantcare.api.generated.model.SeasonalScheduleDto;
import com.plantcare.core.domain.enums.SeasonalOverride;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.service.PlantService;
import com.plantcare.core.service.PlantService.ScheduleView;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneOffset;
import java.util.List;

/**
 * REST API для чтения и редактирования расписаний ухода растения (issue #185).
 *
 * <p>Документация и mapping живут в сгенерированном {@link SchedulesApi} (см. openapi.yaml).
 */
@RestController
@RequiredArgsConstructor
public class ScheduleController implements SchedulesApi {

    private final PlantService plantService;
    private final CurrentUserProvider currentUserProvider;

    @Override
    public List<CareScheduleDto> listPlantSchedules(Long id) {
        Long userId = currentUserProvider.currentUserId();
        return plantService.getSchedules(userId, id).stream()
                .map(ScheduleController::toDto)
                .toList();
    }

    @Override
    public CareScheduleDto updatePlantSchedule(
            Long id, String type, CareScheduleUpdateRequest request) {
        Long userId = currentUserProvider.currentUserId();

        TaskType taskType;
        try {
            taskType = TaskType.valueOf(type);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Неизвестный тип задачи: " + type);
        }

        SeasonalOverride seasonalOverrideEnum = null;
        if (request.getSeasonalOverride() != null) {
            try {
                seasonalOverrideEnum = SeasonalOverride.valueOf(request.getSeasonalOverride());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown seasonalOverride: " + request.getSeasonalOverride());
            }
        }

        ScheduleView view = plantService.updateSchedule(
                userId, id, taskType,
                request.getEvery(), request.getAmountMl(), request.getEnabled(),
                seasonalOverrideEnum);
        return toDto(view);
    }

    private static CareScheduleDto toDto(ScheduleView v) {
        PlantService.SeasonalInfo s = v.seasonal();
        SeasonalScheduleDto seasonalDto = new SeasonalScheduleDto(
                s.active(), s.summerIntervalDays(), s.winterIntervalDays());

        CareScheduleDto dto = new CareScheduleDto(
                CareScheduleDto.TypeEnum.fromValue(v.type().name()),
                v.every(),
                CareScheduleDto.UnitEnum.DAY,
                v.enabled(),
                seasonalDto)
                .amountMl(v.amountMl());
        if (v.nextDueAt() != null) {
            dto.nextDueAt(v.nextDueAt().atOffset(ZoneOffset.UTC));
        }
        return dto;
    }
}
