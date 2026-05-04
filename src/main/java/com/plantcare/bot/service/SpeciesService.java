package com.plantcare.bot.service;

import com.plantcare.bot.domain.Species;
import com.plantcare.bot.repository.SpeciesRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SpeciesService {

    private static final int SEARCH_LIMIT = 5;

    private final SpeciesRepository speciesRepository;

    @Transactional(readOnly = true)
    public List<Species> getTopPopular(int limit) {
        if (limit <= 0) {
            return List.of();
        }

        return speciesRepository.findAllByOrderByPopularityDesc(Limit.of(limit));
    }

    @Transactional(readOnly = true)
    public List<Species> search(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        return speciesRepository.searchByQuery(query.trim(), SEARCH_LIMIT);
    }

    @Transactional
    public Species incrementPopularity(Long speciesId) {
        Species species = speciesRepository.findById(speciesId)
                .orElseThrow(() -> new EntityNotFoundException("Species not found: " + speciesId));

        species.setPopularity(species.getPopularity() + 1);

        return species;
    }
}