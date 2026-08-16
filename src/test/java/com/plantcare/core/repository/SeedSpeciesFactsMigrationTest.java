package com.plantcare.core.repository;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционный тест Flyway-сида V29__seed_species_facts (ADR-011, issue #132).
 *
 * <p>Только Postgres (Testcontainers): сид опирается на V22 (species_facts с
 * CHECK-констрейнтом категории) и V26 (species.toxic_to_*). На H2 диалект не
 * совпал бы. Flyway сам прогоняет все миграции на контейнере при старте контекста,
 * поэтому тест проверяет уже применённое состояние БД.
 *
 * <p>Каждый кейс читает данные нативными запросами и сверяет ровно те инварианты,
 * что описаны в acceptance criteria issue #132. Тест не мутирует справочные данные
 * (кроме явного теста идемпотентности, который проверяет no-op самого сид-INSERT).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Testcontainers
class SeedSpeciesFactsMigrationTest {

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
    private EntityManager entityManager;

    @Test
    void should_seed_origin_care_and_toxicity_facts_for_top_species_when_migration_applied() {
        // arrange
        Long monsteraId = speciesIdByLatin("Monstera deliciosa");

        // act
        long originCount = factCount(monsteraId, "ORIGIN");
        long careCount = factCount(monsteraId, "CARE");
        long toxicityCount = factCount(monsteraId, "TOXICITY");

        // assert — у топ-вида присутствуют факты ORIGIN, CARE и TOXICITY
        assertThat(originCount).isEqualTo(1L);
        assertThat(careCount).isEqualTo(1L);
        assertThat(toxicityCount).isEqualTo(1L);
    }

    @Test
    void should_set_toxicity_flags_for_known_species_when_migration_applied() {
        // arrange
        Long spathiphyllumId = speciesIdByLatin("Spathiphyllum");
        Long monsteraId = speciesIdByLatin("Monstera deliciosa");

        // act
        Boolean spathiphyllumCats = toxicToCats(spathiphyllumId);
        Boolean monsteraCats = toxicToCats(monsteraId);
        Boolean monsteraHumans = toxicToHumans(monsteraId);

        // assert — токсичные виды помечены TRUE для кошек, а оксалатные — и для людей
        assertThat(spathiphyllumCats).isTrue();
        assertThat(monsteraCats).isTrue();
        assertThat(monsteraHumans).isTrue();
    }

    @Test
    void should_mark_safe_species_as_non_toxic_when_migration_applied() {
        // arrange
        Long phalaenopsisId = speciesIdByLatin("Phalaenopsis");
        Long saintpauliaId = speciesIdByLatin("Saintpaulia");

        // act
        Boolean phalaenopsisCats = toxicToCats(phalaenopsisId);
        Boolean saintpauliaCats = toxicToCats(saintpauliaId);

        // assert — безопасные по ASPCA виды помечены FALSE, а не NULL
        assertThat(phalaenopsisCats).isFalse();
        assertThat(saintpauliaCats).isFalse();
    }

    @Test
    void should_leave_toxicity_null_for_species_without_data_when_migration_applied() {
        // arrange — Yucca засеяна в V2, но V29 её намеренно не трогает (нет достоверных данных)
        Long yuccaId = speciesIdByLatin("Yucca");

        // act
        Boolean yuccaCats = toxicToCats(yuccaId);

        // assert — отсутствие данных представлено NULL, а не ложным FALSE
        assertThat(yuccaCats).isNull();
    }

    @Test
    void should_fill_source_for_origin_and_toxicity_facts_when_migration_applied() {
        // arrange
        Long monsteraId = speciesIdByLatin("Monstera deliciosa");

        // act
        String originSource = factSource(monsteraId, "ORIGIN");
        String toxicitySource = factSource(monsteraId, "TOXICITY");

        // assert — source проставлен из выверенных источников
        assertThat(originSource).isEqualTo("Royal Botanic Gardens, Kew");
        assertThat(toxicitySource).isEqualTo("ASPCA");
    }

    @Test
    void should_not_duplicate_facts_when_seed_insert_rerun() {
        // arrange — точное тело ORIGIN-факта монстеры из V29, повторяем тот же INSERT ... WHERE NOT EXISTS
        Long monsteraId = speciesIdByLatin("Monstera deliciosa");
        long before = factCount(monsteraId, "ORIGIN");

        // act — повторный прогон сид-вставки (Flyway применил бы её лишь раз; гоним тело вручную)
        int inserted = entityManager.createNativeQuery("""
                INSERT INTO species_facts (species_id, category, title, body, source, display_order)
                SELECT s.id, 'ORIGIN', 'Родина',
                       'Происходит из тропических лесов Центральной Америки (юг Мексики, Панама), где как лиана взбирается по стволам деревьев к свету.',
                       'Royal Botanic Gardens, Kew', 10
                FROM species s WHERE s.latin_name = 'Monstera deliciosa'
                  AND NOT EXISTS (
                    SELECT 1 FROM species_facts f
                    WHERE f.species_id = s.id AND f.category = 'ORIGIN'
                      AND f.body = 'Происходит из тропических лесов Центральной Америки (юг Мексики, Панама), где как лиана взбирается по стволам деревьев к свету.')
                """).executeUpdate();
        entityManager.flush();
        entityManager.clear();
        long after = factCount(monsteraId, "ORIGIN");

        // assert — WHERE NOT EXISTS делает вставку no-op, дубля нет
        assertThat(inserted).isZero();
        assertThat(after).isEqualTo(before);
    }

    // ------------------------------------------------------------------ helpers

    private Long speciesIdByLatin(String latinName) {
        Object id = entityManager.createNativeQuery(
                        "SELECT id FROM species WHERE latin_name = :latin")
                .setParameter("latin", latinName)
                .getSingleResult();
        return ((Number) id).longValue();
    }

    private long factCount(Long speciesId, String category) {
        Number count = (Number) entityManager.createNativeQuery(
                        "SELECT count(*) FROM species_facts WHERE species_id = :sid AND category = :cat")
                .setParameter("sid", speciesId)
                .setParameter("cat", category)
                .getSingleResult();
        return count.longValue();
    }

    private String factSource(Long speciesId, String category) {
        return (String) entityManager.createNativeQuery(
                        "SELECT source FROM species_facts WHERE species_id = :sid AND category = :cat")
                .setParameter("sid", speciesId)
                .setParameter("cat", category)
                .getSingleResult();
    }

    private Boolean toxicToCats(Long speciesId) {
        return (Boolean) entityManager.createNativeQuery(
                        "SELECT toxic_to_cats FROM species WHERE id = :sid")
                .setParameter("sid", speciesId)
                .getSingleResult();
    }

    private Boolean toxicToHumans(Long speciesId) {
        return (Boolean) entityManager.createNativeQuery(
                        "SELECT toxic_to_humans FROM species WHERE id = :sid")
                .setParameter("sid", speciesId)
                .getSingleResult();
    }
}
