package com.plantcare.bot.service;

import com.plantcare.bot.domain.Disease;

/**
 * Карточка болезни/вредителя для показа в справочнике (issue #140).
 *
 * @param id         идентификатор болезни (для callback'ов и команды /disease_id)
 * @param name       русское название
 * @param latinName  латинское название; {@code null} для неинфекционных проблем
 * @param symptoms   как распознать
 * @param treatment  что делать
 * @param prevention как избежать
 */
public record DiseaseCard(
        Long id,
        String name,
        String latinName,
        String symptoms,
        String treatment,
        String prevention
) {

    public static DiseaseCard from(Disease entity) {
        return new DiseaseCard(
                entity.getId(),
                entity.getName(),
                entity.getLatinName(),
                entity.getSymptoms(),
                entity.getTreatment(),
                entity.getPrevention()
        );
    }
}
