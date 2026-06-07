package com.plantcare.api.v1;

import com.plantcare.api.ApiExceptionHandler;
import com.plantcare.api.auth.exception.AuthTokenException;
import com.plantcare.api.generated.model.LocationDto;
import com.plantcare.api.generated.model.PageResponsePlantDto;
import com.plantcare.api.generated.model.PlantDto;
import com.plantcare.api.generated.model.TodayResponse;
import com.plantcare.api.generated.model.TodaySummary;
import com.plantcare.api.generated.model.WeatherSnapshotDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Слайс-тест {@link UiController} (SDUI-пилот, issue #284).
 *
 * <p>Сценарии: полная композиция home из 4 блоков с action-descriptor;
 * forward-compatibility по {@code X-UI-Catalog-Version}; отсутствие заголовка =
 * новейший клиент; отсутствие аутентификации → 401. Коллабораторы —
 * существующие контроллеры (моки), чтобы проверить именно SDUI-сборку, а не
 * их внутреннюю логику.
 */
@WebMvcTest(UiController.class)
@Import(ApiExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class UiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WeatherController weatherController;

    @MockitoBean
    private TodayController todayController;

    @MockitoBean
    private LocationController locationController;

    @MockitoBean
    private PlantController plantController;

    private void stubAllBlocks() {
        when(weatherController.getWeatherSnapshot()).thenReturn(
                new WeatherSnapshotDto(true)
                        .humidityPercent(82)
                        .recommendation(WeatherSnapshotDto.RecommendationEnum.DEFER_OK)
                        .fetchedAt(OffsetDateTime.of(2026, 5, 28, 12, 0, 0, 0, ZoneOffset.UTC))
                        .fromCache(false));

        when(todayController.getToday()).thenReturn(
                new TodayResponse(List.of(), 3, new TodaySummary(3, 1, 2, 1)));

        when(locationController.listLocations()).thenReturn(List.of(
                new LocationDto(5L, "Гостиная", true, true).emoji("🛋️")));

        when(plantController.listPlants(any(), any(), eq(0), eq(100))).thenReturn(
                new PageResponsePlantDto(
                        List.of(new PlantDto(10L, "Монстера", false).locationName("Гостиная")),
                        1, 0, 100));
    }

    @Test
    void should_return_full_home_layout_when_all_blocks_present() throws Exception {
        // arrange
        stubAllBlocks();

        // act + assert
        mockMvc.perform(get("/api/v1/ui/home"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.screenId").value("home"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.blocks.length()").value(4))
                // фиксированный порядок композиции
                .andExpect(jsonPath("$.blocks[0].type").value("weather_strip"))
                .andExpect(jsonPath("$.blocks[1].type").value("today_summary"))
                .andExpect(jsonPath("$.blocks[2].type").value("location_chips"))
                .andExpect(jsonPath("$.blocks[3].type").value("plant_grid"))
                // weather_strip данные
                .andExpect(jsonPath("$.blocks[0].available").value(true))
                .andExpect(jsonPath("$.blocks[0].humidityPercent").value(82))
                .andExpect(jsonPath("$.blocks[0].recommendation").value("DEFER_OK"))
                // today_summary данные
                .andExpect(jsonPath("$.blocks[1].total").value(3))
                .andExpect(jsonPath("$.blocks[1].done").value(1))
                .andExpect(jsonPath("$.blocks[1].remaining").value(2))
                .andExpect(jsonPath("$.blocks[1].overdue").value(1))
                // location_chips данные
                .andExpect(jsonPath("$.blocks[2].locations[0].id").value(5))
                .andExpect(jsonPath("$.blocks[2].locations[0].name").value("Гостиная"))
                .andExpect(jsonPath("$.blocks[2].locations[0].emoji").value("🛋️"))
                // plant_grid данные + action-descriptor
                .andExpect(jsonPath("$.blocks[3].plants[0].id").value(10))
                .andExpect(jsonPath("$.blocks[3].plants[0].name").value("Монстера"))
                .andExpect(jsonPath("$.blocks[3].plants[0].locationName").value("Гостиная"))
                .andExpect(jsonPath("$.blocks[3].plants[0].action.kind").value("log_care"))
                .andExpect(jsonPath("$.blocks[3].plants[0].action.method").value("POST"))
                .andExpect(jsonPath("$.blocks[3].plants[0].action.path").value("/care-events"))
                .andExpect(jsonPath("$.blocks[3].plants[0].action.payloadTemplate.plantId").value(10))
                .andExpect(jsonPath("$.blocks[3].plants[0].action.payloadTemplate.type").value("WATER"));
    }

    @Test
    void should_include_all_v1_blocks_when_client_catalog_version_is_1() throws Exception {
        // arrange — клиент знает каталог версии 1, все 4 блока = версия 1
        stubAllBlocks();

        // act + assert
        mockMvc.perform(get("/api/v1/ui/home").header("X-UI-Catalog-Version", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocks.length()").value(4))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void should_treat_missing_header_as_newest_client() throws Exception {
        // arrange — заголовок не передан → новейший клиент, все блоки текущей версии
        stubAllBlocks();

        // act + assert
        mockMvc.perform(get("/api/v1/ui/home"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocks.length()").value(4));
    }

    @Test
    void should_reject_catalog_version_below_minimum() throws Exception {
        // arrange — версия каталога < 1 невалидна (минимальная версия каталога = 1):
        // Bean Validation @Min(1) на заголовке отбивает запрос до контроллера.

        // act + assert
        mockMvc.perform(get("/api/v1/ui/home").header("X-UI-Catalog-Version", 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void should_return_401_when_no_authenticated_user() throws Exception {
        // arrange — нет JWT в SecurityContext: первый же блок дёргает контроллер,
        // который пробрасывает ошибку аутентификации
        when(weatherController.getWeatherSnapshot())
                .thenThrow(AuthTokenException.invalid("No authenticated user in security context"));

        // act + assert
        mockMvc.perform(get("/api/v1/ui/home"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_INVALID"));
    }
}
