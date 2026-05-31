package com.plantcare.core.service;

import com.plantcare.core.config.SpeciesProperties;
import com.plantcare.core.domain.Species;
import com.plantcare.core.repository.SpeciesRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SpeciesService {


    private final SpeciesRepository speciesRepository;
    private final SpeciesProperties speciesProperties;

    @Transactional(readOnly = true)
    public List<Species> getTopPopular(int limit) {
        if (limit <= 0) {
            log.debug("Top popular species request ignored because limit is not positive: {}", limit);
            return List.of();
        }

        log.debug("Getting top popular species. limit={}", limit);

        List<Species> species = speciesRepository.findAllByOrderByPopularityDesc(Limit.of(limit));

        log.info("Top popular species loaded. limit={}, found={}", limit, species.size());

        return species;
    }

    @Transactional(readOnly = true)
    public List<Species> search(String query) {
        if (query == null || query.isBlank()) {
            log.debug("Species search request ignored because query is blank");
            return List.of();
        }

        String trimmedQuery = query.trim();

        log.debug("Searching species. query='{}', limit={}", trimmedQuery, speciesProperties.getSearchLimit());

        List<Species> species = speciesRepository.searchByQuery(trimmedQuery, speciesProperties.getSearchLimit());

        log.info("Species search completed. query='{}', found={}", trimmedQuery, species.size());

        return species;
    }

    /**
     * Возвращает страницу видов с опциональной фильтрацией по подстроке.
     *
     * Результат кешируется в {@code species-list}. Ключ кеша включает нормализованный
     * запрос (lowercase, trim), номер и размер страницы. Если {@code q} null или пустой,
     * возвращаются все виды без фильтра.
     *
     * @param q        подстрока для поиска; null и пустая строка эквивалентны
     * @param pageable параметры пагинации и сортировки
     * @return страница entity {@link com.plantcare.core.domain.Species}
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "species-list", key = "#q?.toLowerCase()?.trim() + '-' + #pageable.pageNumber + '-' + #pageable.pageSize")
    public Page<Species> findPage(String q, Pageable pageable) {
        log.debug("Finding species page. q='{}', page={}, size={}", q, pageable.getPageNumber(), pageable.getPageSize());

        Page<Species> result = (q == null || q.isBlank())
                ? speciesRepository.findAll(pageable)
                : speciesRepository.findBySearch(q, pageable);

        log.debug("Species page loaded. totalElements={}", result.getTotalElements());

        return result;
    }

    /**
     * Возвращает вид по идентификатору.
     *
     * Результат кешируется в {@code species-detail} по ключу {@code id}.
     *
     * @param id идентификатор вида
     * @return entity {@link com.plantcare.core.domain.Species}
     * @throws jakarta.persistence.EntityNotFoundException если вид не найден
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "species-detail", key = "#id")
    public Species getById(Long id) {
        log.debug("Getting species by id={}", id);

        return speciesRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("Species not found. id={}", id);
                    return new EntityNotFoundException("Species not found: " + id);
                });
    }

    @Transactional
    public Species incrementPopularity(Long speciesId) {
        log.debug("Incrementing species popularity. speciesId={}", speciesId);

        Species species = speciesRepository.findById(speciesId)
                .orElseThrow(() -> {
                    log.warn("Cannot increment species popularity. Species not found. speciesId={}", speciesId);
                    return new EntityNotFoundException("Species not found: " + speciesId);
                });

        species.setPopularity(species.getPopularity() + 1);

        log.info(
                "Species popularity incremented. speciesId={}, popularity={}",
                speciesId,
                species.getPopularity()
        );

        return species;
    }
}