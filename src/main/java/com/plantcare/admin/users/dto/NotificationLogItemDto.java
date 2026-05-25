package com.plantcare.admin.users.dto;

import java.time.Instant;

public record NotificationLogItemDto(
        long id,
        Instant sentAt,
        String taskType,
        String notificationType,
        long plantId,
        String plantName
) {}
