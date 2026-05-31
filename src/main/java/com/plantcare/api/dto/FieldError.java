package com.plantcare.api.dto;

/**
 * Поле с ошибкой валидации для тела ошибки REST API.
 */
public record FieldError(String field, String message) {
}