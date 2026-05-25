package com.plantcare.core.repository;

import com.plantcare.core.domain.Species;
import com.plantcare.core.domain.SpeciesFact;
import com.plantcare.core.domain.enums.FactCategory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Тесты репозитория энциклопедических фактов вида (ADR-011, issue #129).
 *
 * <p>Только Postgres (Testcontainers): таблица species_facts опирается на
 * CHECK-констрейнт по категории и ON DELETE CASCADE из V22 — на H2 это бы не
 * воспроизвелось. Каждый тест создаёт собственный вид со своим именем, чтобы
 * быть изолированным при reuse-контейнере.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Testcontainers
class SpeciesFactRepositoryTest {

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

    @Autowired
    private EntityManager entityManager;

    @Test
    void should_return_facts_ordered_by_category_then_display_order_when_facts_exist() {
        // arrange
        Species species = givenSpecies("Фикус для сортировки");
        // Намеренно вставляем вперемешку — проверяем, что упорядочивает запрос, а не порядок вставки.
        // category хранится как STRING (@Enumerated(STRING)), поэтому SQL сортирует
        // по тексту имени лексикографически: CARE < CURIOSITY < ORIGIN < TOXICITY.
        saveFact(species, FactCategory.TOXICITY, "токсично", 0);
        saveFact(species, FactCategory.ORIGIN, "родина-2", 1);
        saveFact(species, FactCategory.CARE, "уход", 0);
        saveFact(species, FactCategory.CURIOSITY, "любопытно-2", 1);
        saveFact(species, FactCategory.ORIGIN, "родина-1", 0);
        saveFact(species, FactCategory.CURIOSITY, "любопытно-1", 0);

        // act
        List<SpeciesFact> facts =
                speciesFactRepository.findBySpeciesIdOrderByCategoryAscDisplayOrderAsc(species.getId());

        // assert — порядок: CARE, CURIOSITY(0,1), ORIGIN(0,1), TOXICITY
        assertThat(facts)
                .extracting(SpeciesFact::getCategory)
                .containsExactly(
                        FactCategory.CARE,
                        FactCategory.CURIOSITY, FactCategory.CURIOSITY,
                        FactCategory.ORIGIN, FactCategory.ORIGIN,
                        FactCategory.TOXICITY);
        assertThat(facts)
                .extracting(SpeciesFact::getBody)
                .containsExactly("уход", "любопытно-1", "любопытно-2", "родина-1", "родина-2", "токсично");
    }

    @Test
    void should_return_empty_list_when_species_has_no_facts() {
        // arrange
        Species species = givenSpecies("Вид без фактов");

        // act
        List<SpeciesFact> facts =
                speciesFactRepository.findBySpeciesIdOrderByCategoryAscDisplayOrderAsc(species.getId());

        // assert
        assertThat(facts).isEmpty();
    }

    @Test
    void should_cascade_delete_facts_when_species_deleted() {
        // arrange
        Species species = givenSpecies("Вид под удаление");
        saveFact(species, FactCategory.CARE, "поливать редко", 0);
        saveFact(species, FactCategory.TOXICITY, "токсичен для кошек", 0);
        Long speciesId = species.getId();
        // Гарантируем, что и вид, и факты ушли в БД и вышли из persistence-контекста.
        // Иначе managed SpeciesFact-сущности на flush после удаления вида ссылались бы
        // на удалённый Species (Hibernate про DB-level ON DELETE CASCADE не знает).
        entityManager.flush();
        entityManager.clear();
        assertThat(speciesFactRepository.findBySpeciesIdOrderByCategoryAscDisplayOrderAsc(speciesId))
                .hasSize(2);

        // act
        speciesRepository.deleteById(speciesId);
        entityManager.flush();
        entityManager.clear();

        // assert — факты удалены каскадом из V22 (ON DELETE CASCADE)
        Number remaining = (Number) entityManager.createNativeQuery(
                        "SELECT count(*) FROM species_facts WHERE species_id = :sid")
                .setParameter("sid", speciesId)
                .getSingleResult();
        assertThat(remaining.longValue()).isZero();
        assertThat(speciesFactRepository.findBySpeciesIdOrderByCategoryAscDisplayOrderAsc(speciesId))
                .isEmpty();
    }

    @Test
    void should_violate_check_constraint_when_category_outside_enum() {
        // arrange
        Species species = givenSpecies("Вид с плохой категорией");
        entityManager.flush();

        // act + assert — обходим JPA-enum нативной вставкой невалидной категории;
        // CHECK chk_species_facts_category (V22) обязан отклонить.
        assertThatThrownBy(() -> {
            entityManager.createNativeQuery("""
                    INSERT INTO species_facts (species_id, category, body, display_order)
                    VALUES (:sid, 'DISEASE', 'некорректная категория', 0)
                    """)
                    .setParameter("sid", species.getId())
                    .executeUpdate();
            entityManager.flush();
        })
                // Голый EntityManager бросает jakarta PersistenceException (обёртка над
                // Hibernate/JDBC), Spring-трансляция в DataIntegrityViolationException
                // происходит только на границе Spring Data репозитория, не здесь.
                .isInstanceOf(PersistenceException.class)
                .hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class)
                .rootCause()
                .hasMessageContaining("chk_species_facts_category");
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
