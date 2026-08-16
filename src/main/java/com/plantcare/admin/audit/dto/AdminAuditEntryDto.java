package com.plantcare.admin.audit.dto;

import com.plantcare.admin.audit.AdminAuditAction;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Одна запись аудит-лога для отображения (issue #98).
 *
 * @param action    сырое значение из БД — может не совпадать ни с одной
 *                  константой {@link AdminAuditAction}, если запись оставила
 *                  более старая/новая версия приложения
 * @param details   JSON-строка как она лежит в JSONB (уже отформатирована
 *                  сервисом для читаемости), либо {@code null}
 */
public record AdminAuditEntryDto(
        Long id,
        Instant occurredAt,
        String adminUsername,
        String action,
        String targetType,
        String targetId,
        String details,
        String requestIp
) {

    private static final DateTimeFormatter DISPLAY =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneOffset.UTC);

    /**
     * Время для показа, в UTC. Форматируем здесь, а не в шаблоне: Thymeleaf'овский
     * {@code #temporals.format} не умеет {@link Instant} — у него нет ни даты,
     * ни зоны, и паттерн с {@code dd.MM} на нём падает.
     */
    public String occurredAtDisplay() {
        return DISPLAY.format(occurredAt);
    }

    /** Русская подпись действия; для незнакомого кода — сам код. */
    public String actionLabel() {
        AdminAuditAction known = AdminAuditAction.parseOrNull(action);
        return known == null ? action : known.getLabel();
    }

    public boolean hasDetails() {
        return details != null && !details.isBlank();
    }
}
