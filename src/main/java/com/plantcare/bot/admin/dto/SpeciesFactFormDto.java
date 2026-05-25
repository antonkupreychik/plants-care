package com.plantcare.bot.admin.dto;

import com.plantcare.core.domain.SpeciesFact;
import com.plantcare.core.domain.enums.FactCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Форма создания/редактирования энциклопедического факта вида (issue #131).
 *
 * <p>DTO — record (требование AC). Конвертация в entity делается в
 * {@code AdminSpeciesFactService}, обратная для заполнения формы — фабрикой
 * {@link #from(SpeciesFact)}.
 */
public record SpeciesFactFormDto(

        @NotNull(message = "Категория обязательна")
        FactCategory category,

        @Size(max = 160, message = "Заголовок не должен превышать 160 символов")
        String title,

        @NotBlank(message = "Текст факта обязателен")
        String body,

        @Size(max = 300, message = "Источник не должен превышать 300 символов")
        String source,

        @PositiveOrZero(message = "Порядок отображения не может быть отрицательным")
        Integer displayOrder
) {

    /**
     * Заполняет форму значениями существующего факта (для inline-редактирования).
     */
    public static SpeciesFactFormDto from(SpeciesFact fact) {
        return new SpeciesFactFormDto(
                fact.getCategory(),
                fact.getTitle(),
                fact.getBody(),
                fact.getSource(),
                fact.getDisplayOrder()
        );
    }
}
