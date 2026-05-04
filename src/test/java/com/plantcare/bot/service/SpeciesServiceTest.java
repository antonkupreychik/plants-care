package com.plantcare.bot.service;

import com.plantcare.bot.domain.Species;
import com.plantcare.bot.repository.SpeciesRepository;
import com.plantcare.bot.support.IntegrationTestBase;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpeciesServiceTest extends IntegrationTestBase {

    @Autowired
    private SpeciesService speciesService;

    @Autowired
    private SpeciesRepository speciesRepository;

    @Test
    void getTopPopularReturnsSpeciesOrderedByPopularityDesc() {
        List<Species> top = speciesService.getTopPopular(5);

        assertThat(top).hasSize(5);

        for (int i = 1; i < top.size(); i++) {
            assertThat(top.get(i).getPopularity())
                    .isLessThanOrEqualTo(top.get(i - 1).getPopularity());
        }
    }

    @Test
    void getTopPopularReturnsEmptyListWhenLimitIsZeroOrNegative() {
        assertThat(speciesService.getTopPopular(0)).isEmpty();
        assertThat(speciesService.getTopPopular(-1)).isEmpty();
    }

    @Test
    void searchReturnsTopFiveResults() {
        List<Species> results = speciesService.search("фикус");

        assertThat(results).hasSizeLessThanOrEqualTo(5);
    }

    @Test
    void searchFindsOrchidByPartialRussianQuery() {
        List<Species> results = speciesService.search("фаленопс");

        assertThat(results)
                .extracting(Species::getName)
                .contains("Орхидея фаленопсис");
    }

    @Test
    void searchFindsOrchidByEnglishTag() {
        List<Species> results = speciesService.search("orchid");

        assertThat(results)
                .extracting(Species::getName)
                .contains("Орхидея фаленопсис");
    }

    @Test
    void searchReturnsEmptyListForBlankQuery() {
        assertThat(speciesService.search(null)).isEmpty();
        assertThat(speciesService.search("")).isEmpty();
        assertThat(speciesService.search("   ")).isEmpty();
    }

    @Test
    void searchReturnsEmptyListWhenNothingFound() {
        List<Species> results = speciesService.search("xyznonexistent");

        assertThat(results).isEmpty();
    }

    @Test
    void incrementPopularityIncreasesPopularityByOne() {
        Species species = speciesRepository.findByName("Орхидея фаленопсис")
                .orElseThrow();

        Integer before = species.getPopularity();

        Species updated = speciesService.incrementPopularity(species.getId());

        assertThat(updated.getPopularity()).isEqualTo(before + 1);

        Species reloaded = speciesRepository.findById(species.getId())
                .orElseThrow();

        assertThat(reloaded.getPopularity()).isEqualTo(before + 1);
    }

    @Test
    void incrementPopularityThrowsWhenSpeciesNotFound() {
        assertThatThrownBy(() -> speciesService.incrementPopularity(-1L))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("Species not found");
    }
}