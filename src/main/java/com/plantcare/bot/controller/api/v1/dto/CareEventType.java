package com.plantcare.bot.controller.api.v1.dto;

import com.plantcare.bot.domain.enums.TaskType;

/**
 * Тип ухода в публичном REST API (issue #86).
 *
 * <p>Намеренно не совпадает один-к-одному с внутренним {@link TaskType}:
 * API-контракт стабилен и не зависит от внутренней эволюции доменной модели.
 * SOIL_CHECK не экспонируется через REST API на текущем этапе.
 */
public enum CareEventType {

    WATER,
    SPRAY,
    FERTILIZE;

    public TaskType toTaskType() {
        return switch (this) {
            case WATER -> TaskType.WATERING;
            case SPRAY -> TaskType.MISTING;
            case FERTILIZE -> TaskType.FERTILIZING;
        };
    }
}
