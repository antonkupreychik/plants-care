package com.plantcare.bot.controller.api.v1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plantcare.bot.controller.api.ApiExceptionHandler;
import com.plantcare.bot.domain.Location;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import com.plantcare.bot.service.LocationService;
import com.plantcare.bot.service.PlantService;
import com.plantcare.bot.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @WebMvcTest for {@link PlantController} — covers all CRUD endpoints, validation,
 * ownership checks, and soft-delete semantics (issue #85).
 *
 * <p>Filters disabled so {@link org.springframework.security.access.AccessDeniedException}
 * reaches the {@link ApiExceptionHandler} advice instead of being intercepted by
 * Spring Security's {@code ExceptionTranslationFilter}.
 */
@WebMvcTest(PlantController.class)
@Import(ApiExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class PlantControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PlantService plantService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private LocationService locationService;

    // ------------------------------------------------------------------ helpers

    /**
     * Builds a Plant mock where {@code plant.getUser().getId()} returns {@code userId}
     * and the plant is not archived (archivedAt == null).
     */
    private Plant mockPlant(long plantId, long userId, String name) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(userId);

        Location location = mock(Location.class);
        when(location.getId()).thenReturn(1L);
        when(location.getName()).thenReturn("Living room");

        Plant plant = mock(Plant.class);
        when(plant.getId()).thenReturn(plantId);
        when(plant.getName()).thenReturn(name);
        when(plant.getNotes()).thenReturn(null);
        when(plant.getPhotoFileId()).thenReturn(null);
        when(plant.getLocation()).thenReturn(location);
        when(plant.getSpecies()).thenReturn(null);
        when(plant.isArchived()).thenReturn(false);
        when(plant.getArchivedAt()).thenReturn(null);
        when(plant.getCreatedAt()).thenReturn(null);
        when(plant.getUser()).thenReturn(user);

        return plant;
    }

    // ------------------------------------------------------------------ tests

    @Test
    void should_return_plants_page_when_listing_all() throws Exception {
        // arrange
        Plant p1 = mockPlant(1L, 1L, "Ficus");
        Plant p2 = mockPlant(2L, 1L, "Monstera");

        when(plantService.listPlants(eq(1L), isNull(), eq(0), eq(20)))
                .thenReturn(List.of(p1, p2));
        when(plantService.countPlants(1L, null)).thenReturn(2L);

        // act + assert
        mockMvc.perform(get("/api/v1/plants")
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.offset").value(0))
                .andExpect(jsonPath("$.limit").value(20));
    }

    @Test
    void should_return_plants_filtered_by_location() throws Exception {
        // arrange
        Plant p = mockPlant(1L, 1L, "Ficus");

        when(plantService.listPlants(eq(1L), eq(5L), eq(0), eq(20)))
                .thenReturn(List.of(p));
        when(plantService.countPlants(1L, 5L)).thenReturn(1L);

        // act + assert
        mockMvc.perform(get("/api/v1/plants?locationId=5")
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Ficus"));
    }

    @Test
    void should_return_plant_when_found() throws Exception {
        // arrange
        Plant plant = mockPlant(1L, 1L, "Ficus");

        when(plantService.getPlantOrThrow(1L, 1L)).thenReturn(plant);

        // act + assert
        mockMvc.perform(get("/api/v1/plants/1")
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Ficus"));
    }

    @Test
    void should_return_404_when_plant_not_found() throws Exception {
        // arrange — service throws EntityNotFoundException → handler maps to 404
        when(plantService.getPlantOrThrow(1L, 999L))
                .thenThrow(new EntityNotFoundException("Plant not found: 999"));

        // act + assert
        mockMvc.perform(get("/api/v1/plants/999")
                        .header("X-User-Id", "1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void should_create_plant() throws Exception {
        // arrange
        User user = User.builder().telegramChatId(1L).build();
        Plant plant = mockPlant(10L, 1L, "Ficus");

        when(userService.getByIdOrThrow(1L)).thenReturn(user);
        when(plantService.createPlant(eq(user), eq("Ficus"), isNull(), isNull()))
                .thenReturn(plant);

        String body = """
                {"name": "Ficus"}
                """;

        // act + assert
        mockMvc.perform(post("/api/v1/plants")
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.name").value("Ficus"));
    }

    @Test
    void should_return_400_when_name_blank() throws Exception {
        // arrange
        String body = """
                {"name": ""}
                """;

        // act + assert
        mockMvc.perform(post("/api/v1/plants")
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("name"));
    }

    @Test
    void should_return_400_when_name_missing() throws Exception {
        // arrange — no "name" field in body at all
        String body = "{}";

        // act + assert
        mockMvc.perform(post("/api/v1/plants")
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void should_return_400_when_name_too_long() throws Exception {
        // arrange — name exceeds @Size(max = 100)
        String longName = "X".repeat(101);
        String body = objectMapper.writeValueAsString(java.util.Map.of("name", longName));

        // act + assert
        mockMvc.perform(post("/api/v1/plants")
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void should_update_plant() throws Exception {
        // arrange
        Plant updated = mockPlant(1L, 1L, "Updated");

        when(plantService.updatePlant(eq(1L), eq(1L), eq("Updated"), isNull(), isNull()))
                .thenReturn(updated);

        String body = """
                {"name": "Updated"}
                """;

        // act + assert
        mockMvc.perform(put("/api/v1/plants/1")
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated"));
    }

    @Test
    void should_delete_plant_and_return_204() throws Exception {
        // arrange
        doNothing().when(plantService).archivePlant(1L, 1L);

        // act + assert
        mockMvc.perform(delete("/api/v1/plants/1")
                        .header("X-User-Id", "1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void should_return_403_when_plant_belongs_to_other_user() throws Exception {
        // arrange — service throws AccessDeniedException when plant belongs to another user
        when(plantService.getPlantOrThrow(1L, 1L))
                .thenThrow(new AccessDeniedException("Access denied"));

        // act + assert
        mockMvc.perform(get("/api/v1/plants/1")
                        .header("X-User-Id", "1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
    }
}
