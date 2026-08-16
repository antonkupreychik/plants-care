package com.plantcare.admin.audit;

/**
 * Типы объектов, которые затрагивают админские действия (issue #98).
 *
 * <p>Строковые константы, а не enum: {@code target_type} — это измерение
 * для фильтра и для секции «История админских действий» на карточке
 * объекта, и оно должно переживать появление новых типов без миграции.
 */
public final class AdminAuditTarget {

    public static final String USER = "USER";
    public static final String SPECIES = "SPECIES";
    public static final String SPECIES_FACT = "SPECIES_FACT";
    public static final String BROADCAST = "BROADCAST";

    private AdminAuditTarget() {
    }
}
