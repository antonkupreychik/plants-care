package com.plantcare.bot.controller.api.v1.dto;

import java.util.List;

/**
 * Ответ эндпоинта GET /api/v1/today (issue #86).
 *
 * @param tasks задачи ухода, актуальные до конца сегодняшнего дня в TZ пользователя
 * @param count количество задач (дублирует tasks.size() для удобства клиента)
 */
public record TodayResponse(List<TaskDto> tasks, int count) {}
