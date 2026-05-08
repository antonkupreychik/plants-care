package com.plantcare.bot.admin.dashboard;

import com.plantcare.bot.admin.config.AdminProperties;
import com.plantcare.bot.admin.dashboard.dto.DashboardDto;
import com.plantcare.bot.admin.dashboard.health.SchedulerHealthProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Собирает все данные дашборда параллельно. Цель — общий рендеринг <500ms.
 * Каждый запрос выполняется в своём CompletableFuture на adminQueryExecutor.
 */
@Service
@Slf4j
public class AdminDashboardService {

    private final AdminDashboardRepository repository;
    private final SchedulerHealthProvider schedulerHealth;
    private final ExecutorService executor;
    private final ZoneId timezone;

    public AdminDashboardService(
            AdminDashboardRepository repository,
            SchedulerHealthProvider schedulerHealth,
            @Qualifier("adminQueryExecutor") ExecutorService executor,
            AdminProperties props
    ) {
        this.repository = repository;
        this.schedulerHealth = schedulerHealth;
        this.executor = executor;
        this.timezone = resolveTimezone(props.getDashboard().getTimezone());
    }

    private static ZoneId resolveTimezone(String configured) {
        if (configured == null || configured.isBlank()) return ZoneId.systemDefault();
        try {
            return ZoneId.of(configured);
        } catch (Exception e) {
            return ZoneId.systemDefault();
        }
    }

    public DashboardDto loadDashboard() {
        long start = System.currentTimeMillis();

        CompletableFuture<Long> activeUsersF = CompletableFuture
                .supplyAsync(repository::countActiveUsers, executor);
        CompletableFuture<Long> totalPlantsF = CompletableFuture
                .supplyAsync(repository::countNonArchivedPlants, executor);
        CompletableFuture<Long> careTodayF = CompletableFuture
                .supplyAsync(() -> repository.countCareActionsToday(timezone), executor);
        CompletableFuture<Long> dau24hF = CompletableFuture
                .supplyAsync(repository::countDistinctActiveUsersLast24h, executor);
        var stuckF = CompletableFuture
                .supplyAsync(() -> repository.findTopStuckUsers(5), executor);
        var recentF = CompletableFuture
                .supplyAsync(() -> repository.findRecentCareActions(10), executor);

        CompletableFuture.allOf(activeUsersF, totalPlantsF, careTodayF, dau24hF, stuckF, recentF).join();

        long elapsed = System.currentTimeMillis() - start;
        if (elapsed > 500) {
            log.warn("Dashboard render exceeded 500ms target: {}ms", elapsed);
        }

        return new DashboardDto(
                activeUsersF.join(),
                totalPlantsF.join(),
                careTodayF.join(),
                dau24hF.join(),
                schedulerHealth.currentHealth(),
                stuckF.join(),
                recentF.join(),
                elapsed);
    }
}

