package com.plantcare.bot.controller.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Pagination wrapper для списковых ответов REST API.
 */
@Schema(description = "Paginated response wrapper")
public record PageResponse<T>(
        @Schema(description = "Items on the current page") List<T> items,
        @Schema(description = "Total number of matching items", example = "42") int total,
        @Schema(description = "Current offset", example = "0") int offset,
        @Schema(description = "Page size limit", example = "20") int limit
) {
}
