package com.plantcare.api.web;

import com.plantcare.api.generated.CareTypesApi;
import com.plantcare.api.generated.model.CareTypeDto;
import com.plantcare.core.domain.enums.TaskType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * Публичный REST API справочника типов ухода.
 *
 * <p>Документация и mapping живут в сгенерированном {@link CareTypesApi}.
 */
@Slf4j
@RestController
public class CareTypeController implements CareTypesApi {

    @Override
    public List<CareTypeDto> listCareTypes() {
        log.debug("Care types list request");
        return Arrays.stream(TaskType.values())
                .map(t -> new CareTypeDto(toCodeEnum(t), toDisplayName(t)))
                .toList();
    }

    private static CareTypeDto.CodeEnum toCodeEnum(TaskType taskType) {
        return switch (taskType) {
            case WATERING -> CareTypeDto.CodeEnum.WATERING;
            case MISTING -> CareTypeDto.CodeEnum.MISTING;
            case FERTILIZING -> CareTypeDto.CodeEnum.FERTILIZING;
            case SOIL_CHECK -> CareTypeDto.CodeEnum.SOIL_CHECK;
        };
    }

    private static String toDisplayName(TaskType taskType) {
        return switch (taskType) {
            case WATERING -> "Полив";
            case MISTING -> "Опрыскивание";
            case FERTILIZING -> "Подкормка";
            case SOIL_CHECK -> "Проверка грунта";
        };
    }
}
