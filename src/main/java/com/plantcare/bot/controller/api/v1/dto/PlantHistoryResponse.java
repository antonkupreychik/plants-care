package com.plantcare.bot.controller.api.v1.dto;

import java.util.List;

/**
 * Страница истории ухода за растением с метаданными пагинации (issue #86).
 *
 * @param items  элементы текущей страницы
 * @param total  суммарное количество активных (не отменённых) записей
 * @param limit  размер страницы, который был применён
 * @param offset смещение, которое было применено
 */
public record PlantHistoryResponse(
        List<CareEventResponse> items,
        long total,
        int limit,
        int offset
) {}
