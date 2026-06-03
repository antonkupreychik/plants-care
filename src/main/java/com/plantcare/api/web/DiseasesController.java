package com.plantcare.api.web;

import com.plantcare.api.generated.DiseasesApi;
import com.plantcare.api.generated.model.DiseaseDto;
import com.plantcare.core.service.DiseaseCard;
import com.plantcare.core.service.DiseaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Публичный REST API справочника болезней и вредителей комнатных растений (issue #225).
 *
 * <p>Документация и mapping живут в сгенерированном {@link DiseasesApi}.
 * Аутентификация не требуется — справочник общедоступен.
 * Вся бизнес-логика делегируется {@link DiseaseService}.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class DiseasesController implements DiseasesApi {

    private static final int SEARCH_MAX_RESULTS = 20;

    private final DiseaseService diseaseService;

    @Override
    public List<DiseaseDto> listDiseases(String q) {
        if (q == null || q.isBlank()) {
            log.debug("Diseases list request: all");
            return diseaseService.getAll().stream()
                    .map(DiseasesController::toDto)
                    .toList();
        }

        log.debug("Diseases search request. q='{}'", q);
        return diseaseService.search(q, SEARCH_MAX_RESULTS).stream()
                .map(DiseasesController::toDto)
                .toList();
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
