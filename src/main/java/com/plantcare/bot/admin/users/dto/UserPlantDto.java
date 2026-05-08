package com.plantcare.bot.admin.users.dto;

import java.time.Instant;

public record UserPlantDto(
        long id,
        String name,
        String speciesName,
        String roomName,
        Instant nextWateringDueAt,
        Instant archivedAt
) {}
