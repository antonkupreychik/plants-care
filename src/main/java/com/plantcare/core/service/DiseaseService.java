package com.plantcare.core.service;

import com.plantcare.core.diagnosis.DiagnosisSymptom;
import com.plantcare.core.domain.Disease;
import com.plantcare.core.repository.DiseaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

/**
 * Чтение справочника типичных болезней/вредителей (ADR-013, issue #140).
 *
 * <p>Только чтение: данные сидятся миграцией V25, пользователь их не меняет.
 * Сервис отделён от Telegram-слоя — хендлеры дёргают его и рендерят результат.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiseaseService {

    private final DiseaseRepository diseaseRepository;

    /**
     * Все болезни справочника в алфавитном порядке.
     */
    @Transactional(readOnly = true)
    public List<DiseaseCard> getAll() {
        return diseaseRepository.findAllByOrderByNameAsc()
                .stream()
                .map(DiseaseCard::from)
                .toList();
    }

    /**
     * Карточка болезни по id.
     *
     * @throws DiseaseNotFoundException если болезни с таким id нет
     */
    @Transactional(readOnly = true)
    public DiseaseCard getById(Long diseaseId) {
        return diseaseRepository.findById(diseaseId)
                .map(DiseaseCard::from)
                .orElseThrow(() -> new DiseaseNotFoundException(diseaseId));
    }

    /**
     * Полнотекстовый поиск по болезням (название/теги/симптомы).
     *
     * @param query      пользовательский запрос
     * @param maxResults максимум результатов
     * @return найденные карточки в алфавитном порядке; пустой список, если ничего не найдено
     */
    @Transactional(readOnly = true)
    public List<DiseaseCard> search(String query, int maxResults) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        List<DiseaseCard> results = diseaseRepository.searchByQuery(query.trim(), maxResults)
                .stream()
                .map(DiseaseCard::from)
                .toList();

        log.debug("Disease search. query='{}', found={}", query, results.size());
        return results;
    }

    /**
     * Болезни, у которых {@code symptom_codes} (CSV) содержит код данного симптома.
     *
     * <p>Матчинг строго по точному коду-токену: CSV режется по запятой, каждый
     * токен тримится и сравнивается через {@code equals} с кодом симптома. Так
     * {@code leaf_spots} не матчит {@code other} и наоборот — подстрочного
     * совпадения не происходит. Справочник маленький (~20 записей, ADR-013),
     * поэтому фильтрация в памяти дешевле и надёжнее SQL LIKE по подстроке.
     *
     * @param symptom симптом из визарда диагностики (#73)
     * @return карточки подходящих болезней в алфавитном порядке; пустой список, если совпадений нет
     */
    @Transactional(readOnly = true)
    public List<DiseaseCard> matchBySymptom(DiagnosisSymptom symptom) {
        if (symptom == null) {
            return List.of();
        }

        String code = symptom.code();

        return diseaseRepository.findAllByOrderByNameAsc()
                .stream()
                .filter(disease -> containsCode(disease.getSymptomCodes(), code))
                .map(DiseaseCard::from)
                .toList();
    }

    private boolean containsCode(String csv, String code) {
        if (csv == null || csv.isBlank()) {
            return false;
        }

        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .anyMatch(token -> token.equals(code));
    }
}
