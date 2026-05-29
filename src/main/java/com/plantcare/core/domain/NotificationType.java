package com.plantcare.core.domain;

/**
 * Тип уведомления в персистентном inbox пользователя (issue #183).
 *
 * <p>В БД хранится имя константы в верхнем регистре ({@code CARE}, {@code ALERT}, …)
 * через {@link jakarta.persistence.EnumType#STRING}. В JSON API наружу отдаётся
 * нижний регистр ({@code care}, {@code alert}, …) — маппинг делает контроллер
 * между этим enum и сгенерированным DTO-enum. Колонка {@code type VARCHAR(16)}
 * не имеет CHECK-констрейнта: новые типы добавляются кодом без миграции.
 */
public enum NotificationType {
    CARE,
    ALERT,
    AWARD,
    REPORT,
    SYSTEM
}
