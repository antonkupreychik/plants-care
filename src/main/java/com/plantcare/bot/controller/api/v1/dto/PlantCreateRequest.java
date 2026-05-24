package com.plantcare.bot.controller.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request DTO для создания растения.
 */
@Schema(description = "Request to create a plant")
public record PlantCreateRequest(
        @NotBlank
        @Size(max = 100)
        @Schema(description = "Plant name", example = "Monstera", requiredMode = Schema.RequiredMode.REQUIRED)
        String name,

        @Size(max = 2000)
        @Schema(description = "Notes about the plant", example = "Keep soil moist")
        String notes,

        @Schema(description = "Location ID to place the plant in (uses default if omitted)", example = "2")
        Long locationId
) {
}
