package com.plantcare.bot.web.dto;

import com.plantcare.bot.domain.Species;

public record SpeciesDetailDto(
        Long id,
        String name,
        String latinName,
        Integer wateringDays,
        Integer mistingDays,
        Integer fertilizingDays,
        Integer soilCheckDays,
        String careDifficulty,
        String lightPreference,
        String description
) {

    public static SpeciesDetailDto from(Species s) {
        return new SpeciesDetailDto(
                s.getId(),
                s.getName(),
                s.getLatinName(),
                s.getWateringDays(),
                s.getMistingDays(),
                s.getFertilizingDays(),
                s.getSoilCheckDays(),
                s.getCareDifficulty() != null ? s.getCareDifficulty().name() : null,
                s.getLightPreference() != null ? s.getLightPreference().name() : null,
                s.getDescription()
        );
    }
}
