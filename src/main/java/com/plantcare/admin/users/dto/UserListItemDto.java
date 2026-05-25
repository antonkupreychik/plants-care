package com.plantcare.admin.users.dto;

import java.time.Instant;

public record UserListItemDto(
        long id,
        long telegramChatId,
        String username,
        String timezone,
        long plantsCount,
        Instant lastActionAt,
        boolean blocked,
        boolean paused,
        boolean stuck,
        String conversationState) {

}

