package com.plantcare.core.repository;

import com.plantcare.core.domain.Species;
import com.plantcare.core.domain.SpeciesFact;
import com.plantcare.core.domain.enums.FactCategory;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Testcontainers
class SpeciesSearchRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withReuse(true);

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private SpeciesRepository speciesRepository;

    @Autowired
    private SpeciesFactRepository speciesFactRepository;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void seedTestData() {
        // Seed only species used in these tests to stay isolated.
        // We check by name to avoid re-inserting if the container is reused.
        if (speciesRepository.findByName("Роза чайная").isEmpty()) {
            var roza = new Species();
            roza.setName("Роза чайная");
            roza.setLatinName("Rosa");
            roza.setWateringDays(3);
            roza.setSearchTags("роза, rosa, чайная");
            roza.setPopularity(70);
            speciesRepository.save(roza);
        }
        if (speciesRepository.findByName("Лаванда").isEmpty()) {
            var lavanda = new Species();
            lavanda.setName("Лаванда");
            lavanda.setLatinName("Lavandula angustifolia");
            lavanda.setWateringDays(7);
            lavanda.setSearchTags("лаванда, lavender, lavandula");
            lavanda.setPopularity(60);
            speciesRepository.save(lavanda);
        }
        // issue #129: вид, у которого искомое слово есть ТОЛЬКО в теле факта,
        // а не в name/latin_name/search_tags. Слово "мадагаскарский" нарочно
        // отсутствует во всех остальных полях этого вида.
        //
        // Имя нарочно уникальное и НЕ совпадает с сидом V2 (там есть "Драцена"
        // без такого факта) — иначе условие .isEmpty() было бы всегда false и факт
        // не вставлялся бы вовсе, делая поиск по телу факта непроверяемым.
        if (speciesRepository.findByName("Тестовый вид с фактом #129").isEmpty()) {
            var dracaena = new Species();
            dracaena.setName("Тестовый вид с фактом #129");
            dracaena.setLatinName("Dracaena testfact");
            dracaena.setWateringDays(10);
            dracaena.setSearchTags("тествид, testfact");
            dracaena.setPopularity(40);
            speciesRepository.save(dracaena);

            var fact = new SpeciesFact();
            fact.setSpecies(dracaena);
            fact.setCategory(FactCategory.ORIGIN);
            fact.setBody("Это мадагаскарский эндемик, растёт в сухих лесах.");
            fact.setDisplayOrder(0);
            speciesFactRepository.save(fact);
        }

        // searchByQuery — native query; Hibernate не делает надёжный авто-flush
        // перед native-запросами, поэтому факты не были бы видны. Явно флашим.
        entityManager.flush();
    }

    @Test
    void should_find_species_by_word_present_only_in_fact_body() {
        // act — "мадагаскарский" есть только в species_facts.body тестового вида
        List<Species> results = speciesRepository.searchByQuery("мадагаскарский", 5);

        // assert
        assertThat(results)
                .extracting(Species::getName)
                .contains("Тестовый вид с фактом #129");
    }

    @Test
    void should_not_find_species_when_word_absent_from_facts_and_tags() {
        // act — слова нет ни в одном поле и ни в одном факте
        List<Species> results = speciesRepository.searchByQuery("марсианский", 5);

        // assert
        assertThat(results)
                .extracting(Species::getName)
                .doesNotContain("Тестовый вид с фактом #129");
    }

    @Test
    void should_find_species_by_name_substring() {
        // act
        Page<Species> result = speciesRepository.findBySearch("роза", PageRequest.of(0, 10));

        // assert
        assertThat(result.getContent())
                .extracting(Species::getName)
                .contains("Роза чайная");
    }

    @Test
    void should_find_species_by_latin_name() {
        // act
        Page<Species> result = speciesRepository.findBySearch("Rosa", PageRequest.of(0, 10));

        // assert
        assertThat(result.getContent())
                .extracting(Species::getName)
                .contains("Роза чайная");
    }

    @Test
    void should_return_empty_when_no_match() {
        // act
        Page<Species> result = speciesRepository.findBySearch("xyznonexistent42", PageRequest.of(0, 10));

        // assert
        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
    }

    @Test
    void should_find_species_by_search_tags_substring() {
        // act
        Page<Species> result = speciesRepository.findBySearch("lavender", PageRequest.of(0, 10));

        // assert
        assertThat(result.getContent())
                .extracting(Species::getName)
                .contains("Лаванда");
    }
}
