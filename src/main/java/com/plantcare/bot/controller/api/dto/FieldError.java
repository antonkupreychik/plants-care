package com.plantcare.bot.controller.api.dto;

/**
 * Поле с ошибкой валидации для тела ошибки REST API.
 */
public record FieldError(String field, String message) {
}