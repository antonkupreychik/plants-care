package com.plantcare.admin.dashboard.dto;

import java.util.List;

/** Snapshot всех данных дашборда — то что видит шаблон. */
public record DashboardDto(
        long activeUsers,
        long totalPlants,
        long careActionsToday,
        long dau24h,
        SchedulerHealth schedulerHealth,
        List<StuckUserDto> stuckUsers,
        List<RecentCareActionDto> recentActions,
        long renderTimeMillis
) {}
