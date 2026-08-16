package com.plantcare.admin.service;

import com.plantcare.admin.dto.SpeciesFactFormDto;
import com.plantcare.admin.exception.SpeciesFactNotFoundException;
import com.plantcare.core.domain.Species;
import com.plantcare.core.domain.SpeciesFact;
import com.plantcare.core.domain.enums.FactCategory;
import com.plantcare.core.repository.SpeciesFactRepository;
import com.plantcare.core.repository.SpeciesRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.plantcare.admin.audit.service.AdminAuditService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Интеграционные тесты сервиса CRUD фактов вида (issue #131).
 *
 * <p>Только Postgres (Testcontainers): таблица species_facts опирается на CHECK
 * по категории, ON DELETE CASCADE и триггер {@code trg_species_facts_updated_at}
 * из V22 — на H2 это бы не воспроизвелось. Каждый тест создаёт собственные виды
 * с уникальными именами, чтобы быть изолированным при reuse-контейнере.
 *
 * <p>Сервис ({@link AdminSpeciesFactService}) поднимается через {@link Import} —
 * {@code @DataJpaTest} сам по себе сервисный слой не сканирует.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AdminSpeciesFactService.class})
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Testcontainers
class AdminSpeciesFactServiceTest {

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

    /**
     * Аудит (issue #98) в слайс не входит: {@code @DataJpaTest} не поднимает
     * ни JdbcTemplate-репозиторий лога, ни ObjectMapper. Здесь проверяется CRUD
     * фактов, а не проводка аудита — для неё есть {@code AdminAuditWiringTest}.
     */
    @MockitoBean
    private AdminAuditService auditService;

    @Autowired
    private AdminSpeciesFactService service;

    @Autowired
    private SpeciesFactRepository speciesFactRepository;

    @Autowired
    private SpeciesRepository speciesRepository;

    @Autowired
    private EntityManager entityManager;

    // ============================================================ create

    @Test
    void should_persist_fact_bound_to_species_when_create_with_valid_dto() {
        // arrange
        Species species = givenSpecies("Фикус для создания факта");
        var dto = new SpeciesFactFormDto(FactCategory.CARE, "Полив", "Раз в неделю", "Книга", 2);

        // act
        SpeciesFact created = service.createFact(species.getId(), dto, ADMIN);
        flushAndClear();

        // assert
        SpeciesFact reloaded = speciesFactRepository.findById(created.getId()).orElseThrow();
        assertThat(reloaded.getSpecies().getId()).isEqualTo(species.getId());
        assertThat(reloaded.getCategory()).isEqualTo(FactCategory.CARE);
        assertThat(reloaded.getTitle()).isEqualTo("Полив");
        assertThat(reloaded.getBody()).isEqualTo("Раз в неделю");
        assertThat(reloaded.getSource()).isEqualTo("Книга");
        assertThat(reloaded.getDisplayOrder()).isEqualTo(2);
    }

    @Test
    void should_default_display_order_to_zero_when_dto_display_order_is_null() {
        // arrange
        Species species = givenSpecies("Вид с null порядком");
        var dto = new SpeciesFactFormDto(FactCategory.ORIGIN, null, "Родом из тропиков", null, null);

        // act
        SpeciesFact created = service.createFact(species.getId(), dto, ADMIN);
        flushAndClear();

        // assert
        assertThat(speciesFactRepository.findById(created.getId()).orElseThrow().getDisplayOrder())
                .isZero();
    }

    @Test
    void should_throw_when_create_fact_for_missing_species() {
        // arrange
        var dto = new SpeciesFactFormDto(FactCategory.CARE, null, "Тело", null, 0);

        // act + assert
        assertThatThrownBy(() -> service.createFact(999_999L, dto, ADMIN))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("Вид не найден");
    }

    // ============================================================ getFactsGrouped

    @Test
    void should_group_facts_by_category_in_display_order_when_facts_exist() {
        // arrange
        Species species = givenSpecies("Вид с группировкой");
        service.createFact(species.getId(),
                new SpeciesFactFormDto(FactCategory.CARE, null, "уход-1", null, 1), ADMIN);
        service.createFact(species.getId(),
                new SpeciesFactFormDto(FactCategory.CARE, null, "уход-0", null, 0), ADMIN);
        service.createFact(species.getId(),
                new SpeciesFactFormDto(FactCategory.ORIGIN, null, "родина", null, 0), ADMIN);
        flushAndClear();

        // act
        Map<FactCategory, List<SpeciesFact>> grouped = service.getFactsGrouped(species.getId());

        // assert — две категории, внутри CARE сортировка по display_order
        assertThat(grouped).containsOnlyKeys(FactCategory.CARE, FactCategory.ORIGIN);
        assertThat(grouped.get(FactCategory.CARE))
                .extracting(SpeciesFact::getBody)
                .containsExactly("уход-0", "уход-1");
        assertThat(grouped.get(FactCategory.ORIGIN))
                .extracting(SpeciesFact::getBody)
                .containsExactly("родина");
    }

