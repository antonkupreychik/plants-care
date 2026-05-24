package com.plantcare.bot.controller.api.v1.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * Тело запроса POST /api/v1/care-events (issue #86).
 *
 * @param plantId     ID растения
 * @param type        тип ухода (WATER, SPRAY, FERTILIZE)
 * @param performedAt момент выполнения ухода (UTC Instant)
 * @param note        необязательная заметка (например, «добавил удобрение X»)
 * @param clientId    UUID от мобильного клиента для идемпотентности при retry;
 *                    null — если клиент не поддерживает идемпотентность
 */
public record CreateCareEventRequest(
        @NotNull Long plantId,
        @NotNull CareEventType type,
        @NotNull Instant performedAt,
        String note,
        String clientId
) {}
