package com.plantcare.api.v1;

import com.plantcare.api.generated.UiApi;
import com.plantcare.api.generated.model.ActionDescriptor;
import com.plantcare.api.generated.model.Block;
import com.plantcare.api.generated.model.BlockType;
import com.plantcare.api.generated.model.LocationChipsBlock;
import com.plantcare.api.generated.model.LocationDto;
import com.plantcare.api.generated.model.PlantDto;
import com.plantcare.api.generated.model.PlantGridBlock;
import com.plantcare.api.generated.model.ScreenLayout;
import com.plantcare.api.generated.model.TodayResponse;
import com.plantcare.api.generated.model.TodaySummary;
import com.plantcare.api.generated.model.TodaySummaryBlock;
import com.plantcare.api.generated.model.UiLocationChip;
import com.plantcare.api.generated.model.UiPlantCard;
import com.plantcare.api.generated.model.WeatherSnapshotDto;
import com.plantcare.api.generated.model.WeatherStripBlock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Server-Driven UI: серверная композиция экранов из блоков (issue #284, SDUI-пилот).
 *
 * <p>Пилотный эндпоинт {@code GET /api/v1/ui/home} собирает главный экран как
 * {@link ScreenLayout} — фиксированный список {@link Block}'ов в порядке
 * {@code weather_strip → today_summary → location_chips → plant_grid}. Каждый
 * блок несёт ТОЛЬКО данные; вёрстку и логику применяет клиент.
 *
 * <p><b>Переиспользование сервисного слоя.</b> Никакой новой бизнес-логики:
 * данные блоков берутся через уже существующие REST-контроллеры
 * ({@link WeatherController}, {@link TodayController}, {@link LocationController},
 * {@link PlantController}), которые делегируют в свои сервисы. Это гарантирует
 * идентичное поведение с обычными эндпоинтами {@code /weather/snapshot},
 * {@code /today}, {@code /locations}, {@code /plants}.
 *
 * <p><b>Каталог блок-типов и версии (forward-compatibility).</b> Клиент
 * присылает версию своего каталога блоков в заголовке
 * {@code X-UI-Catalog-Version}. Сервер НЕ включает в ответ блоки, чья версия
 * каталога выше присланной — старый клиент не получает незнакомых типов. На
 * старте все 4 типа имеют версию каталога {@code 1}:
 *
 * <table border="1">
 *   <caption>Каталог блок-типов SDUI</caption>
 *   <tr><th>type</th><th>catalogVersion</th><th>источник</th></tr>
 *   <tr><td>{@code weather_strip}</td><td>1</td><td>WeatherService</td></tr>
 *   <tr><td>{@code today_summary}</td><td>1</td><td>TodayApiService</td></tr>
 *   <tr><td>{@code location_chips}</td><td>1</td><td>LocationService</td></tr>
 *   <tr><td>{@code plant_grid}</td><td>1</td><td>PlantService</td></tr>
 * </table>
 *
 * <p>Документация и mapping живут в сгенерированном {@link UiApi} (см. ui.yaml).
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class UiController implements UiApi {

    /** Идентификатор пилотного экрана. */
    static final String HOME_SCREEN_ID = "home";

    /** Текущая версия каталога блок-типов на сервере. */
    static final int CATALOG_VERSION = 1;

    /** Версия каталога, начиная с которой существует каждый тип блока. */
    private static final int V1 = 1;

    /**
     * Сколько растений отдаём в {@code plant_grid}. Берём первую страницу
     * максимального размера — главный экран не пагинирует сетку.
     */
    private static final int PLANT_GRID_LIMIT = 100;

    private final WeatherController weatherController;
    private final TodayController todayController;
    private final LocationController locationController;
    private final PlantController plantController;

    @Override
    public ScreenLayout getUiHome(Integer xUiCatalogVersion) {
        // null заголовок = «новейший клиент»: отдаём все блоки текущей версии.
        int clientCatalogVersion = xUiCatalogVersion != null ? xUiCatalogVersion : CATALOG_VERSION;
        log.info("GET /api/v1/ui/home: clientCatalogVersion={}", clientCatalogVersion);

        List<Block> blocks = new ArrayList<>();

        // Фиксированная композиция home. Каждый блок добавляется только если его
        // версия каталога не выше присланной клиентом (forward-compatibility).
        if (V1 <= clientCatalogVersion) {
            blocks.add(weatherStripBlock());
        }
        if (V1 <= clientCatalogVersion) {
            blocks.add(todaySummaryBlock());
        }
        if (V1 <= clientCatalogVersion) {
            blocks.add(locationChipsBlock());
        }
        if (V1 <= clientCatalogVersion) {
            blocks.add(plantGridBlock());
        }

        return new ScreenLayout(HOME_SCREEN_ID, CATALOG_VERSION, blocks);
    }

    /** {@code weather_strip} — снимок микро-погоды из {@link WeatherController}. */
    private WeatherStripBlock weatherStripBlock() {
        WeatherSnapshotDto snapshot = weatherController.getWeatherSnapshot();

        WeatherStripBlock block = new WeatherStripBlock(snapshot.getAvailable(), BlockType.WEATHER_STRIP)
                .humidityPercent(snapshot.getHumidityPercent())
                .fetchedAt(snapshot.getFetchedAt())
                .fromCache(snapshot.getFromCache());

        if (snapshot.getRecommendation() != null) {
            block.recommendation(
                    WeatherStripBlock.RecommendationEnum.fromValue(snapshot.getRecommendation().getValue()));
        }
        return block;
    }

    /** {@code today_summary} — сводка задач на сегодня из {@link TodayController}. */
    private TodaySummaryBlock todaySummaryBlock() {
        TodayResponse today = todayController.getToday();
        TodaySummary summary = today.getSummary();

        return new TodaySummaryBlock(
                summary.getTotal(),
                summary.getDone(),
                summary.getRemaining(),
                summary.getOverdue(),
                BlockType.TODAY_SUMMARY);
    }

    /** {@code location_chips} — локации пользователя из {@link LocationController}. */
    private LocationChipsBlock locationChipsBlock() {
        List<UiLocationChip> chips = locationController.listLocations().stream()
                .map(UiController::toChip)
                .toList();
        return new LocationChipsBlock(chips, BlockType.LOCATION_CHIPS);
    }

    /** {@code plant_grid} — растения пользователя с action-descriptor «полить». */
    private PlantGridBlock plantGridBlock() {
        List<UiPlantCard> cards = plantController.listPlants(null, null, 0, PLANT_GRID_LIMIT)
                .getItems().stream()
                .map(UiController::toCard)
                .toList();
        return new PlantGridBlock(cards, BlockType.PLANT_GRID);
    }

    private static UiLocationChip toChip(LocationDto location) {
        return new UiLocationChip(location.getId(), location.getName())
                .emoji(location.getEmoji());
    }

    private static UiPlantCard toCard(PlantDto plant) {
        return new UiPlantCard(plant.getId(), plant.getName(), waterAction(plant.getId()))
                .locationName(plant.getLocationName());
    }

    /**
     * Декларативное описание действия «полить»: клиент сам исполнит
     * {@code POST /care-events} с подставленным телом. Сервер действие не
     * выполняет — только описывает его.
     */
    private static ActionDescriptor waterAction(Long plantId) {
        Map<String, Object> payloadTemplate = new java.util.LinkedHashMap<>();
        payloadTemplate.put("plantId", plantId);
        payloadTemplate.put("type", "WATER");

        return new ActionDescriptor("log_care", "POST", "/care-events", payloadTemplate);
    }
}
