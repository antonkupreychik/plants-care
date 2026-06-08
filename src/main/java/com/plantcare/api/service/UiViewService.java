package com.plantcare.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.plantcare.api.generated.model.LocationDto;
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
 *   <tr><td>{@code today_tasks}</td><td>TodayApiService (тапабельный список)</td></tr>
 *   <tr><td>{@code location_chips}</td><td>LocationService</td></tr>
 *   <tr><td>{@code plant_grid}</td><td>PlantService</td></tr>
 *   <tr><td>{@code guest_banner}</td><td>инъекция сервиса (юзер-гость, MADR-017)</td></tr>
 *   <tr><td>{@code empty_state}</td><td>инъекция сервиса (пустой сад, MADR-017)</td></tr>
 * </table>
 *
 * <h2>MADR-017: серверно-управляемое поведение (всё опаково)</h2>
 * <ul>
 *   <li><b>Серверная видимость блоков.</b> Композиция/порядок берутся из таблицы
 *       {@code ui_views}, но два блока ВИДИМОСТИ сервис инъектирует условно по
 *       состоянию юзера (в таблицу не выносятся — это бизнес-правило):
 *       {@code guest_banner} добавляется ВВЕРХ (после {@code weather_strip}),
 *       если юзер — ГОСТЬ; {@code plant_grid} ЗАМЕНЯЕТСЯ на {@code empty_state},
 *       если у юзера нет растений. Поля {@code empty_state}/{@code guest_banner} —
 *       l10n-КЛЮЧИ ({@code iconKey/titleKey/bodyKey}), не готовый текст.</li>
 *   <li><b>Декларативные действия (navigate / log_care).</b> Карточка
 *       {@code plant_grid} несёт два действия: {@code action} (тап по телу) —
 *       {@code navigate} к витринному пути {@code /plants/{id}} из допустимого
 *       словаря путей; {@code waterAction} (кнопка «полить») — {@code log_care}
 *       на {@code POST /care-events}.</li>
 *   <li><b>Декларативная инвалидация.</b> Действия-мутации несут поле
 *       {@code invalidates} — массив ЛОГИЧЕСКИХ ключей того, что клиенту обновить
 *       после успеха. Словарь ключей: {@code home} (главный экран целиком),
 *       {@code today} (срез задач на сегодня), {@code plant} (детали растения).
 *       Это НЕ имена клиентских провайдеров и НЕ эндпоинты. Применено:
 *       {@code waterAction} → {@code ["home", "today"]}.</li>
 * </ul>
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
    private static final String TYPE_TODAY_TASKS = "today_tasks";
    private static final String TYPE_LOCATION_CHIPS = "location_chips";
    private static final String TYPE_PLANT_GRID = "plant_grid";
    private static final String TYPE_GUEST_BANNER = "guest_banner";
    private static final String TYPE_EMPTY_STATE = "empty_state";

    /** Логические ключи инвалидации (словарь MADR-017). Не провайдеры, не эндпоинты. */
    private static final String INVALIDATE_HOME = "home";
    private static final String INVALIDATE_TODAY = "today";

    private final UiViewRepository uiViewRepository;
    private final CurrentUserProvider currentUserProvider;
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

        // MADR-017: серверная видимость — гостю инъектируем guest_banner ВВЕРХ
        // (сразу после weather_strip, если он есть; иначе первым). Композиция в
        // таблице статична — этот блок динамичен по юзеру, потому живёт в коде.
        if (currentUserProvider.currentUser().isGuest()) {
            blocks.add(guestBannerInsertIndex(blocks), guestBannerBlock());
        }

        response.put("blocks", blocks);
        return response;
    }

    /**
     * Индекс вставки {@code guest_banner}: сразу после {@code weather_strip}
     * (если первый блок — погодная полоса), иначе самым первым.
     */
    private static int guestBannerInsertIndex(List<Object> blocks) {
        if (!blocks.isEmpty() && blocks.get(0) instanceof Map<?, ?> first
                && TYPE_WEATHER_STRIP.equals(first.get("type"))) {
            return 1;
        }
        return 0;
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
            case TYPE_TODAY_TASKS -> todayTasksBlock();
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

    /**
     * {@code today_tasks} — тапабельный список задач на сегодня из
     * {@link TodayController} (тот же источник, что {@code GET /today}) плюс
     * прогресс ({@code completedCount}/{@code totalCount}).
     *
     * <p>Возвращает обогащённый блок взамен счётчикового {@code today_summary}:
     * на home нужен интерактив — тап задачи открывает нативный sheet ухода,
     * для предвыбора типа достаточно нормализованного {@code type}.
     *
     * <p>{@code type} НОРМАЛИЗОВАН в публичный словарь {@code WATER/SPRAY/
     * FERTILIZE/SOIL_CHECK} (как action-descriptor {@code plant_grid}), а не
     * во внутренний {@code TaskType} ({@code WATERING/MISTING/...}).
     */
    private Map<String, Object> todayTasksBlock() {
        TodayResponse today = todayController.getToday();
        TodaySummary summary = today.getSummary();

        List<Map<String, Object>> tasks = today.getTasks().stream()
                .map(UiViewService::toTodayTask)
                .toList();

        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", TYPE_TODAY_TASKS);
        block.put("completedCount", summary.getDone());
        block.put("totalCount", summary.getTotal());
        block.put("tasks", tasks);
        return block;
    }

    /**
     * Маппинг {@link TaskDto} → запись задачи блока {@code today_tasks}.
     * {@code dueAt} — ISO-8601 UTC ({@code nextDueAt}). {@code type}
     * нормализуется через {@link #normalizeTaskType(String)}.
     */
    private static Map<String, Object> toTodayTask(TaskDto task) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("scheduleId", task.getScheduleId());
        entry.put("plantId", task.getPlantId());
        entry.put("plantName", task.getPlantName());
        entry.put("type", normalizeTaskType(task.getTaskType()));
        entry.put("dueAt", task.getNextDueAt());
        return entry;
    }

    /**
     * Нормализация внутреннего {@code TaskType} в публичный словарь типов ухода
     * ({@code CareEventType}). «Ловушка контракта»: доменные имена
     * ({@code WATERING/MISTING/FERTILIZING}) НЕ совпадают с публичными
     * ({@code WATER/SPRAY/FERTILIZE}) — мапим здесь, на бэкенде, как в
     * {@code CareEventController.fromTaskType}. {@code SOIL_CHECK} совпадает.
     * Неизвестный тип отдаём как есть (forward-compat).
     */
    private static String normalizeTaskType(String domainTaskType) {
        if (domainTaskType == null) {
            return null;
        }
        return switch (domainTaskType) {
            case "WATERING" -> "WATER";
            case "MISTING" -> "SPRAY";
            case "FERTILIZING" -> "FERTILIZE";
            case "SOIL_CHECK" -> "SOIL_CHECK";
            default -> domainTaskType;
        };
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

    /**
     * {@code plant_grid} — растения пользователя с двумя action-descriptor'ами:
     * {@code action} (тап → navigate к карточке) и {@code waterAction}
     * (кнопка «полить» → log_care).
     *
     * <p>MADR-017: если у юзера ПУСТОЙ САД, сервис вместо сетки отдаёт блок
     * {@code empty_state} (серверная видимость по состоянию юзера). Пустоту
     * определяем по списку {@code plant_grid} того же запроса (без лишнего
     * count-запроса): главный экран и так грузит первую страницу.
     */
    private Map<String, Object> plantGridBlock() {
        List<PlantDto> plants = plantController.listPlants(null, null, 0, PLANT_GRID_LIMIT).getItems();
        if (plants.isEmpty()) {
            return emptyStateBlock();
        }
        List<Map<String, Object>> cards = plants.stream()
                .map(UiViewService::toCard)
                .toList();
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", TYPE_PLANT_GRID);
        block.put("plants", cards);
        return block;
    }

    /**
     * {@code empty_state} — заглушка пустого сада взамен {@code plant_grid}
     * (MADR-017). Поля — l10n-КЛЮЧИ для клиента (не готовый текст). CTA —
     * декларативный {@code navigate} к нативному флоу добавления растения.
     */
    private static Map<String, Object> emptyStateBlock() {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", TYPE_EMPTY_STATE);
        block.put("iconKey", "home.empty.icon");
        block.put("titleKey", "home.empty.title");
        block.put("bodyKey", "home.empty.body");
        block.put("ctaAction", navigateAction("/home/add"));
        return block;
    }

    /**
     * {@code guest_banner} — баннер для гостевого аккаунта (MADR-017). Опаковый
     * блок с минимальными полями-КЛЮЧАМИ; готовый текст — на клиенте. CTA —
     * декларативный {@code navigate} к нативному флоу конвертации гостя.
     */
    private static Map<String, Object> guestBannerBlock() {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", TYPE_GUEST_BANNER);
        block.put("titleKey", "home.guest.title");
        block.put("bodyKey", "home.guest.body");
        block.put("ctaAction", navigateAction("/profile/convert-guest"));
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
        // MADR-017: тап по телу карточки → навигация к витрине растения;
        // явная кнопка «полить» → отдельное log_care-действие.
        card.put("action", plantCardNavigateAction(plant.getId()));
        card.put("waterAction", waterAction(plant.getId()));
        return card;
    }

    /**
     * Декларативное описание действия «полить»: клиент сам исполнит
     * {@code POST /care-events} с подставленным телом. Сервер действие не
     * выполняет — только описывает его.
     *
     * <p>MADR-017: несёт {@code invalidates} — логические ключи того, что
     * клиенту обновить после успеха. Полив меняет и главный экран целиком
     * ({@code home}), и срез задач на сегодня ({@code today}).
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
        action.put("invalidates", List.of(INVALIDATE_HOME, INVALIDATE_TODAY));
        return action;
    }

    /**
     * Действие «навигация к карточке растения» (MADR-017). Путь строится из
     * допустимого словаря витринных путей ({@code /home/plants/{id}}), не из
     * произвольного URL. Префикс {@code /home} обязателен: маршрут карточки у
     * клиента вложен в home-shell ({@code /home/plants/:id}); без него
     * go_router не находит маршрут и тап молча игнорируется.
     */
    private static Map<String, Object> plantCardNavigateAction(Long plantId) {
        return navigateAction("/home/plants/" + plantId);
    }

    /**
     * Декларативное {@code navigate}-действие к витринному/нативному пути.
     * Опаково для клиента: {@code { kind: navigate, target }}.
     */
    private static Map<String, Object> navigateAction(String target) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("kind", "navigate");
        action.put("target", target);
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
