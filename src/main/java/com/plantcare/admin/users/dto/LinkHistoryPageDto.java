package com.plantcare.admin.users.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Страница раздела «История привязок» (issue #93). Пагинация keyset'ом не нужна —
 * объём мизерный, поэтому обычный LIMIT/OFFSET, а наличие следующей страницы
 * определяется выборкой {@code pageSize + 1} строк (без второго COUNT-запроса).
 *
 * @param available {@code false}, если у юзера нет email — тогда истории быть не
 *                  может в принципе, и UI показывает объяснение вместо пустой таблицы
 */
public record LinkHistoryPageDto(
        List<LinkHistoryItemDto> items,
        int currentPage,
        int pageSize,
        boolean hasNext,
        LocalDate from,
        LocalDate to,
        boolean available
) {

    public static LinkHistoryPageDto unavailable(int pageSize, LocalDate from, LocalDate to) {
        return new LinkHistoryPageDto(List.of(), 1, pageSize, false, from, to, false);
    }

    public boolean hasPrev() {
        return currentPage > 1;
    }

    public int prevPage() {
        return currentPage - 1;
    }

    public int nextPage() {
        return currentPage + 1;
    }
}
