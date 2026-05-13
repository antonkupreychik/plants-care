package com.plantcare.bot.admin.dto;

import com.plantcare.bot.domain.Species;
import com.plantcare.bot.domain.enums.CareDifficulty;
import com.plantcare.bot.domain.enums.LightPreference;

/**
 * Проекция Species + количество привязанных растений.
 * Используется только в админ-списке, чтобы не делать N+1 запросов
 * при отрисовке кнопки удаления с подсчётом затрагиваемых растений.
 */
public record SpeciesListItem(
        Long id,
        String name,
        String latinName,
        Integer wateringDays,
        Integer mistingDays,
        Integer fertilizingDays,
        LightPreference lightPreference,
        CareDifficulty careDifficulty,
        Integer popularity,
        long linkedPlantsCount
) {
    public static SpeciesListItem of(Species s, long linkedPlantsCount) {
        return new SpeciesListItem(
                s.getId(), s.getName(), s.getLatinName(),
                s.getWateringDays(), s.getMistingDays(), s.getFertilizingDays(),
                s.getLightPreference(), s.getCareDifficulty(),
                s.getPopularity(), linkedPlantsCount
        );
    }
}
