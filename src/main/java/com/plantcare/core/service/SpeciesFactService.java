package com.plantcare.core.service;

import com.plantcare.core.domain.SpeciesFact;
import com.plantcare.core.domain.enums.FactCategory;
import com.plantcare.core.repository.SpeciesFactRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Чтение энциклопедических фактов о видах (ADR-011, issue #129).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpeciesFactService {

    private final SpeciesFactRepository speciesFactRepository;

    /**
     * Факты вида, сгруппированные по категории в порядке показа.
     *
     * <p>Внутри каждой категории факты упорядочены по {@code displayOrder}
     * (репозиторий отдаёт уже отсортированный по {@code (category, displayOrder)}
     * список, порядок сохраняется в {@link LinkedHashMap}/{@link List}).
     * Категории без фактов в результат не попадают.
     *
     * @param speciesId идентификатор вида
     * @param locale    язык контента; пока игнорируется — мягкая закладка под
     *                  будущий i18n из ADR-011
     * @return карта категория → факты; пустая, если у вида нет фактов
     */
    @Transactional(readOnly = true)
    public Map<FactCategory, List<SpeciesFactDto>> getFactsBySpecies(Long speciesId, String locale) {
        log.debug("Loading species facts. speciesId={}, locale={}", speciesId, locale);

        List<SpeciesFact> facts =
                speciesFactRepository.findBySpeciesIdOrderByCategoryAscDisplayOrderAsc(speciesId);

        Map<FactCategory, List<SpeciesFactDto>> grouped = new LinkedHashMap<>();
        for (SpeciesFact fact : facts) {
            grouped.computeIfAbsent(fact.getCategory(), k -> new java.util.ArrayList<>())
                    .add(SpeciesFactDto.from(fact));
        }

        log.debug("Species facts loaded. speciesId={}, categories={}, total={}",
                speciesId, grouped.size(), facts.size());

        return grouped;
    }

    /**
     * Есть ли у вида хотя бы один факт.
     *
     * <p>Дешёвый existence-check вместо загрузки полного списка фактов —
     * используется для решения о видимости кнопки «О виде» на карточке
     * растения (самый частый экран бота).
     *
     * @param speciesId идентификатор вида
     * @return {@code true}, если у вида есть факты
     */
    @Transactional(readOnly = true)
    public boolean hasFactsForSpecies(Long speciesId) {
        return speciesFactRepository.existsBySpeciesId(speciesId);
    }
}
