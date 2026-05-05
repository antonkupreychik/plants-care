package com.plantcare.bot.service;

import com.plantcare.bot.config.SpeciesProperties;
import com.plantcare.bot.domain.Species;
import com.plantcare.bot.repository.SpeciesRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
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