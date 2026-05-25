package com.plantcare.api.dto;

import java.util.List;

/**
 * Единый формат ответа REST API при ошибке: {@code { "error": { ... } }}.
 */
public record ApiErrorResponse(ApiError error) {

    public static ApiErrorResponse of(String code, String message) {
        return new ApiErrorResponse(new ApiError(code, message, null));
    }

    public static ApiErrorResponse of(String code, String message, List<FieldError> details) {
        return new ApiErrorResponse(new ApiError(code, message, details));
    }
}
