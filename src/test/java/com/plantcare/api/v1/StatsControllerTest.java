package com.plantcare.api.v1;

import com.plantcare.api.auth.exception.AuthTokenException;
import com.plantcare.api.ApiExceptionHandler;
import com.plantcare.api.CurrentUserProvider;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.repository.PlantRepository;
import com.plantcare.core.service.CareHistoryService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Слайс-тест {@link StatsController}.
 */
@WebMvcTest(StatsController.class)
@Import(ApiExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class StatsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CareHistoryService careHistoryService;

    @MockBean
    private PlantRepository plantRepository;

    @MockBean
    private CurrentUserProvider currentUserProvider;

    // ===================== GET /api/v1/stats/streak =====================

    @Test
    void should_return_200_with_streak_when_plant_belongs_to_user() throws Exception {
        // arrange
        User user = mockUserWithId(1L, 1L);
        when(currentUserProvider.currentUser()).thenReturn(user);

        Plant plant = mock(Plant.class);
        when(plant.getId()).thenReturn(10L);
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 10L))
                .thenReturn(Optional.of(plant));
        when(careHistoryService.computePlantStreak(10L)).thenReturn(5);

        // act + assert
        mockMvc.perform(get("/api/v1/stats/streak")
                        .param("plantId", "10"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.plantId").value(10))
                .andExpect(jsonPath("$.streak").value(5));
    }

    @Test
    void should_return_200_with_streak_zero_when_no_history() throws Exception {
        // arrange
        User user = mockUserWithId(2L, 2L);
        when(currentUserProvider.currentUser()).thenReturn(user);

        Plant plant = mock(Plant.class);
        when(plant.getId()).thenReturn(20L);
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(2L, 20L))
                .thenReturn(Optional.of(plant));
        when(careHistoryService.computePlantStreak(20L)).thenReturn(0);

        // act + assert
        mockMvc.perform(get("/api/v1/stats/streak")
                        .param("plantId", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.streak").value(0));
    }

    @Test
    void should_return_404_when_plant_does_not_belong_to_user() throws Exception {
        // arrange
        User user = mockUserWithId(3L, 3L);
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(3L, 99L))
                .thenReturn(Optional.empty());

        // act + assert
        mockMvc.perform(get("/api/v1/stats/streak")
                        .param("plantId", "99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void should_return_400_when_plant_id_param_missing() throws Exception {
        // arrange
        User user = mockUserWithId(4L, 4L);
        when(currentUserProvider.currentUser()).thenReturn(user);

        // act + assert — параметр plantId отсутствует
        mockMvc.perform(get("/api/v1/stats/streak"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void should_return_401_when_no_authenticated_user() throws Exception {
        // arrange — нет JWT в SecurityContext
        when(currentUserProvider.currentUser())
                .thenThrow(AuthTokenException.invalid("No authenticated user in security context"));

        // act + assert
        mockMvc.perform(get("/api/v1/stats/streak")
                        .param("plantId", "10"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_INVALID"));
    }

    // ===================== helpers =====================

    private User mockUserWithId(Long id, Long chatId) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(id);
        when(user.getTelegramChatId()).thenReturn(chatId);
        when(user.getTimezone()).thenReturn("UTC");
        return user;
    }
}
