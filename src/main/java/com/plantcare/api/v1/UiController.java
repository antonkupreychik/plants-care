package com.plantcare.api.v1;

import com.plantcare.api.generated.UiApi;
import com.plantcare.api.service.UiViewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Server-Driven UI: серверная композиция экранов из блоков (issue #284).
 *
 * <p>Эндпоинт {@code GET /api/v1/ui/{screen}} собирает экран по шаблону
 * композиции из таблицы {@code ui_views} и гидрирует динамические блоки данными
 * из существующих сервисов. Ответ — ОПАКОВЫЙ JSON-объект
 * ({@code Map<String,Object>}), а не типизированный DTO: состав/порядок блоков
 * управляется сервером, клиент рендерит блоки по их {@code type}.
 *
 * <p>Вся сборка — в {@link UiViewService}; контроллер тонкий. Пилотный экран —
 * {@code home}.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class UiController implements UiApi {

    private final UiViewService uiViewService;

    @Override
    public Map<String, Object> getUiScreen(String screen, Integer xUiCatalogVersion) {
        return uiViewService.buildScreen(screen, xUiCatalogVersion);
    }
}
