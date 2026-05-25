package com.plantcare.admin.dashboard.dto;

import java.time.Instant;

public record StuckUserDto(
        long id,
        long telegramChatId,
        String username,
        String conversationState,
        Instant updatedAt,
        long hoursSinceUpdate
) {}
