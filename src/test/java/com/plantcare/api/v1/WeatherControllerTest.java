package com.plantcare.api.v1;

import com.plantcare.api.ApiExceptionHandler;
import com.plantcare.api.CurrentUserProvider;
import com.plantcare.api.auth.exception.AuthTokenException;
import com.plantcare.core.domain.User;
import com.plantcare.core.weather.dto.HumidityInfo;
import com.plantcare.core.weather.dto.WeatherRecommendation;
import com.plantcare.core.weather.service.WeatherService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Слайс-тест {@link WeatherController} (mobile gap G4).
 *
 * <p>Сценарии: данные есть → 200 с полями; погода не настроена → 200 с
 * {@code available=false} и пустыми полями; нет аутентификации → 401.
 */
@WebMvcTest(WeatherController.class)
@Import(ApiExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class WeatherControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WeatherService weatherService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @Test
    void should_return_snapshot_when_weather_available() throws Exception {
        // arrange
        User user = mock(User.class);
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(weatherService.getCurrentHumidity(user))
                .thenReturn(Optional.of(new HumidityInfo(
                        82, WeatherRecommendation.DEFER_OK,
                        LocalDateTime.of(2026, 5, 28, 12, 0), /* fromCache */ false)));

        // act + assert
        mockMvc.perform(get("/api/v1/weather/snapshot"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.humidityPercent").value(82))
                .andExpect(jsonPath("$.recommendation").value("DEFER_OK"))
                .andExpect(jsonPath("$.fromCache").value(false))
                .andExpect(jsonPath("$.fetchedAt").value("2026-05-28T12:00:00Z"));
    }

    @Test
    void should_return_unavailable_when_weather_not_configured() throws Exception {
        // arrange — погода не настроена / источник недоступен → Optional.empty()
        User user = mock(User.class);
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(weatherService.getCurrentHumidity(user)).thenReturn(Optional.empty());

        // act + assert
        mockMvc.perform(get("/api/v1/weather/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.humidityPercent").isEmpty())
                .andExpect(jsonPath("$.recommendation").isEmpty())
                .andExpect(jsonPath("$.fetchedAt").isEmpty());
    }

    @Test
    void should_return_401_when_no_authenticated_user() throws Exception {
        // arrange — нет JWT в SecurityContext
        when(currentUserProvider.currentUser())
                .thenThrow(AuthTokenException.invalid("No authenticated user in security context"));

        // act + assert
        mockMvc.perform(get("/api/v1/weather/snapshot"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_INVALID"));
    }
}
