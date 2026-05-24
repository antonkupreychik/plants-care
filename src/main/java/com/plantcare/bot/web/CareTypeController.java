package com.plantcare.bot.web;

import com.plantcare.bot.domain.enums.TaskType;
import com.plantcare.bot.web.dto.CareTypeDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * Публичный REST API справочника типов ухода.
 *
 * Возвращает статический список, построенный из значений {@link com.plantcare.bot.domain.enums.TaskType}.
 * Аутентификация не требуется.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/care-types")
public class CareTypeController {

    /**
     * Возвращает все типы ухода с локализованными русскими названиями.
     *
     * Список фиксирован и соответствует значениям перечисления {@code TaskType}:
     * WATERING, MISTING, FERTILIZING, SOIL_CHECK.
     *
     * @return список {@link CareTypeDto} с полями {@code code} и {@code displayName}
     */
    @GetMapping
    public List<CareTypeDto> list() {
        log.debug("Care types list request");
        return Arrays.stream(TaskType.values())
                .map(t -> new CareTypeDto(t.name(), toDisplayName(t)))
                .toList();
    }

    private String toDisplayName(TaskType taskType) {
        return switch (taskType) {
            case WATERING -> "Полив";
            case MISTING -> "Опрыскивание";
            case FERTILIZING -> "Подкормка";
            case SOIL_CHECK -> "Проверка грунта";
        };
    }
}
