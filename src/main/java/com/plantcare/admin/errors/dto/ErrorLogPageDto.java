package com.plantcare.admin.errors.dto;

import java.util.List;

/**
 * Страница списка ошибок (issue #97): сами строки + навигация.
 *
 * @param items      строки текущей страницы
 * @param page       номер страницы, начиная с 1
 * @param pageSize   размер страницы
 * @param totalItems сколько всего строк подходит под фильтр
 * @param totalPages сколько всего страниц (минимум 1)
 */
public record ErrorLogPageDto(
        List<ErrorLogItemDto> items,
        int page,
        int pageSize,
        long totalItems,
        int totalPages
) {

    public boolean hasPrevious() {
        return page > 1;
    }

    public boolean hasNext() {
        return page < totalPages;
    }
}
