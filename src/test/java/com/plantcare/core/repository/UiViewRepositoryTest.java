package com.plantcare.core.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plantcare.core.domain.UiView;
import org.junit.jupiter.api.DisplayName;
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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @DataJpaTest} на реальном Postgres (Testcontainers, НЕ H2) для
 * {@link UiViewRepository} (issue #284). Проверяет JSONB round-trip шаблона
 * композиции и сид экрана {@code home} из миграции V47.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Testcontainers
@DisplayName("UiViewRepository на Postgres (issue #284)")
class UiViewRepositoryTest {

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
    private UiViewRepository uiViewRepository;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("should_return_seeded_home_view_with_composition_blocks")
    void should_return_seeded_home_view_with_composition_blocks() {
        // act
        Optional<UiView> found = uiViewRepository.findByScreen("home");

        // assert
        assertThat(found).isPresent();
        UiView home = found.get();
        assertThat(home.getCategory()).isEqualTo("screen");
        assertThat(home.getMinCatalogVersion()).isEqualTo(1);

        JsonNode layout = home.getLayoutJson();
        assertThat(layout.path("screenId").asText()).isEqualTo("home");
        assertThat(layout.path("version").asInt()).isEqualTo(1);

        JsonNode blocks = layout.path("blocks");
        assertThat(blocks).hasSize(4);
        assertThat(blocks.get(0).path("type").asText()).isEqualTo("weather_strip");
        assertThat(blocks.get(1).path("type").asText()).isEqualTo("today_summary");
        assertThat(blocks.get(2).path("type").asText()).isEqualTo("location_chips");
        assertThat(blocks.get(3).path("type").asText()).isEqualTo("plant_grid");
    }

    @Test
    @DisplayName("should_roundtrip_jsonb_layout_when_saving_new_view")
    void should_roundtrip_jsonb_layout_when_saving_new_view() throws Exception {
        // arrange
        JsonNode layout = MAPPER.readTree(
                "{\"screenId\":\"plants\",\"version\":2,"
                        + "\"blocks\":[{\"type\":\"banner\",\"minCatalogVersion\":2,\"text\":\"hi\"}]}");
        UiView view = newView("screen", "plants", layout, 2);

        // act
        uiViewRepository.saveAndFlush(view);
        JsonNode reloaded = uiViewRepository.findByScreen("plants").orElseThrow().getLayoutJson();

        // assert — JSONB сохранил вложенную структуру и статичный проп блока
        assertThat(reloaded.path("version").asInt()).isEqualTo(2);
        assertThat(reloaded.path("blocks").get(0).path("type").asText()).isEqualTo("banner");
        assertThat(reloaded.path("blocks").get(0).path("text").asText()).isEqualTo("hi");
        assertThat(reloaded.path("blocks").get(0).path("minCatalogVersion").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("should_return_empty_when_screen_unknown")
    void should_return_empty_when_screen_unknown() {
        assertThat(uiViewRepository.findByScreen("does-not-exist")).isEmpty();
    }

    /** Создаёт {@link UiView} через рефлексию — у entity нет публичных сеттеров. */
    private static UiView newView(String category, String screen, JsonNode layout, int minVersion) {
        try {
            UiView view = new UiView();
            set(view, "category", category);
            set(view, "screen", screen);
            set(view, "layoutJson", layout);
            set(view, "minCatalogVersion", minVersion);
            return view;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void set(UiView view, String field, Object value) throws ReflectiveOperationException {
        var f = UiView.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(view, value);
    }
}