    @Test
    void should_return_empty_map_when_species_has_no_facts() {
        // arrange
        Species species = givenSpecies("Вид без фактов вообще");

        // act
        Map<FactCategory, List<SpeciesFact>> grouped = service.getFactsGrouped(species.getId());

        // assert
        assertThat(grouped).isEmpty();
    }

    // ============================================================ update

    @Test
    void should_update_owned_fact_fields_when_update_valid() {
        // arrange
        Species species = givenSpecies("Вид для обновления");
        SpeciesFact fact = service.createFact(species.getId(),
                new SpeciesFactFormDto(FactCategory.CARE, "старый", "старое тело", null, 0), ADMIN);
        flushAndClear();

        // act
        service.updateFact(species.getId(), fact.getId(),
                new SpeciesFactFormDto(FactCategory.TOXICITY, "новый", "новое тело", "источник", 5), ADMIN);
        flushAndClear();

        // assert
        SpeciesFact reloaded = speciesFactRepository.findById(fact.getId()).orElseThrow();
        assertThat(reloaded.getCategory()).isEqualTo(FactCategory.TOXICITY);
        assertThat(reloaded.getTitle()).isEqualTo("новый");
        assertThat(reloaded.getBody()).isEqualTo("новое тело");
        assertThat(reloaded.getSource()).isEqualTo("источник");
        assertThat(reloaded.getDisplayOrder()).isEqualTo(5);
    }

    // ============================================================ ownership / security

    @Test
    void should_throw_when_update_fact_belonging_to_another_species() {
        // arrange — факт принадлежит виду A, пытаемся обновить «от имени» вида B
        Species owner = givenSpecies("Вид-владелец факта");
        Species attacker = givenSpecies("Чужой вид");
        SpeciesFact fact = service.createFact(owner.getId(),
                new SpeciesFactFormDto(FactCategory.CARE, null, "приватный факт", null, 0), ADMIN);
        flushAndClear();

        var dto = new SpeciesFactFormDto(FactCategory.CARE, null, "взлом", null, 0);

        // act + assert — подмена speciesId не должна дать тронуть чужой факт
        assertThatThrownBy(() -> service.updateFact(attacker.getId(), fact.getId(), dto, ADMIN))
                .isInstanceOf(SpeciesFactNotFoundException.class);

        // и факт остался нетронутым
        flushAndClear();
        assertThat(speciesFactRepository.findById(fact.getId()).orElseThrow().getBody())
                .isEqualTo("приватный факт");
    }

    @Test
    void should_throw_when_delete_fact_belonging_to_another_species() {
        // arrange
        Species owner = givenSpecies("Владелец под удаление");
        Species attacker = givenSpecies("Чужак под удаление");
        SpeciesFact fact = service.createFact(owner.getId(),
                new SpeciesFactFormDto(FactCategory.CARE, null, "нельзя удалить", null, 0), ADMIN);
        flushAndClear();

        // act + assert
        assertThatThrownBy(() -> service.deleteFact(attacker.getId(), fact.getId(), ADMIN))
                .isInstanceOf(SpeciesFactNotFoundException.class);

        // факт всё ещё на месте
        flushAndClear();
        assertThat(speciesFactRepository.findById(fact.getId())).isPresent();
    }

    @Test
    void should_delete_fact_when_owned_by_species() {
        // arrange
        Species species = givenSpecies("Вид с удаляемым фактом");
        SpeciesFact fact = service.createFact(species.getId(),
                new SpeciesFactFormDto(FactCategory.CARE, null, "удалить меня", null, 0), ADMIN);
        flushAndClear();

        // act
        service.deleteFact(species.getId(), fact.getId(), ADMIN);
        flushAndClear();

        // assert
        assertThat(speciesFactRepository.findById(fact.getId())).isEmpty();
    }

    @Test
    void should_throw_when_get_owned_fact_for_wrong_species() {
        // arrange
        Species owner = givenSpecies("Владелец для prefill");
        Species other = givenSpecies("Другой для prefill");
        SpeciesFact fact = service.createFact(owner.getId(),
                new SpeciesFactFormDto(FactCategory.CARE, null, "тело", null, 0), ADMIN);
        flushAndClear();

        // act + assert
        assertThatThrownBy(() -> service.getOwnedFact(other.getId(), fact.getId()))
                .isInstanceOf(SpeciesFactNotFoundException.class);
    }

    @Test
    void should_have_updated_at_set_by_db_after_create() {
        // arrange — поле updated_at помечено insertable=false/updatable=false:
        // значение ставит БД (DEFAULT now()), не код
        Species species = givenSpecies("Вид для проверки маппинга updated_at");
        SpeciesFact created = service.createFact(species.getId(),
                new SpeciesFactFormDto(FactCategory.CARE, null, "тело", null, 0), ADMIN);
        flushAndClear();

        // act
        SpeciesFact reloaded = speciesFactRepository.findById(created.getId()).orElseThrow();

        // assert — БД проставила updated_at даже без участия кода
        assertThat(reloaded.getUpdatedAt()).isNotNull();
    }

    // ------------------------------------------------------------------ helpers

    private Species givenSpecies(String name) {
        var s = new Species();
        s.setName(name);
        s.setWateringDays(7);
        s.setPopularity(0);
        return speciesRepository.save(s);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
