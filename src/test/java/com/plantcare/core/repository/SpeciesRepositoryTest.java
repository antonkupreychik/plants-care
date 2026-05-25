package com.plantcare.core.repository;

import com.plantcare.core.domain.Species;
import com.plantcare.bot.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты на справочник видов. Использует данные из V2__seed_species.sql,
 * поэтому read-only — записи не создаём, очистку не делаем.
 */
class SpeciesRepositoryTest extends IntegrationTestBase {

    @Autowired
    private SpeciesRepository speciesRepository;

    @Test
    void findByNameWorks() {
        Optional<Species> monstera = speciesRepository.findByName("Монстера");

        assertThat(monstera).isPresent();
        assertThat(monstera.get().getWateringDays()).isEqualTo(7);
        assertThat(monstera.get().getPopularity()).isEqualTo(100);
    }

    @Test
    void topPopularReturnsInCorrectOrder() {
        List<Species> top3 = speciesRepository.findAllByOrderByPopularityDesc(Limit.of(3));

        assertThat(top3).hasSize(3);
        // У Монстеры popularity=100, она должна быть первой
        assertThat(top3.get(0).getName()).isEqualTo("Монстера");
        // Каждый следующий — не больше предыдущего
        for (int i = 1; i < top3.size(); i++) {
            assertThat(top3.get(i).getPopularity())
                    .isLessThanOrEqualTo(top3.get(i - 1).getPopularity());
        }
    }

    @Test
    void searchByQueryFindsByLatinName() {
        // У Орхидеи фаленопсис latin_name = "Phalaenopsis"
        List<Species> results = speciesRepository.searchByQuery("Phalaenopsis", 5);

        assertThat(results).extracting(Species::getName)
                .contains("Орхидея фаленопсис");
    }

    @Test
    void searchByQueryFindsByPartialRussianName() {
        // Поиск по подстроке имени
        List<Species> results = speciesRepository.searchByQuery("фикус", 5);

        assertThat(results).extracting(Species::getName)
                .anyMatch(name -> name.toLowerCase().contains("фикус"));
    }

    @Test
    void searchByQueryFindsByEnglishTag() {
        // У Орхидеи в search_tags есть "orchid"
        List<Species> results = speciesRepository.searchByQuery("orchid", 5);

        assertThat(results).extracting(Species::getName)
                .contains("Орхидея фаленопсис");
    }

    @Test
    void searchByQueryRespectsLimit() {
        // Очень общий запрос, который должен бы вернуть много, но мы лимитируем
        List<Species> results = speciesRepository.searchByQuery("растение", 3);

        assertThat(results).hasSizeLessThanOrEqualTo(3);
    }

    @Test
    void searchByQueryReturnsEmptyForNonexistentMatch() {
        List<Species> results = speciesRepository.searchByQuery("xyznonexistent", 5);

        assertThat(results).isEmpty();
    }
}
