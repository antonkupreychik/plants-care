package com.plantcare.admin.scheduler.dto;

import com.plantcare.admin.dashboard.dto.SchedulerHealth;

import java.util.List;

/**
 * Snapshot всех данных страницы /admin/scheduler (issue #59).
 *
 * @param health           статус последнего тика (healthy/stale/unknown)
 * @param schedulesInQueue сколько расписаний сейчас due (next_due_at ≤ now, active)
 * @param sentLast24h      сколько уведомлений ушло за последние 24 часа
 * @param errorsLast24h    placeholder; реальный счёт появится в Phase 2 — пока {@code -1}
 * @param sendsByHour      24 точки за последние сутки (oldest first), час и кол-во
 * @param triggerCooldownSecondsLeft сколько ещё секунд кнопка триггера должна быть заблокирована;
 *                                    {@code 0}, если можно нажимать
 * @param queue            детальная очередь уведомлений: due now + upcoming в ближайшие 24h
 * @param queueTruncated   {@code true}, если в БД больше записей, чем влезло в {@code queue}
 */
public record SchedulerPageDto(
        SchedulerHealth health,
        long schedulesInQueue,
        long sentLast24h,
        long errorsLast24h,
        List<HourBucket> sendsByHour,
        int triggerCooldownSecondsLeft,
        List<QueuedNotificationDto> queue,
        boolean queueTruncated
) {

    /**
     * Один час на графике/в таблице отправок.
     * @param hourLabel «00:00», «01:00», …, «23:00»
     * @param count     сколько уведомлений ушло в этот час
     */
    public record HourBucket(String hourLabel, long count) {}

    /** {@code true}, если "Ошибок за 24h" пока не подсчитывается (показываем «—»). */
    public boolean isErrorsPlaceholder() {
        return errorsLast24h < 0;
    }
}
