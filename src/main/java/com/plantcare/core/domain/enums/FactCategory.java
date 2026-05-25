package com.plantcare.core.domain.enums;

/**
 * Категория энциклопедического факта о виде (ADR-011, issue #129).
 *
 * <p>Значения совпадают с CHECK-ограничением {@code chk_species_facts_category}
 * в БД (V22). Новая категория = правка CHECK отдельной миграцией + дополнение
 * этого enum. Категорию {@code DISEASE} сознательно не вводим — диагностика
 * живёт отдельно (issue #73).
 */
public enum FactCategory {
    ORIGIN,
    CARE,
    TOXICITY,
    CURIOSITY
}
