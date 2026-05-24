package com.plantcare.bot.controller.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Request DTO для обновления растения (PATCH-семантика через PUT: обновляются только присутствующие поля).
 */
@Schema(description = "Request to update a plant (all fields optional)")
public record PlantUpdateRequest(
        @Size(max = 100)
        @Schema(description = "New plant name", example = "Monstera deliciosa")
        String name,

        @Size(max = 2000)
        @Schema(description = "Updated notes", example = "Water twice a week")
        String notes,

        @Schema(description = "New location ID", example = "3")
        Long locationId
) {
}
