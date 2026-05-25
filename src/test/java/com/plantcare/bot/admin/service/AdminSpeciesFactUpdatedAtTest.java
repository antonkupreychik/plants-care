package com.plantcare.bot.admin.service;

import com.plantcare.bot.admin.dto.SpeciesFactFormDto;
import com.plantcare.core.domain.Species;
import com.plantcare.core.domain.enums.FactCategory;
import com.plantcare.core.repository.SpeciesFactRepository;
import com.plantcare.core.repository.SpeciesRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверка обновления {@code updated_at} факта триггером БД (issue #131, AC «updated_at обновляется при изменении»).
 *
 * <p>{@code updated_at} ставит триггер {@code trg_species_facts_updated_at} (V22),
 * значение — {@code CURRENT_TIMESTAMP}, то есть время ТРАНЗАКЦИИ. Чтобы увидеть сдвиг
 * времени, insert и update обязаны быть в РАЗНЫХ транзакциях, иначе они получат
 * идентичный таймстемп. Поэтому класс работает без оборачивающей rollback-транзакции
 * {@code @DataJpaTest} ({@link Transactional} с {@link Propagation#NOT_SUPPORTED}):
 * каждый вызов {@code @Transactional}-метода сервиса коммитится отдельно. Созданные
 * строки чистим в {@link AfterEach}.
 *
 * <p>Только Postgres (Testcontainers): на H2 триггера нет.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AdminSpeciesFactService.class})
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Rollback(false)
@Testcontainers
class AdminSpeciesFactUpdatedAtTest {

    private static final String ADMIN = "editor";

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
    private AdminSpeciesFactService service;

    @Autowired
    private SpeciesFactRepository speciesFactRepository;

    @Autowired
    private SpeciesRepository speciesRepository;

    @Autowired
    private EntityManager entityManager;

    private Long createdSpeciesId;

    @AfterEach
    void cleanup() {
        // Без оборачивающей транзакции данные реально коммитятся — чистим за собой,
        // чтобы не контаминировать reuse-контейнер.
        if (createdSpeciesId != null) {
            speciesRepository.deleteById(createdSpeciesId);
        }
    }

    @Test
    void should_advance_updated_at_when_fact_updated_in_separate_transaction() {
        // arrange — создаём факт (commit 1) и читаем исходный updated_at прямо из БД
        Long speciesId = givenCommittedSpecies("Вид для updated_at");
        Long factId = service.createFact(speciesId,
                new SpeciesFactFormDto(FactCategory.CARE, null, "исходное тело", null, 0), ADMIN).getId();

        Instant before = readUpdatedAt(factId);
        assertThat(before).isNotNull();

        // act — обновляем факт (commit 2 → новый CURRENT_TIMESTAMP)
        service.updateFact(speciesId, factId,
                new SpeciesFactFormDto(FactCategory.CARE, null, "изменённое тело", null, 0), ADMIN);

        // assert — триггер сдвинул updated_at строго вперёд
        Instant after = readUpdatedAt(factId);
        assertThat(after)
                .isNotNull()
                .isAfter(before);
    }

    // ------------------------------------------------------------------ helpers

    private Long givenCommittedSpecies(String name) {
        var s = new Species();
        s.setName(name);
        s.setWateringDays(7);
        s.setPopularity(0);
        Long id = speciesRepository.save(s).getId();
        this.createdSpeciesId = id;
        return id;
    }

    /**
     * Читает updated_at нативно из БД как {@link Instant} (колонка TIMESTAMPTZ),
     * видим реальное значение, проставленное триггером, без влияния persistence-контекста.
     */
    private Instant readUpdatedAt(Long factId) {
        Object value = entityManager.createNativeQuery(
                        "SELECT updated_at FROM species_facts WHERE id = :id")
                .setParameter("id", factId)
                .getSingleResult();
        return switch (value) {
            case Instant instant -> instant;
            case Timestamp ts -> ts.toInstant();
            case OffsetDateTime odt -> odt.toInstant();
            case java.time.LocalDateTime ldt -> ldt.toInstant(ZoneOffset.UTC);
            default -> throw new IllegalStateException(
                    "Неожиданный тип updated_at: " + value.getClass());
        };
    }
}
