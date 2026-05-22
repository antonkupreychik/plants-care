package com.plantcare.bot.controller.api.dto;

import java.util.List;

/**
 * Тело ошибки REST API: код, сообщение и опциональный список ошибок по полям.
 */
public record ApiError(String code, String message, List<FieldError> details) {
}