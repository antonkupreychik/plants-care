package com.plantcare.bot.repository;

import com.plantcare.bot.domain.Species;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
