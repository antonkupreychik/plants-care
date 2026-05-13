package com.plantcare.bot.domain;

import com.plantcare.bot.domain.enums.TaskType;

import java.time.LocalDateTime;

public record DigestTaskItem(
        Long scheduleId,
        Long plantId,
        String plantName,
        TaskType taskType,
        LocalDateTime scheduledAt
) {
}