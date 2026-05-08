package com.plantcare.bot.admin.dashboard.dto;

import java.time.Duration;
import java.time.Instant;

/**
 * Healthy: tick был < 5 минут назад.
 * Stale: tick был ≥ 5 минут назад → красная метка, что-то случилось.
 * Unknown: метрики нет (шедулер ещё не подключён или другое имя метрики).
 */
public record SchedulerHealth(Status status, Instant lastTickAt, long secondsAgo) {

    public enum Status { HEALTHY, STALE, UNKNOWN }

    public static SchedulerHealth unknown() {
        return new SchedulerHealth(Status.UNKNOWN, null, -1);
    }

    public static SchedulerHealth from(Instant lastTickAt) {
        if (lastTickAt == null) {
            return unknown();
        }
        long seconds = Duration.between(lastTickAt, Instant.now()).getSeconds();
        Status s = seconds <= 300 ? Status.HEALTHY : Status.STALE;
        return new SchedulerHealth(s, lastTickAt, seconds);
    }

    public String humanAgo() {
        if (status == Status.UNKNOWN) return "—";
        if (secondsAgo < 60) return secondsAgo + " сек";
        if (secondsAgo < 3600) return (secondsAgo / 60) + " мин";
        return (secondsAgo / 3600) + " ч";
    }

    public boolean isHealthy() { return status == Status.HEALTHY; }
    public boolean isStale() { return status == Status.STALE; }
    public boolean isUnknown() { return status == Status.UNKNOWN; }
}
