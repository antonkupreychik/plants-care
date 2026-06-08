package com.plantcare.api.v1;

import com.plantcare.api.ApiExceptionHandler;
import com.plantcare.api.auth.exception.AuthTokenException;
import com.plantcare.api.service.UiViewService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Слайс-тест {@link UiController} (SDUI, issue #284).
 *
 * <p>Контроллер тонкий: делегирует сборку в {@link UiViewService} и отдаёт
 * собранную опаковую структуру как JSON. Проверяем проброс {@code {screen}} и
 * {@code X-UI-Catalog-Version}, сериализацию опакового тела, валидацию заголовка
 * ({@code @Min(1)} → 400), 404 для неизвестного экрана и 401 без аутентификации.
 */
@WebMvcTest(UiController.class)
@Import(ApiExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class UiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UiViewService uiViewService;

    private static Map<String, Object> homeLayout() {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("kind", "log_care");
        Map<String, Object> plant = new LinkedHashMap<>();
        plant.put("id", 10L);
        plant.put("name", "Монстера");
        plant.put("action", action);
        Map<String, Object> grid = new LinkedHashMap<>();
        grid.put("type", "plant_grid");
        grid.put("plants", List.of(plant));
        Map<String, Object> weather = new LinkedHashMap<>();
        weather.put("type", "weather_strip");
        weather.put("available", true);

        Map<String, Object> layout = new LinkedHashMap<>();
        layout.put("screenId", "home");
        layout.put("version", 1);
        layout.put("blocks", List.of(weather, grid));
        return layout;
    }

    @Test
    void should_return_opaque_layout_when_screen_resolved() throws Exception {
        // arrange — без фильтра по комнате (locationId == null)
        when(uiViewService.buildScreen(eq("home"), isNull(), isNull())).thenReturn(homeLayout());

        // act + assert — опаковая структура отдаётся как JSON «как есть»
        mockMvc.perform(get("/api/v1/ui/home"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.screenId").value("home"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.blocks[0].type").value("weather_strip"))
                .andExpect(jsonPath("$.blocks[1].type").value("plant_grid"))
                .andExpect(jsonPath("$.blocks[1].plants[0].action.kind").value("log_care"));
    }

    @Test
    void should_pass_catalog_version_to_service() throws Exception {
        // arrange
        when(uiViewService.buildScreen(eq("home"), isNull(), eq(1))).thenReturn(homeLayout());

        // act + assert
        mockMvc.perform(get("/api/v1/ui/home").header("X-UI-Catalog-Version", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.screenId").value("home"));
    }

    @Test
    void should_pass_location_filter_to_service() throws Exception {
        // arrange — query-param locationId пробрасывается в сервис (issue #315)
        when(uiViewService.buildScreen(eq("home"), eq(5L), isNull())).thenReturn(homeLayout());

        // act + assert
        mockMvc.perform(get("/api/v1/ui/home").param("locationId", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.screenId").value("home"));
    }

    @Test
    void should_reject_catalog_version_below_minimum() throws Exception {
        // arrange — @Min(1) на заголовке отбивает запрос до контроллера

        // act + assert
        mockMvc.perform(get("/api/v1/ui/home").header("X-UI-Catalog-Version", 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void should_return_404_when_screen_unknown() throws Exception {
        // arrange
        when(uiViewService.buildScreen(eq("ghost"), isNull(), isNull()))
                .thenThrow(new EntityNotFoundException("Unknown SDUI screen: ghost"));

        // act + assert
        mockMvc.perform(get("/api/v1/ui/ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void should_return_401_when_no_authenticated_user() throws Exception {
        // arrange — гидрация дёргает контроллер, который пробрасывает ошибку аутентификации
        when(uiViewService.buildScreen(eq("home"), isNull(), isNull()))
                .thenThrow(AuthTokenException.invalid("No authenticated user in security context"));

        // act + assert
        mockMvc.perform(get("/api/v1/ui/home"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_INVALID"));
    }
}
