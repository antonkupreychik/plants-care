package com.plantcare.api.web;

import com.plantcare.api.generated.SpeciesApi;
import com.plantcare.api.generated.model.PageResponseSpeciesSummaryDto;
import com.plantcare.api.generated.model.SpeciesDetailDto;
import com.plantcare.api.generated.model.SpeciesFactDto;
import com.plantcare.api.generated.model.SpeciesSummaryDto;
import com.plantcare.core.domain.Species;
import com.plantcare.core.service.SpeciesFactService;
import com.plantcare.core.service.SpeciesService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Публичный REST API справочника видов растений.
 *
 * <p>Документация и mapping живут в сгенерированном {@link SpeciesApi}.
 * Аутентификация не требуется. Ответы кешируются в {@code species-list} /
 * {@code species-detail} (TTL 1 час); кеш сбрасывается при любом изменении
 * через {@code AdminSpeciesService}.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class SpeciesController implements SpeciesApi {

    private static final int MAX_LIMIT = 100;

    private final SpeciesService speciesService;
    private final SpeciesFactService speciesFactService;

    @Override
    public PageResponseSpeciesSummaryDto listSpecies(String q, Integer offset, Integer limit) {
        int safeLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        int safeOffset = Math.max(0, offset);
        int page = safeOffset / safeLimit;

        log.debug("Species list request. q='{}', offset={}, limit={}", q, safeOffset, safeLimit);

        Page<Species> speciesPage = speciesService.findPage(q, PageRequest.of(page, safeLimit));

        List<SpeciesSummaryDto> items = speciesPage.getContent().stream()
                .map(SpeciesController::toSummary)
                .toList();

        return new PageResponseSpeciesSummaryDto(items, speciesPage.getTotalElements(), safeOffset, safeLimit);
    }

    @Override
    public SpeciesDetailDto getSpecies(Long id) {
        log.debug("Species detail request. id={}", id);

        Species species = speciesService.getById(id);

        // issue #129: факты вида в детальной карточке справочника. Сгруппированы
        // по категориям сервисом; здесь разворачиваем в плоский список с уже
        // правильным порядком (категория → displayOrder).
        List<SpeciesFactDto> facts = speciesFactService.getFactsBySpecies(id, null).values().stream()
                .flatMap(List::stream)
                .map(SpeciesController::toFact)
                .toList();

        return toDetail(species).facts(facts);
    }

    private static SpeciesSummaryDto toSummary(Species s) {
        return new SpeciesSummaryDto(s.getId(), s.getName())
                .latinName(s.getLatinName())
                .wateringDays(s.getWateringDays())
                .mistingDays(s.getMistingDays())
                .fertilizingDays(s.getFertilizingDays())
                .soilCheckDays(s.getSoilCheckDays())
                .careDifficulty(s.getCareDifficulty() != null ? s.getCareDifficulty().name() : null)
                .lightPreference(s.getLightPreference() != null ? s.getLightPreference().name() : null);
    }

    private static SpeciesFactDto toFact(com.plantcare.core.service.SpeciesFactDto fact) {
        return new SpeciesFactDto(SpeciesFactDto.CategoryEnum.fromValue(fact.category().name()), fact.body())
                .title(fact.title())
                .source(fact.source());
    }

    private static SpeciesDetailDto toDetail(Species s) {
        return new SpeciesDetailDto(s.getId(), s.getName())
                .latinName(s.getLatinName())
                .wateringDays(s.getWateringDays())
                .mistingDays(s.getMistingDays())
                .fertilizingDays(s.getFertilizingDays())
                .soilCheckDays(s.getSoilCheckDays())
                .careDifficulty(s.getCareDifficulty() != null ? s.getCareDifficulty().name() : null)
                .lightPreference(s.getLightPreference() != null ? s.getLightPreference().name() : null)
                .description(s.getDescription());
    }
}
