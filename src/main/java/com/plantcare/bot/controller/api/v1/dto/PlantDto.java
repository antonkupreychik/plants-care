package com.plantcare.bot.controller.api.v1.dto;

import com.plantcare.bot.domain.Plant;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Response DTO для растения.
 */
@Schema(description = "Plant details")
public record PlantDto(
        @Schema(description = "Plant ID", example = "1") Long id,
        @Schema(description = "Plant name", example = "Monstera") String name,
        @Schema(description = "Notes", example = "Keep moist") String notes,
        @Schema(description = "Telegram photo file_id") String photoFileId,
        @Schema(description = "Location ID", example = "2") Long locationId,
        @Schema(description = "Location name", example = "Living room") String locationName,
        @Schema(description = "Species ID", example = "5") Long speciesId,
        @Schema(description = "Species name", example = "Monstera deliciosa") String speciesName,
        @Schema(description = "Whether the plant is archived") boolean archived,
        @Schema(description = "Creation timestamp (UTC)") Instant createdAt
) {

    public static PlantDto from(Plant plant) {
        return new PlantDto(
                plant.getId(),
                plant.getName(),
                plant.getNotes(),
                plant.getPhotoFileId(),
                plant.getLocation() != null ? plant.getLocation().getId() : null,
                plant.getLocation() != null ? plant.getLocation().getName() : null,
                plant.getSpecies() != null ? plant.getSpecies().getId() : null,
                plant.getSpecies() != null ? plant.getSpecies().getName() : null,
                plant.isArchived(),
                plant.getCreatedAt() != null
                        ? plant.getCreatedAt().toInstant(ZoneOffset.UTC)
                        : null
        );
    }
}
