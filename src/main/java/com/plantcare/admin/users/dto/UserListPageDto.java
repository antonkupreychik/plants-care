package com.plantcare.admin.users.dto;

import java.util.List;

public record UserListPageDto(
        List<UserListItemDto> items,
        int currentPage,
        int pageSize,
        long totalItems,
        int totalPages,
        String activeFilter,
        String search,
        String sort,
        String direction
) {
    public boolean hasPrev() { return currentPage > 1; }
    public boolean hasNext() { return currentPage < totalPages; }
    public int prevPage() { return currentPage - 1; }
    public int nextPage() { return currentPage + 1; }
    public String oppositeDirection() { return "asc".equals(direction) ? "desc" : "asc"; }
}
