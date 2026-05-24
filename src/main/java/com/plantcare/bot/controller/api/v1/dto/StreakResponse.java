package com.plantcare.bot.controller.api.v1.dto;

/**
 * Ответ эндпоинта GET /api/v1/stats/streak (issue #86).
 *
 * @param plantId ID растения
 * @param streak  количество последовательных выполнений без пропусков (on-time подряд)
 */
public record StreakResponse(Long plantId, int streak) {}
