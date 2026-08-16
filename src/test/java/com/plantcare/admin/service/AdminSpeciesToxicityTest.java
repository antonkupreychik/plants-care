package com.plantcare.admin.service;

import com.plantcare.admin.dto.SpeciesFormDto;
import com.plantcare.core.domain.Species;
import com.plantcare.core.repository.SpeciesRepository;
import jakarta.persistence.EntityManager;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tri-state флаги токсичности вида через {@link AdminSpeciesService} (issue #131, #130).
 *
 * <p>Три состояния: {@code true} (токсично) / {@code false} (безопасно) /
 * {@code null} (нет данных). Ключевой кейс AC — сброс {@code true → null}: вид был
 * помечен токсичным, редактор выбрал «нет данных», колонка обязана занулиться.
 *
 * <p>Сохранение идёт реальным {@code applyDto} сервиса и реальной колонкой Postgres
 * (V26), а не в обход entity. {@code @CacheEvict} на методах сервиса в слайс-тесте
 * без CacheManager — no-op, на проверяемое поведение не влияет.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AdminSpeciesService.class})
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Testcontainers
class AdminSpeciesToxicityTest {

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

    /** Аудит (issue #98) вне слайса — проверяется в {@code AdminAuditWiringTest}. */
    @MockitoBean
    private AdminAuditService auditService;

    @Autowired
    private AdminSpeciesService service;

    @Autowired
    private SpeciesRepository speciesRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void should_persist_all_three_toxicity_states_when_created() {
        // arrange — кошки токсично, собаки безопасно, люди — нет данных
        SpeciesFormDto dto = baseDto("Вид с тремя состояниями");
        dto.setToxicToCats(true);
        dto.setToxicToDogs(false);
        dto.setToxicToHumans(null);

        // act
        Species created = service.create(dto, ADMIN);
        flushAndClear();

        // assert
        Species reloaded = speciesRepository.findById(created.getId()).orElseThrow();
        assertThat(reloaded.getToxicToCats()).isTrue();
        assertThat(reloaded.getToxicToDogs()).isFalse();
        assertThat(reloaded.getToxicToHumans()).isNull();
    }

    @Test
    void should_reset_toxicity_to_null_when_updated_from_true_to_no_data() {
        // arrange — был токсичен для всех троих
        SpeciesFormDto createDto = baseDto("Вид со сбросом токсичности");
        createDto.setToxicToCats(true);
        createDto.setToxicToDogs(true);
        createDto.setToxicToHumans(true);
        Species created = service.create(createDto, ADMIN);
        flushAndClear();
        // sanity: значения действительно лежат в БД
        assertThat(readToxicToCats(created.getId())).isTrue();

        // act — редактор выставил «нет данных» (null) по всем трём
        SpeciesFormDto updateDto = baseDto("Вид со сбросом токсичности");
        updateDto.setToxicToCats(null);
        updateDto.setToxicToDogs(null);
        updateDto.setToxicToHumans(null);
        service.update(created.getId(), updateDto, ADMIN);
        flushAndClear();

        // assert — колонки реально занулены, а не остались true
        Species reloaded = speciesRepository.findById(created.getId()).orElseThrow();
        assertThat(reloaded.getToxicToCats()).isNull();
        assertThat(reloaded.getToxicToDogs()).isNull();
        assertThat(reloaded.getToxicToHumans()).isNull();
        // и нативно в БД именно NULL, не false
        assertThat(readToxicToCats(reloaded.getId())).isNull();
    }

    @Test
    void should_set_toxicity_to_true_when_updated_from_null() {
        // arrange — изначально нет данных
        Species created = service.create(baseDto("Вид без данных о токсичности"), ADMIN);
        flushAndClear();
        assertThat(readToxicToCats(created.getId())).isNull();

        // act — отметили токсичным для кошек
        SpeciesFormDto updateDto = baseDto("Вид без данных о токсичности");
        updateDto.setToxicToCats(true);
        service.update(created.getId(), updateDto, ADMIN);
        flushAndClear();

        // assert
        assertThat(speciesRepository.findById(created.getId()).orElseThrow().getToxicToCats())
                .isTrue();
    }

    // ------------------------------------------------------------------ helpers

    private SpeciesFormDto baseDto(String name) {
        SpeciesFormDto dto = new SpeciesFormDto();
        dto.setName(name);
        dto.setWateringDays(7);
        dto.setPopularity(50);
        return dto;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    /**
     * Читает toxic_to_cats нативно — отличаем настоящий SQL NULL от false.
     */
    private Boolean readToxicToCats(Long speciesId) {
        return (Boolean) entityManager.createNativeQuery(
                        "SELECT toxic_to_cats FROM species WHERE id = :id")
                .setParameter("id", speciesId)
                .getSingleResult();
    }
}
