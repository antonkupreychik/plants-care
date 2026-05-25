package com.plantcare.core.domain;

import com.plantcare.core.domain.enums.TaskType;

import java.time.LocalDateTime;

public record DigestTaskItem(
        Long scheduleId,
        Long plantId,
        String plantName,
        TaskType taskType,
        LocalDateTime scheduledAt
) {
}