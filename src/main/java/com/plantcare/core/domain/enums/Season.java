package com.plantcare.core.domain.enums;

/**
 * Сезон (issue #67). Используется при расчёте эффективного интервала ухода.
 * Никаких «весны» и «осени» в первой итерации — это AC: «лето и зима как минимум».
 * Границы между ними настраиваемые: всё, что не лето — зима.
 */
public enum Season {
    SUMMER,
    WINTER;

    public boolean isSummer() { return this == SUMMER; }
    public boolean isWinter() { return this == WINTER; }

    /** Подпись для UI («лето» / «зима»). */
    public String displayName() {
        return switch (this) {
            case SUMMER -> "лето";
            case WINTER -> "зима";
        };
    }
}
