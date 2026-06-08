package com.plantcare.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plantcare.api.generated.model.LocationDto;
import com.plantcare.api.generated.model.PageResponsePlantDto;
import com.plantcare.api.generated.model.PlantDto;
import com.plantcare.api.generated.model.TaskDto;
import com.plantcare.api.generated.model.TodayResponse;
import com.plantcare.api.generated.model.TodaySummary;
import com.plantcare.api.generated.model.WeatherSnapshotDto;
import com.plantcare.api.CurrentUserProvider;
import com.plantcare.api.v1.LocationController;
import com.plantcare.api.v1.PlantController;
import com.plantcare.api.v1.TodayController;
import com.plantcare.api.v1.WeatherController;
import com.plantcare.core.domain.UiView;
import com.plantcare.core.domain.User;
import com.plantcare.core.repository.UiViewRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit-тест {@link UiViewService} (SDUI, issue #284). Композиция берётся из
 * шаблона {@code ui_views} (JSONB), динамика гидрируется из контроллеров-моков,
 * блоки фильтруются по {@code minCatalogVersion}. Edge — версия каталога ниже
 * блока исключает блок; статичный блок отдаётся verbatim.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UiViewService — сборка SDUI-экрана (issue #284)")
class UiViewServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock private UiViewRepository uiViewRepository;
    @Mock private CurrentUserProvider currentUserProvider;
    @Mock private WeatherController weatherController;
    @Mock private TodayController todayController;
    @Mock private LocationController locationController;
    @Mock private PlantController plantController;

    @InjectMocks private UiViewService service;

    private static User user(boolean guest) {
        User u = new User();
        u.setGuest(guest);
        return u;
    }

    @BeforeEach
    void stubHydrationSources() {
        // По умолчанию — обычный авторизованный юзер (не гость).
        lenient().when(currentUserProvider.currentUser()).thenReturn(user(false));

        lenient().when(weatherController.getWeatherSnapshot()).thenReturn(
                new WeatherSnapshotDto(true)
                        .humidityPercent(82)
                        .recommendation(WeatherSnapshotDto.RecommendationEnum.DEFER_OK)
                        .fetchedAt(OffsetDateTime.of(2026, 5, 28, 12, 0, 0, 0, ZoneOffset.UTC))
                        .fromCache(false));

        lenient().when(todayController.getToday()).thenReturn(
                new TodayResponse(
                        List.of(new TaskDto(
                                1L, 42L, "Фикус", "FERTILIZING",
                                OffsetDateTime.of(2026, 5, 27, 9, 0, 0, 0, ZoneOffset.UTC))),
                        3, new TodaySummary(3, 1, 2, 1)));

        lenient().when(locationController.listLocations()).thenReturn(List.of(
                new LocationDto(5L, "Гостиная", true, true).emoji("🛋️")));

        lenient().when(plantController.listPlants(any(), any(), eq(0), eq(100))).thenReturn(
                new PageResponsePlantDto(
                        List.of(new PlantDto(10L, "Монстера", false).locationName("Гостиная")),
                        1, 0, 100));
    }

    private void seedHomeView(String layoutJson, int minVersion) {
        when(uiViewRepository.findByScreen("home")).thenReturn(Optional.of(home(layoutJson, minVersion)));
    }

    private static UiView home(String layoutJson, int minVersion) {
        try {
            JsonNode layout = MAPPER.readTree(layoutJson);
            UiView view = new UiView();
            field(view, "category", "screen");
            field(view, "screen", "home");
            field(view, "layoutJson", layout);
            field(view, "minCatalogVersion", minVersion);
            return view;
        } catch (ReflectiveOperationException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void field(UiView v, String name, Object value) throws ReflectiveOperationException {
        var f = UiView.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(v, value);
    }

    @Test
    @DisplayName("should_build_full_home_from_composition_when_no_catalog_version")
    void should_build_full_home_from_composition_when_no_catalog_version() {
        // arrange — композиция из 4 динамических блоков, как в сиде V48
        seedHomeView("""
                { "screenId":"home","version":1,"blocks":[
                  {"type":"weather_strip","minCatalogVersion":1},
                  {"type":"today_tasks","minCatalogVersion":1},
                  {"type":"location_chips","minCatalogVersion":1},
                  {"type":"plant_grid","minCatalogVersion":1} ] }""", 1);

        // act
        Map<String, Object> screen = service.buildScreen("home", null);

        // assert — верхний уровень + фиксированный порядок из таблицы
        assertThat(screen).containsEntry("screenId", "home").containsEntry("version", 1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) screen.get("blocks");
        assertThat(blocks).extracting(b -> b.get("type"))
                .containsExactly("weather_strip", "today_tasks", "location_chips", "plant_grid");

        // гидрация: weather + today_tasks (counts + тапабельный список) + chips + grid
        assertThat(blocks.get(0)).containsEntry("available", true).containsEntry("humidityPercent", 82)
                .containsEntry("recommendation", "DEFER_OK");
        assertThat(blocks.get(1)).containsEntry("completedCount", 1).containsEntry("totalCount", 3);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> todayTasks = (List<Map<String, Object>>) blocks.get(1).get("tasks");
        assertThat(todayTasks).hasSize(1);
        assertThat(todayTasks.get(0))
                .containsEntry("scheduleId", 1L)
                .containsEntry("plantId", 42L)
                .containsEntry("plantName", "Фикус")
                .containsEntry("type", "FERTILIZE")   // FERTILIZING → FERTILIZE (нормализация)
                .containsEntry("dueAt", OffsetDateTime.of(2026, 5, 27, 9, 0, 0, 0, ZoneOffset.UTC));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chips = (List<Map<String, Object>>) blocks.get(2).get("locations");
        assertThat(chips.get(0)).containsEntry("id", 5L).containsEntry("name", "Гостиная").containsEntry("emoji", "🛋️");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> plants = (List<Map<String, Object>>) blocks.get(3).get("plants");
        assertThat(plants.get(0)).containsEntry("id", 10L).containsEntry("name", "Монстера")
                .containsEntry("locationName", "Гостиная");

        // MADR-017: тап по телу карточки → navigate к витрине растения
        @SuppressWarnings("unchecked")
        Map<String, Object> navAction = (Map<String, Object>) plants.get(0).get("action");
        assertThat(navAction).containsEntry("kind", "navigate").containsEntry("target", "/plants/10");

        // MADR-017: явная кнопка «полить» → log_care + invalidates
        @SuppressWarnings("unchecked")
        Map<String, Object> action = (Map<String, Object>) plants.get(0).get("waterAction");
        assertThat(action).containsEntry("kind", "log_care").containsEntry("method", "POST")
                .containsEntry("path", "/care-events")
                .containsEntry("invalidates", List.of("home", "today"));
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) action.get("payloadTemplate");
        assertThat(payload).containsEntry("plantId", 10L).containsEntry("type", "WATER");
    }

    @Test
    @DisplayName("should_exclude_block_when_catalog_version_below_block_minimum")
    void should_exclude_block_when_catalog_version_below_block_minimum() {
        // arrange — plant_grid требует каталог v2; клиент знает только v1
        seedHomeView("""
                { "screenId":"home","version":2,"blocks":[
                  {"type":"weather_strip","minCatalogVersion":1},
                  {"type":"plant_grid","minCatalogVersion":2} ] }""", 1);

        // act — клиент с X-UI-Catalog-Version: 1
        Map<String, Object> screen = service.buildScreen("home", 1);

        // assert — блок v2 исключён, остался только weather_strip
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) screen.get("blocks");
        assertThat(blocks).extracting(b -> b.get("type")).containsExactly("weather_strip");
    }

    @Test
    @DisplayName("should_render_static_block_verbatim_without_hydration")
    void should_render_static_block_verbatim_without_hydration() {
        // arrange — статичный banner-блок не из каталога динамических типов
        seedHomeView("""
                { "screenId":"home","version":1,"blocks":[
                  {"type":"banner","minCatalogVersion":1,"text":"Привет","dismissible":true} ] }""", 1);

        // act
        Map<String, Object> screen = service.buildScreen("home", null);

        // assert — отдан verbatim из layout_json
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) screen.get("blocks");
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0)).containsEntry("type", "banner").containsEntry("text", "Привет")
                .containsEntry("dismissible", true);
    }

    @Test
    @DisplayName("should_normalize_domain_task_type_to_public_dictionary_in_today_tasks")
    void should_normalize_domain_task_type_to_public_dictionary_in_today_tasks() {
        // arrange — внутренние доменные типы (ловушка контракта) во всех вариантах
        when(todayController.getToday()).thenReturn(new TodayResponse(
                List.of(
                        task(1L, "WATERING"),
                        task(2L, "MISTING"),
                        task(3L, "FERTILIZING"),
                        task(4L, "SOIL_CHECK")),
                4, new TodaySummary(4, 0, 4, 0)));
        seedHomeView("""
                { "screenId":"home","version":1,"blocks":[
                  {"type":"today_tasks","minCatalogVersion":1} ] }""", 1);

        // act
        Map<String, Object> screen = service.buildScreen("home", null);

        // assert — WATERING→WATER, MISTING→SPRAY, FERTILIZING→FERTILIZE, SOIL_CHECK как есть
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) screen.get("blocks");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) blocks.get(0).get("tasks");
        assertThat(tasks).extracting(t -> t.get("type"))
                .containsExactly("WATER", "SPRAY", "FERTILIZE", "SOIL_CHECK");
    }

    @Test
    @DisplayName("should_render_empty_today_tasks_when_no_tasks_today")
    void should_render_empty_today_tasks_when_no_tasks_today() {
        // arrange — задач на сегодня нет
        when(todayController.getToday()).thenReturn(
                new TodayResponse(List.of(), 0, new TodaySummary(0, 0, 0, 0)));
        seedHomeView("""
                { "screenId":"home","version":1,"blocks":[
                  {"type":"today_tasks","minCatalogVersion":1} ] }""", 1);

        // act
        Map<String, Object> screen = service.buildScreen("home", null);

        // assert — блок есть, counts нулевые, список пустой (а не null)
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) screen.get("blocks");
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0))
                .containsEntry("type", "today_tasks")
                .containsEntry("completedCount", 0)
                .containsEntry("totalCount", 0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) blocks.get(0).get("tasks");
        assertThat(tasks).isEmpty();
    }

    @Test
    @DisplayName("should_inject_guest_banner_after_weather_when_user_is_guest")
    void should_inject_guest_banner_after_weather_when_user_is_guest() {
        // arrange — текущий юзер ГОСТЬ
        when(currentUserProvider.currentUser()).thenReturn(user(true));
        seedHomeView("""
                { "screenId":"home","version":1,"blocks":[
                  {"type":"weather_strip","minCatalogVersion":1},
                  {"type":"today_tasks","minCatalogVersion":1},
                  {"type":"plant_grid","minCatalogVersion":1} ] }""", 1);

        // act
        Map<String, Object> screen = service.buildScreen("home", null);

        // assert — guest_banner вставлен сразу после weather_strip
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) screen.get("blocks");
        assertThat(blocks).extracting(b -> b.get("type"))
                .containsExactly("weather_strip", "guest_banner", "today_tasks", "plant_grid");
        Map<String, Object> banner = blocks.get(1);
        assertThat(banner).containsEntry("type", "guest_banner")
                .containsKeys("titleKey", "bodyKey", "ctaAction");
        @SuppressWarnings("unchecked")
        Map<String, Object> cta = (Map<String, Object>) banner.get("ctaAction");
        assertThat(cta).containsEntry("kind", "navigate");
    }

    @Test
    @DisplayName("should_not_inject_guest_banner_when_user_is_authenticated")
    void should_not_inject_guest_banner_when_user_is_authenticated() {
        // arrange — обычный юзер (стаб BeforeEach: не гость)
        seedHomeView("""
                { "screenId":"home","version":1,"blocks":[
                  {"type":"weather_strip","minCatalogVersion":1},
                  {"type":"plant_grid","minCatalogVersion":1} ] }""", 1);

        // act
        Map<String, Object> screen = service.buildScreen("home", null);

        // assert — guest_banner отсутствует
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) screen.get("blocks");
        assertThat(blocks).extracting(b -> b.get("type"))
                .containsExactly("weather_strip", "plant_grid")
                .doesNotContain("guest_banner");
    }

    @Test
    @DisplayName("should_replace_plant_grid_with_empty_state_when_garden_is_empty")
    void should_replace_plant_grid_with_empty_state_when_garden_is_empty() {
        // arrange — у юзера нет растений
        when(plantController.listPlants(any(), any(), eq(0), eq(100)))
                .thenReturn(new PageResponsePlantDto(List.of(), 0, 0, 100));
        seedHomeView("""
                { "screenId":"home","version":1,"blocks":[
                  {"type":"weather_strip","minCatalogVersion":1},
                  {"type":"plant_grid","minCatalogVersion":1} ] }""", 1);

        // act
        Map<String, Object> screen = service.buildScreen("home", null);

        // assert — plant_grid заменён на empty_state с l10n-ключами и CTA-navigate
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) screen.get("blocks");
        assertThat(blocks).extracting(b -> b.get("type"))
                .containsExactly("weather_strip", "empty_state");
        Map<String, Object> empty = blocks.get(1);
        assertThat(empty).containsKeys("iconKey", "titleKey", "bodyKey", "ctaAction");
        @SuppressWarnings("unchecked")
        Map<String, Object> cta = (Map<String, Object>) empty.get("ctaAction");
        assertThat(cta).containsEntry("kind", "navigate").containsEntry("target", "/home/add");
    }

    @Test
    @DisplayName("should_render_plant_grid_when_garden_has_plants")
    void should_render_plant_grid_when_garden_has_plants() {
        // arrange — стаб BeforeEach: одно растение
        seedHomeView("""
                { "screenId":"home","version":1,"blocks":[
                  {"type":"plant_grid","minCatalogVersion":1} ] }""", 1);

        // act
        Map<String, Object> screen = service.buildScreen("home", null);

        // assert — обычная сетка, не empty_state
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) screen.get("blocks");
        assertThat(blocks).extracting(b -> b.get("type")).containsExactly("plant_grid");
        assertThat(blocks.get(0)).containsKey("plants");
    }

    private static TaskDto task(Long scheduleId, String domainTaskType) {
        return new TaskDto(
                scheduleId, 42L, "Фикус", domainTaskType,
                OffsetDateTime.of(2026, 5, 27, 9, 0, 0, 0, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("should_throw_when_screen_not_in_ui_views")
    void should_throw_when_screen_not_in_ui_views() {
        // arrange
        when(uiViewRepository.findByScreen("ghost")).thenReturn(Optional.empty());

        // act + assert
        assertThatThrownBy(() -> service.buildScreen("ghost", null))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("ghost");
    }
}
