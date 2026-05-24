package com.plantcare.bot.web.dto;

import com.plantcare.bot.domain.Species;

public record SpeciesSummaryDto(
        Long id,
        String name,
        String latinName,
        Integer wateringDays,
        Integer mistingDays,
        Integer fertilizingDays,
        Integer soilCheckDays,
        String careDifficulty,
        String lightPreference
) {

    public static SpeciesSummaryDto from(Species s) {
        return new SpeciesSummaryDto(
                s.getId(),
                s.getName(),
                s.getLatinName(),
                s.getWateringDays(),
                s.getMistingDays(),
                s.getFertilizingDays(),
                s.getSoilCheckDays(),
                s.getCareDifficulty() != null ? s.getCareDifficulty().name() : null,
                s.getLightPreference() != null ? s.getLightPreference().name() : null
        );
    }
}
