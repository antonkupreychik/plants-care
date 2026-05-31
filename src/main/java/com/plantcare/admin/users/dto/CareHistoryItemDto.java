package com.plantcare.admin.users.dto;

import java.time.Instant;

public record CareHistoryItemDto(
        long id,
        Instant doneAt,
        String taskType,
        boolean wasOnTime,
        long plantId,
        String plantName
) {}
