package com.plantcare.core.domain.enums;

/**
 * Роль субъекта (пользователя) на объекте (локации) в модели совместного ухода (issue #77).
 *
 * <ul>
 *   <li>{@code OWNER}  — владелец локации (создатель). В таблицу {@code location_access}
 *       не пишется: владение определяется через {@code locations.user_id}.</li>
 *   <li>{@code CARETAKER} — приглашённый ухаживающий: видит растения и задачи локации,
 *       может отмечать выполнение ухода, получает уведомления.</li>
 * </ul>
 *
 * Enum хранится как строка ({@code VARCHAR(16)}), словарь расширяется кодом без миграции.
 */
public enum AccessRole {
    OWNER,
    CARETAKER
}
