package com.plantcare.bot.admin.users.dto;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

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
        List<NotificationLogItemDto> notifications,
        /** Коды включённых feature flag'ов (issue #78). Сюда попадают и
         *  «известные» и ad-hoc. UI рендерит их в отдельной секции. */
        Set<String> enabledFeatureFlags
) {
    public boolean isPausedNow() {
        return pausedUntil != null && pausedUntil.isAfter(Instant.now());
    }
    public boolean isStuck() {
        return conversationState != null && !"IDLE".equals(conversationState);
    }
}
