package com.plantcare.bot.controller.api.v1.dto;

import com.plantcare.bot.domain.Location;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Response DTO для локации.
 */
@Schema(description = "Location details")
public record LocationDto(
        @Schema(description = "Location ID", example = "1") Long id,
        @Schema(description = "Location name", example = "Living room") String name,
        @Schema(description = "Location emoji", example = "🪴") String emoji,
        @Schema(description = "Whether this is the default location") boolean defaultLocation,
        @Schema(description = "Creation timestamp (UTC)") Instant createdAt
) {

    public static LocationDto from(Location location) {
        return new LocationDto(
                location.getId(),
                location.getName(),
                location.getEmoji(),
                location.isDefaultLocation(),
                location.getCreatedAt() != null
                        ? location.getCreatedAt().toInstant(ZoneOffset.UTC)
                        : null
        );
    }
}
