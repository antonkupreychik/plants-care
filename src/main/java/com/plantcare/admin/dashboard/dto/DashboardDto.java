package com.plantcare.admin.dashboard.dto;

import com.plantcare.admin.notifications.dto.ChannelHealthDto;

import java.util.List;

/**
 * Snapshot всех данных дашборда — то что видит шаблон.
 *
 * @param alertingChannels каналы уведомлений с error rate выше порога (issue #95);
 *                         пустой список — всё в порядке
 */
public record DashboardDto(
        long activeUsers,
        long totalPlants,
        long careActionsToday,
        long dau24h,
        SchedulerHealth schedulerHealth,
        List<StuckUserDto> stuckUsers,
        List<RecentCareActionDto> recentActions,
        List<ChannelHealthDto> alertingChannels,
        long renderTimeMillis
) {}
