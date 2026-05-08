package com.plantcare.bot.admin.dashboard.dto;

import java.time.Instant;

public record RecentCareActionDto(
        long historyId,
        Instant doneAt,
        String taskType,
        boolean wasOnTime,
        long userId,
        String username,
        long plantId,
        String plantName
) {}
