package com.plantcare.bot.service;

import com.plantcare.bot.domain.Species;
import com.plantcare.bot.domain.SpeciesFact;
import com.plantcare.bot.domain.enums.FactCategory;
import com.plantcare.bot.repository.SpeciesFactRepository;
import com.plantcare.bot.repository.SpeciesRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты сервиса чтения фактов вида (ADR-011, issue #129).
 *
 * <p>Репозиторий не мокаем (CLAUDE.md): используем реальный Postgres через
 * Testcontainers, сервис собираем вручную поверх инжектированного репозитория.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Testcontainers
class SpeciesFactServiceTest {

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
    private SpeciesFactRepository speciesFactRepository;

    @Autowired
    private SpeciesRepository speciesRepository;

    private SpeciesFactService service;

    @BeforeEach
    void setUp() {
        service = new SpeciesFactService(speciesFactRepository);
    }

    @Test
    void should_group_facts_by_category_ordered_by_display_order_when_facts_exist() {
        // arrange
        Species species = givenSpecies("Монстера для группировки");
        saveFact(species, FactCategory.ORIGIN, "родина-2", 1);
        saveFact(species, FactCategory.ORIGIN, "родина-1", 0);
        saveFact(species, FactCategory.CARE, "уход", 0);

        // act
        Map<FactCategory, List<SpeciesFactDto>> grouped =
                service.getFactsBySpecies(species.getId(), "ru");

        // assert
        assertThat(grouped).containsOnlyKeys(FactCategory.CARE, FactCategory.ORIGIN);
        assertThat(grouped.get(FactCategory.ORIGIN))
                .extracting(SpeciesFactDto::body)
                .containsExactly("родина-1", "родина-2");
        assertThat(grouped.get(FactCategory.CARE))
                .extracting(SpeciesFactDto::body)
                .containsExactly("уход");
    }

    @Test
    void should_return_empty_map_when_species_has_no_facts() {
        // arrange
        Species species = givenSpecies("Вид совсем без фактов");

        // act
        Map<FactCategory, List<SpeciesFactDto>> grouped =
                service.getFactsBySpecies(species.getId(), "ru");

        // assert
        assertThat(grouped).isNotNull().isEmpty();
    }

    @Test
    void should_return_identical_result_regardless_of_locale_when_i18n_is_stub() {
        // arrange
        Species species = givenSpecies("Вид для проверки locale-заглушки");
        saveFact(species, FactCategory.CURIOSITY, "факт", 0);

        // act
        Map<FactCategory, List<SpeciesFactDto>> ru = service.getFactsBySpecies(species.getId(), "ru");
        Map<FactCategory, List<SpeciesFactDto>> en = service.getFactsBySpecies(species.getId(), "en");
        Map<FactCategory, List<SpeciesFactDto>> nullLocale = service.getFactsBySpecies(species.getId(), null);

        // assert — locale пока игнорируется, фиксируем текущее поведение
        assertThat(ru).isEqualTo(en);
        assertThat(ru).isEqualTo(nullLocale);
        assertThat(ru.get(FactCategory.CURIOSITY))
                .extracting(SpeciesFactDto::body)
                .containsExactly("факт");
    }

    // ------------------------------------------------------------------ helpers

    private Species givenSpecies(String name) {
        var s = new Species();
        s.setName(name);
        s.setWateringDays(7);
        s.setPopularity(0);
        return speciesRepository.save(s);
    }

    private void saveFact(Species species, FactCategory category, String body, int displayOrder) {
        var fact = new SpeciesFact();
        fact.setSpecies(species);
        fact.setCategory(category);
        fact.setBody(body);
        fact.setDisplayOrder(displayOrder);
        speciesFactRepository.save(fact);
    }
}
