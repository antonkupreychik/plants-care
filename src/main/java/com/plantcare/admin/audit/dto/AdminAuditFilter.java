package com.plantcare.admin.audit.dto;

import com.plantcare.admin.audit.AdminAuditAction;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Критерии выборки аудит-лога (issue #98). Любое поле {@code null} = «без фильтра».
 *
 * <p>Границы дат хранятся уже как {@link Instant} в UTC — в БД
 * {@code occurred_at} это {@code TIMESTAMPTZ}, и сравнивать его с локальной
 * датой нельзя. Интервал полуоткрытый: {@code [from, to)}.
 */
public record AdminAuditFilter(
        String adminUsername,
        AdminAuditAction action,
        String targetType,
        String targetId,
        Instant from,
        Instant to
) {

    public static AdminAuditFilter empty() {
        return new AdminAuditFilter(null, null, null, null, null, null);
    }

    /**
     * Собирает фильтр из query-параметров страницы: пустые строки схлопываются
     * в {@code null}, даты трактуются как календарные дни в UTC, причём
     * {@code toDate} включается целиком (граница сдвигается на следующий день).
     */
    public static AdminAuditFilter of(String adminUsername, String action, String targetType,
                                      LocalDate fromDate, LocalDate toDate) {
        return new AdminAuditFilter(
                blankToNull(adminUsername),
                AdminAuditAction.parseOrNull(action),
                blankToNull(targetType),
                null,
                fromDate == null ? null : fromDate.atStartOfDay(ZoneOffset.UTC).toInstant(),
                toDate == null ? null : toDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        );
    }

    /** Фильтр «всё по одному объекту» — для секции истории на карточке. */
    public static AdminAuditFilter forTarget(String targetType, String targetId) {
        return new AdminAuditFilter(null, null, targetType, targetId, null, null);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
