package com.plantcare.bot.controller.api.v1.dto;

import com.plantcare.bot.domain.CareSchedule;

import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Задача ухода для эндпоинтов /today и /calendar (issue #86).
 *
 * @param scheduleId   ID расписания
 * @param plantId      ID растения
 * @param plantName    имя растения
 * @param taskType     тип задачи (TaskType.name()) — WATERING / MISTING / FERTILIZING / SOIL_CHECK
 * @param locationName имя локации растения
 * @param nextDueAt    ближайший дедлайн в UTC (Instant)
 */
public record TaskDto(
        Long scheduleId,
        Long plantId,
        String plantName,
        String taskType,
        String locationName,
        Instant nextDueAt
) {

    public static TaskDto from(CareSchedule schedule) {
        return new TaskDto(
                schedule.getId(),
                schedule.getPlant().getId(),
                schedule.getPlant().getName(),
                schedule.getTaskType().name(),
                schedule.getPlant().getLocation() != null
                        ? schedule.getPlant().getLocation().getName()
                        : null,
                schedule.getNextDueAt().toInstant(ZoneOffset.UTC)
        );
    }
}
