package com.plantcare.core.service;

import com.plantcare.core.domain.SpeciesFact;
import com.plantcare.core.domain.enums.FactCategory;

/**
 * DTO одного энциклопедического факта вида (ADR-011, issue #129).
 *
 * @param category     категория факта
 * @param title        опциональный заголовок (может быть {@code null})
 * @param body         текст факта
 * @param source       опциональный источник (может быть {@code null})
 * @param displayOrder порядок показа внутри категории
 */
public record SpeciesFactDto(
        FactCategory category,
        String title,
        String body,
        String source,
        int displayOrder
) {

    public static SpeciesFactDto from(SpeciesFact entity) {
        return new SpeciesFactDto(
                entity.getCategory(),
                entity.getTitle(),
                entity.getBody(),
                entity.getSource(),
                entity.getDisplayOrder() == null ? 0 : entity.getDisplayOrder()
        );
    }
}
