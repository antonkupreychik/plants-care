package com.plantcare.bot.admin.users.dto;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

public record UserDetailDto(
        long id,
        long telegramChatId,
        String username,
        String timezone,
        LocalTime quietHoursStart,
        LocalTime quietHoursEnd,
        Instant pausedUntil,
        String conversationState,
        boolean blocked,
        Instant createdAt,
        List<UserPlantDto> plants,
        List<CareHistoryItemDto> careHistory,
        List<NotificationLogItemDto> notifications
) {
    public boolean isPausedNow() {
        return pausedUntil != null && pausedUntil.isAfter(Instant.now());
    }
    public boolean isStuck() {
        return conversationState != null && !"IDLE".equals(conversationState);
    }
}
