package com.plantcare.core.service;

/**
 * Болезнь с указанным id не найдена в справочнике (issue #140).
 * Бросается {@link DiseaseService} при запросе несуществующей карточки.
 */
public class DiseaseNotFoundException extends RuntimeException {

    public DiseaseNotFoundException(Long diseaseId) {
        super("Disease not found: " + diseaseId);
    }
}
