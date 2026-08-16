package com.plantcare.admin.service;

import com.plantcare.admin.audit.AdminAuditAction;
import com.plantcare.admin.audit.AdminAuditTarget;
import com.plantcare.admin.audit.service.AdminAuditService;
import com.plantcare.admin.dto.SpeciesFactFormDto;
import com.plantcare.admin.exception.SpeciesFactNotFoundException;
import com.plantcare.core.domain.Species;
import com.plantcare.core.domain.SpeciesFact;
import com.plantcare.core.domain.enums.FactCategory;
import com.plantcare.core.repository.SpeciesFactRepository;
import com.plantcare.core.repository.SpeciesRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Админский CRUD энциклопедических фактов вида (issue #131).
 *
 * <p>Каждая операция привязана к виду: при update/delete проверяется
 * принадлежность факта виду из URL ({@link SpeciesFactRepository#findByIdAndSpeciesId}),
 * чтобы редактор не мог через подмену {@code factId} тронуть чужой факт.
 *
 * <p>{@code updated_at} факта обновляется триггером БД, руками не выставляется
 * (см. {@link SpeciesFact}). Чтение фактов ({@code SpeciesFactService}) не кэшируется,
 * поэтому {@code @CacheEvict} здесь не нужен.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminSpeciesFactService {

    private final SpeciesFactRepository speciesFactRepository;
    private final SpeciesRepository speciesRepository;
    private final AdminAuditService auditService;

    /**
     * Факты вида, сгруппированные по категории в порядке показа
     * (внутри категории — по {@code displayOrder}). Категории без фактов
     * в результат не попадают.
     */
    @Transactional(readOnly = true)
    public Map<FactCategory, List<SpeciesFact>> getFactsGrouped(Long speciesId) {
        List<SpeciesFact> facts =
                speciesFactRepository.findBySpeciesIdOrderByCategoryAscDisplayOrderAsc(speciesId);

        Map<FactCategory, List<SpeciesFact>> grouped = new LinkedHashMap<>();
        for (SpeciesFact fact : facts) {
            grouped.computeIfAbsent(fact.getCategory(), k -> new ArrayList<>()).add(fact);
        }
        return grouped;
    }

    /**
     * Один факт вида с проверкой принадлежности (для prefill формы редактирования).
     */
    @Transactional(readOnly = true)
    public SpeciesFact getOwnedFact(Long speciesId, Long factId) {
        return requireOwnedFact(speciesId, factId);
    }

    /**
     * Создаёт факт у вида.
     *
     * @throws EntityNotFoundException если вид не найден
     */
    @Transactional
    public SpeciesFact createFact(Long speciesId, SpeciesFactFormDto dto, String adminUsername) {
        Species species = speciesRepository.findById(speciesId)
                .orElseThrow(() -> new EntityNotFoundException("Вид не найден: " + speciesId));

        SpeciesFact fact = new SpeciesFact();
        fact.setSpecies(species);
        applyDto(fact, dto);
        SpeciesFact saved = speciesFactRepository.save(fact);

        log.info("Admin [{}] created fact id={} for species id={} category={}",
                adminUsername, saved.getId(), speciesId, saved.getCategory());
        auditService.log(AdminAuditAction.SPECIES_FACT_CREATE, adminUsername,
                AdminAuditTarget.SPECIES_FACT, saved.getId(),
                AdminAuditService.details("speciesId", speciesId,
                        "category", String.valueOf(saved.getCategory()),
                        "title", saved.getTitle()));
        return saved;
    }

    /**
     * Обновляет факт вида с проверкой принадлежности.
     *
     * @throws SpeciesFactNotFoundException если факт не найден или принадлежит другому виду
     */
    @Transactional
    public SpeciesFact updateFact(Long speciesId, Long factId, SpeciesFactFormDto dto, String adminUsername) {
        SpeciesFact fact = requireOwnedFact(speciesId, factId);
        String titleBefore = fact.getTitle();
        applyDto(fact, dto);
        SpeciesFact saved = speciesFactRepository.save(fact);

        log.info("Admin [{}] updated fact id={} for species id={} category={}",
                adminUsername, factId, speciesId, saved.getCategory());
        auditService.log(AdminAuditAction.SPECIES_FACT_UPDATE, adminUsername,
                AdminAuditTarget.SPECIES_FACT, factId,
                AdminAuditService.details("speciesId", speciesId,
                        "titleBefore", titleBefore, "titleAfter", saved.getTitle()));
        return saved;
    }

    /**
     * Удаляет факт вида с проверкой принадлежности.
     *
     * @throws SpeciesFactNotFoundException если факт не найден или принадлежит другому виду
     */
    @Transactional
    public void deleteFact(Long speciesId, Long factId, String adminUsername) {
        SpeciesFact fact = requireOwnedFact(speciesId, factId);
        String title = fact.getTitle();
        String category = String.valueOf(fact.getCategory());
        speciesFactRepository.delete(fact);

        log.info("Admin [{}] deleted fact id={} for species id={}", adminUsername, factId, speciesId);
        auditService.log(AdminAuditAction.SPECIES_FACT_DELETE, adminUsername,
                AdminAuditTarget.SPECIES_FACT, factId,
                AdminAuditService.details("speciesId", speciesId,
                        "category", category, "title", title));
    }

    // ------------------------------------------------------------------ helpers

    private SpeciesFact requireOwnedFact(Long speciesId, Long factId) {
        return speciesFactRepository.findByIdAndSpeciesId(factId, speciesId)
                .orElseThrow(() -> new SpeciesFactNotFoundException(factId, speciesId));
    }

    private void applyDto(SpeciesFact fact, SpeciesFactFormDto dto) {
        fact.setCategory(dto.category());
        fact.setTitle(dto.title());
        fact.setBody(dto.body());
        fact.setSource(dto.source());
        fact.setDisplayOrder(dto.displayOrder() == null ? 0 : dto.displayOrder());
    }
}
