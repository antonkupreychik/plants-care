package com.plantcare.bot.controller.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request DTO для создания локации.
 */
@Schema(description = "Request to create a location")
public record LocationCreateRequest(
        @NotBlank
        @Size(max = 30)
        @Schema(description = "Location name", example = "Living room", requiredMode = Schema.RequiredMode.REQUIRED)
        String name,

        @Size(max = 16)
        @Schema(description = "Location emoji (optional)", example = "🌿")
        String emoji
) {
}
