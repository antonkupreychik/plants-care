package com.plantcare.bot.controller.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Request DTO для обновления локации (все поля опциональные).
 */
@Schema(description = "Request to update a location (all fields optional)")
public record LocationUpdateRequest(
        @Size(max = 30)
        @Schema(description = "New location name", example = "Bedroom")
        String name,

        @Size(max = 16)
        @Schema(description = "New location emoji", example = "🌱")
        String emoji
) {
}
