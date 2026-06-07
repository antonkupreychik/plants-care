package com.plantcare.api.v1;

import com.plantcare.api.ApiExceptionHandler;
import com.plantcare.api.CurrentUserProvider;
import com.plantcare.core.domain.User;
import com.plantcare.core.service.AccountDeletionService;
import com.plantcare.core.service.UserProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} для {@link MeController#updateWeatherLocation} (issue #257).
 *
 * <p>REST-parity gap #1: установка координат для погодных эвристик.
 * {@code PATCH /me} писал только {@code weatherEnabled}, без координат
 * погода не работала. Этот эндпоинт закрывает пробел.
 */
@WebMvcTest(MeController.class)
@Import(ApiExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class WeatherLocationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserProfileService userProfileService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @MockitoBean
    private AccountDeletionService accountDeletionService;

    @BeforeEach
    void setUp() {
        User user = mock(User.class);
        when(user.getId()).thenReturn(1L);
        when(currentUserProvider.currentUser()).thenReturn(user);
    }

    @Test
    void should_return_204_when_valid_coordinates() throws Exception {
        // arrange
        doNothing().when(userProfileService)
                .setWeatherLocation(any(User.class), anyDouble(), anyDouble());

        // act + assert
        mockMvc.perform(put("/api/v1/me/weather-location")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lat\": 55.7558, \"lon\": 37.6176}"))
                .andExpect(status().isNoContent());

        verify(userProfileService).setWeatherLocation(any(User.class), eq(55.7558), eq(37.6176));
    }

    @Test
    void should_return_400_when_lat_missing() throws Exception {
        // arrange — lat обязательно (@NotNull из required: true)
        mockMvc.perform(put("/api/v1/me/weather-location")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lon\": 37.6176}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void should_return_400_when_lat_out_of_range() throws Exception {
        // arrange — lat > 90 нарушает @DecimalMax(90) / minimum/maximum в OpenAPI
        mockMvc.perform(put("/api/v1/me/weather-location")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lat\": 91.0, \"lon\": 37.6176}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void should_return_400_when_lon_out_of_range() throws Exception {
        // arrange — lon < -180 нарушает @DecimalMin(-180)
        mockMvc.perform(put("/api/v1/me/weather-location")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lat\": 55.0, \"lon\": -181.0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void should_handle_edge_coords_north_pole() throws Exception {
        // arrange — граничные значения lat=90, lon=180 допустимы
        doNothing().when(userProfileService)
                .setWeatherLocation(any(User.class), anyDouble(), anyDouble());

        mockMvc.perform(put("/api/v1/me/weather-location")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lat\": 90.0, \"lon\": 180.0}"))
                .andExpect(status().isNoContent());
    }
}
