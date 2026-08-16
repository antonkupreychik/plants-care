package com.plantcare.api.v1;

import com.plantcare.api.ApiExceptionHandler;
import com.plantcare.api.CurrentUserProvider;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.service.HealthScoreService;
import com.plantcare.core.service.PlantAcclimationService;
import com.plantcare.core.service.PlantService;
import com.plantcare.core.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} для {@link PlantController#disablePlantAcclimation} (issue #257).
 *
 * <p>REST-parity gap #3: выключить акклиматизацию через REST.
 * Раньше только бот умел отключать акклиматизацию.
 */
@WebMvcTest(PlantController.class)
@Import(ApiExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class PlantAcclimationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PlantService plantService;

    @MockitoBean
    private PlantAcclimationService plantAcclimationService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @MockitoBean
    private Clock clock;

    @MockitoBean
    private HealthScoreService healthScoreService;

    private User user;

    @BeforeEach
    void setUp() {
        user = mock(User.class);
        when(user.getId()).thenReturn(1L);
        when(currentUserProvider.currentUserId()).thenReturn(1L);
        when(userService.getByIdOrThrow(1L)).thenReturn(user);
    }

    @Test
    void should_return_204_when_acclimation_disabled() throws Exception {
        // arrange
        Plant plant = mock(Plant.class);
        when(plantAcclimationService.disable(eq(user), eq(42L))).thenReturn(plant);

        // act + assert
        mockMvc.perform(delete("/api/v1/plants/42/acclimation"))
                .andExpect(status().isNoContent());

        verify(plantAcclimationService).disable(eq(user), eq(42L));
    }

    @Test
    void should_return_204_when_acclimation_was_already_off_idempotent() throws Exception {
        // arrange — disable() идемпотентен: обнуляет поля, даже если они уже null
        Plant plant = mock(Plant.class);
        when(plantAcclimationService.disable(eq(user), eq(42L))).thenReturn(plant);

        // act + assert
        mockMvc.perform(delete("/api/v1/plants/42/acclimation"))
                .andExpect(status().isNoContent());
    }

    @Test
    void should_return_404_when_plant_not_found() throws Exception {
        // arrange — disable() возвращает null если растение не найдено или архивировано
        when(plantAcclimationService.disable(eq(user), eq(999L))).thenReturn(null);

        // act + assert
        mockMvc.perform(delete("/api/v1/plants/999/acclimation"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }
}
