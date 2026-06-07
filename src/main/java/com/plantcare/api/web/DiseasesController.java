package com.plantcare.api.web;

import com.plantcare.api.generated.DiseasesApi;
import com.plantcare.api.generated.model.DiseaseDto;
import com.plantcare.api.generated.model.PageResponseDiseaseDto;
import com.plantcare.core.service.DiseaseCard;
import com.plantcare.core.service.DiseaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Публичный REST API справочника болезней и вредителей комнатных растений (issue #255).
 *
 * <p>Документация и mapping живут в сгенерированном {@link DiseasesApi}.
 * Аутентификация не требуется — справочник общедоступен.
 * Вся бизнес-логика делегируется {@link DiseaseService}.
 *
 * <p>Пагинация: {@code offset}/{@code limit} (limit ≤ 100). Паттерн скопирован
 * со {@link SpeciesController}: offset/limit конвертируются в {@code PageRequest}.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class DiseasesController implements DiseasesApi {

    private static final int MAX_LIMIT = 100;

    private final DiseaseService diseaseService;

    @Override
    public PageResponseDiseaseDto listDiseases(String q, Integer offset, Integer limit) {
        int safeLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        int safeOffset = Math.max(0, offset);
        int page = safeOffset / safeLimit;

        log.debug("Diseases list request. q='{}', offset={}, limit={}", q, safeOffset, safeLimit);

        Page<DiseaseCard> diseasePage;
        if (q == null || q.isBlank()) {
            diseasePage = diseaseService.findPage(PageRequest.of(page, safeLimit));
        } else {
            diseasePage = diseaseService.searchPage(q, PageRequest.of(page, safeLimit));
        }

        List<DiseaseDto> items = diseasePage.getContent().stream()
                .map(DiseasesController::toDto)
                .toList();

        return new PageResponseDiseaseDto(items, diseasePage.getTotalElements(), safeOffset, safeLimit);
    }

    @Override
    public DiseaseDto getDiseaseById(Long id) {
        log.debug("Disease detail request. id={}", id);
        return toDto(diseaseService.getById(id));
    }

    private static DiseaseDto toDto(DiseaseCard card) {
        return new DiseaseDto(card.id(), card.name(), card.symptoms(), card.treatment(), card.prevention())
                .latinName(card.latinName());
    }
}
