package com.plantcare.bot.controller.api.v1.dto;

import com.plantcare.bot.domain.CareHistory;
import com.plantcare.bot.domain.enums.TaskType;

import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Ответ на регистрацию или получение события ухода (issue #86).
 */
public record CareEventResponse(
        Long id,
        Long plantId,
        String plantName,
        CareEventType type,
        Instant performedAt,
        String note,
        String clientId,
        boolean onTime
) {

    /**
     * Конвертирует доменный объект {@link CareHistory} в ответ REST API.
     * {@code doneAt} хранится в UTC как {@code LocalDateTime} — конвертируем
     * через {@link ZoneOffset#UTC} обратно в {@link Instant}.
     */
    public static CareEventResponse from(CareHistory history) {
        return new CareEventResponse(
                history.getId(),
                history.getPlant().getId(),
                history.getPlant().getName(),
                fromTaskType(history.getTaskType()),
                history.getDoneAt().toInstant(ZoneOffset.UTC),
                history.getNote(),
                history.getClientId(),
                history.isOnTime()
        );
    }

    private static CareEventType fromTaskType(TaskType taskType) {
        return switch (taskType) {
            case WATERING -> CareEventType.WATER;
            case MISTING -> CareEventType.SPRAY;
            case FERTILIZING -> CareEventType.FERTILIZE;
            // SOIL_CHECK не экспонируется через REST API. Записи с SOIL_CHECK
            // должны быть отфильтрованы до вызова CareEventResponse.from().
            case SOIL_CHECK -> throw new IllegalStateException(
                    "SOIL_CHECK cannot be represented as CareEventType, filter before mapping");
        };
    }
}
