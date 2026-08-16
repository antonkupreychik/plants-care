package com.plantcare.admin.audit.dto;

import java.util.List;

/**
 * Страница аудит-лога для шаблона (issue #98).
 *
 * <p>Форма повторяет {@code UserListPageDto}: нумерация страниц с единицы,
 * навигация считается в самом DTO, чтобы Thymeleaf не занимался арифметикой.
 */
public record AdminAuditPageDto(
        List<AdminAuditEntryDto> items,
        int currentPage,
        int pageSize,
        long totalItems,
        int totalPages
) {
    public boolean hasPrev() { return currentPage > 1; }
    public boolean hasNext() { return currentPage < totalPages; }
    public int prevPage() { return currentPage - 1; }
    public int nextPage() { return currentPage + 1; }
}
