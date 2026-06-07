package com.plantcare.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.plantcare.api.generated.model.LocationDto;
import com.plantcare.api.generated.model.PlantDto;
import com.plantcare.api.generated.model.TodayResponse;
import com.plantcare.api.generated.model.TodaySummary;
import com.plantcare.api.generated.model.WeatherSnapshotDto;
import com.plantcare.api.v1.LocationController;
import com.plantcare.api.v1.PlantController;
import com.plantcare.api.v1.TodayController;
import com.plantcare.api.v1.WeatherController;
import com.plantcare.core.repository.UiViewRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-Driven UI: сборка экрана из шаблона композиции {@code ui_views}
 * (issue #284).
 *
 * <p>Композиция (какие блоки и в каком порядке) берётся ИЗ таблицы
 * {@code ui_views} — строка по {@code screen} хранит шаблон в {@code layout_json}.
 * Сервис фильтрует блоки по {@code minCatalogVersion} (≤ присланной клиентом
 * {@code X-UI-Catalog-Version}) и ГИДРИРУЕТ динамические блоки данными из уже
 * существующих контроллеров/сервисов. Статичные блоки отдаются verbatim.
 *
 * <p>Ответ — опаковая структура {@code Map<String,Object>}: фронт рендерит
 * блоки по их {@code type}, бэкенд-контракт не типизирует блоки.
 *
 * <table border="1">
 *   <caption>Каталог блок-типов SDUI</caption>
 *   <tr><th>type</th><th>источник</th></tr>
 *   <tr><td>{@code weather_strip}</td><td>WeatherService</td></tr>
 *   <tr><td>{@code today_summary}</td><td>TodayApiService</td></tr>
 *   <tr><td>{@code location_chips}</td><td>LocationService</td></tr>
 *   <tr><td>{@code plant_grid}</td><td>PlantService</td></tr>
 * </table>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UiViewService {

    /**
     * Сколько растений отдаём в {@code plant_grid}. Берём первую страницу
     * максимального размера — главный экран не пагинирует сетку.
     */
    private static final int PLANT_GRID_LIMIT = 100;

    private static final String TYPE_WEATHER_STRIP = "weather_strip";
    private static final String TYPE_TODAY_SUMMARY = "today_summary";
    private static final String TYPE_LOCATION_CHIPS = "location_chips";
    private static final String TYPE_PLANT_GRID = "plant_grid";

    private final UiViewRepository uiViewRepository;
    private final WeatherController weatherController;
    private final TodayController todayController;
    private final LocationController locationController;
    private final PlantController plantController;

    /**
     * Собирает опаковую композицию экрана {@code screen}.
     *
     * @param screen             идентификатор экрана (строка {@code ui_views.screen})
     * @param clientCatalogVersion версия каталога блоков клиента; {@code null} —
     *                             новейший клиент (фильтрация по версии не применяется)
     * @return {@code { screenId, version, blocks: [...] }} с гидрированными данными
     * @throws EntityNotFoundException если экран не описан в {@code ui_views}
     */
    @Transactional(readOnly = true)
    public Map<String, Object> buildScreen(String screen, Integer clientCatalogVersion) {
        var view = uiViewRepository.findByScreen(screen)
                .orElseThrow(() -> new EntityNotFoundException("Unknown SDUI screen: " + screen));

        JsonNode layout = view.getLayoutJson();
        log.info("GET /api/v1/ui/{}: clientCatalogVersion={}", screen, clientCatalogVersion);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("screenId", layout.path("screenId").asText(screen));
        response.put("version", layout.path("version").asInt(view.getMinCatalogVersion()));

        List<Object> blocks = new ArrayList<>();
        for (JsonNode blockTemplate : layout.path("blocks")) {
            if (isBlockHidden(blockTemplate, clientCatalogVersion)) {
                continue;
            }
            Object rendered = renderBlock(blockTemplate);
            if (rendered != null) {
                blocks.add(rendered);
            }
        }
        response.put("blocks", blocks);
        return response;
    }

    /**
     * Блок скрыт, если клиент прислал версию каталога ниже
     * {@code minCatalogVersion} блока (forward-compatibility). При отсутствии
     * заголовка ({@code null}) блоки не фильтруются.
     */
    private boolean isBlockHidden(JsonNode blockTemplate, Integer clientCatalogVersion) {
        if (clientCatalogVersion == null) {
            return false;
        }
        int blockMinVersion = blockTemplate.path("minCatalogVersion").asInt(1);
        return blockMinVersion > clientCatalogVersion;
    }

    /**
     * Рендерит один блок шаблона: динамические типы гидрируются из сервисов,
     * прочие (статичные) отдаются verbatim как есть из шаблона.
     */
    private Object renderBlock(JsonNode blockTemplate) {
        String type = blockTemplate.path("type").asText(null);
        if (type == null) {
            log.warn("SDUI: пропущен блок без поля type: {}", blockTemplate);
            return null;
        }
        return switch (type) {
            case TYPE_WEATHER_STRIP -> weatherStripBlock();
            case TYPE_TODAY_SUMMARY -> todaySummaryBlock();
            case TYPE_LOCATION_CHIPS -> locationChipsBlock();
            case TYPE_PLANT_GRID -> plantGridBlock();
            // Статичный блок: отдаём шаблон verbatim (без гидрации).
            default -> staticBlock(blockTemplate);
        };
    }

    /** {@code weather_strip} — снимок микро-погоды из {@link WeatherController}. */
    private Map<String, Object> weatherStripBlock() {
        WeatherSnapshotDto snapshot = weatherController.getWeatherSnapshot();
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", TYPE_WEATHER_STRIP);
        block.put("available", snapshot.getAvailable());
        block.put("humidityPercent", snapshot.getHumidityPercent());
        block.put("recommendation",
                snapshot.getRecommendation() != null ? snapshot.getRecommendation().getValue() : null);
        block.put("fetchedAt", snapshot.getFetchedAt());
        block.put("fromCache", snapshot.getFromCache());
        return block;
    }

    /** {@code today_summary} — сводка задач на сегодня из {@link TodayController}. */
    private Map<String, Object> todaySummaryBlock() {
        TodayResponse today = todayController.getToday();
        TodaySummary summary = today.getSummary();
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", TYPE_TODAY_SUMMARY);
        block.put("total", summary.getTotal());
        block.put("done", summary.getDone());
        block.put("remaining", summary.getRemaining());
        block.put("overdue", summary.getOverdue());
        return block;
    }

    /** {@code location_chips} — локации пользователя из {@link LocationController}. */
    private Map<String, Object> locationChipsBlock() {
        List<Map<String, Object>> chips = locationController.listLocations().stream()
                .map(UiViewService::toChip)
                .toList();
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", TYPE_LOCATION_CHIPS);
        block.put("locations", chips);
        return block;
    }

    /** {@code plant_grid} — растения пользователя с action-descriptor «полить». */
    private Map<String, Object> plantGridBlock() {
        List<Map<String, Object>> cards =
                plantController.listPlants(null, null, 0, PLANT_GRID_LIMIT)
                        .getItems().stream()
                        .map(UiViewService::toCard)
                        .toList();
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", TYPE_PLANT_GRID);
        block.put("plants", cards);
        return block;
    }

    /**
     * Статичный блок: отдаём шаблон verbatim. Сериализуем JsonNode в опаковую
     * Map, чтобы клиент получил однородный JSON-объект.
     */
    private static Map<String, Object> staticBlock(JsonNode blockTemplate) {
        Map<String, Object> block = new LinkedHashMap<>();
        blockTemplate.fields().forEachRemaining(e -> block.put(e.getKey(), unwrap(e.getValue())));
        return block;
    }

    private static Map<String, Object> toChip(LocationDto location) {
        Map<String, Object> chip = new LinkedHashMap<>();
        chip.put("id", location.getId());
        chip.put("name", location.getName());
        chip.put("emoji", location.getEmoji());
        return chip;
    }

    private static Map<String, Object> toCard(PlantDto plant) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("id", plant.getId());
        card.put("name", plant.getName());
        card.put("locationName", plant.getLocationName());
        card.put("action", waterAction(plant.getId()));
        return card;
    }

    /**
     * Декларативное описание действия «полить»: клиент сам исполнит
     * {@code POST /care-events} с подставленным телом. Сервер действие не
     * выполняет — только описывает его.
     */
    private static Map<String, Object> waterAction(Long plantId) {
        Map<String, Object> payloadTemplate = new LinkedHashMap<>();
        payloadTemplate.put("plantId", plantId);
        payloadTemplate.put("type", "WATER");

        Map<String, Object> action = new LinkedHashMap<>();
        action.put("kind", "log_care");
        action.put("method", "POST");
        action.put("path", "/care-events");
        action.put("payloadTemplate", payloadTemplate);
        return action;
    }

    /** Рекурсивная распаковка JsonNode статичного блока в простые JSON-типы. */
    private static Object unwrap(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            node.fields().forEachRemaining(e -> map.put(e.getKey(), unwrap(e.getValue())));
            return map;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(child -> list.add(unwrap(child)));
            return list;
        }
        if (node.isInt() || node.isLong()) {
            return node.asLong();
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        return node.asText();
    }
}
