package com.plantcare.bot.controller.api.v1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plantcare.bot.controller.api.ApiExceptionHandler;
import com.plantcare.bot.domain.Location;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.service.LocationService;
import com.plantcare.bot.service.UserService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @WebMvcTest for {@link LocationController} — covers list, get, create, update,
 * delete (empty and with target), validation, 403/404 error paths (issue #85).
 */
@WebMvcTest(LocationController.class)
@Import(ApiExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class LocationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private LocationService locationService;

    @MockitoBean
    private UserService userService;

    // ------------------------------------------------------------------ helpers

    private Location mockLocation(long id, long userId, String name, String emoji) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(userId);

        Location location = mock(Location.class);
        when(location.getId()).thenReturn(id);
        when(location.getName()).thenReturn(name);
        when(location.getEmoji()).thenReturn(emoji);
        when(location.isDefaultLocation()).thenReturn(false);
        when(location.getCreatedAt()).thenReturn(null);

        return location;
    }

    // ------------------------------------------------------------------ tests

    @Test
    void should_return_locations_list() throws Exception {
        // arrange
        Location loc1 = mockLocation(1L, 1L, "Living Room", "🌿");
        Location loc2 = mockLocation(2L, 1L, "Bedroom", "🛏");

        when(locationService.getUserLocations(1L)).thenReturn(List.of(loc1, loc2));

        // act + assert
        mockMvc.perform(get("/api/v1/locations")
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Living Room"))
                .andExpect(jsonPath("$[1].name").value("Bedroom"));
    }

    @Test
    void should_return_location_when_found() throws Exception {
        // arrange
        Location loc = mockLocation(1L, 1L, "Living Room", "🌿");

        when(locationService.getUserLocationOrThrow(1L, 1L)).thenReturn(loc);

        // act + assert
        mockMvc.perform(get("/api/v1/locations/1")
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Living Room"))
                .andExpect(jsonPath("$.emoji").value("🌿"));
    }

    @Test
    void should_return_404_when_location_not_found() throws Exception {
        // arrange — service throws IllegalArgumentException which controller converts to EntityNotFoundException → 404
        when(locationService.getUserLocationOrThrow(1L, 99L))
                .thenThrow(new IllegalArgumentException("Комната не найдена"));

        // act + assert
        mockMvc.perform(get("/api/v1/locations/99")
                        .header("X-User-Id", "1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void should_create_location() throws Exception {
        // arrange
        User user = User.builder().telegramChatId(1L).build();
        Location created = mockLocation(5L, 1L, "Living Room", "🌿");

        when(userService.getByIdOrThrow(1L)).thenReturn(user);
        when(locationService.createLocation(eq(user), eq("Living Room"), eq("🌿")))
                .thenReturn(created);

        String body = """
                {"name": "Living Room", "emoji": "🌿"}
                """;

        // act + assert
        mockMvc.perform(post("/api/v1/locations")
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.name").value("Living Room"));
    }

    @Test
    void should_return_400_when_location_name_blank() throws Exception {
        // arrange — @NotBlank on LocationCreateRequest.name
        String body = """
                {"name": ""}
                """;

        // act + assert
        mockMvc.perform(post("/api/v1/locations")
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("name"));
    }

    @Test
    void should_return_400_when_location_name_missing() throws Exception {
        // arrange
        String body = "{}";

        // act + assert
        mockMvc.perform(post("/api/v1/locations")
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void should_update_location() throws Exception {
        // arrange
        Location updated = mockLocation(1L, 1L, "New Name", "🪴");

        when(locationService.updateLocation(eq(1L), eq(1L), eq("New Name"), isNull()))
                .thenReturn(updated);

        String body = """
                {"name": "New Name"}
                """;

        // act + assert
        mockMvc.perform(put("/api/v1/locations/1")
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("New Name"));
    }

    @Test
    void should_delete_location_without_target() throws Exception {
        // arrange — location has no plants
        Location loc = mockLocation(1L, 1L, "Empty Room", "🪴");

        when(locationService.getUserLocationOrThrow(1L, 1L)).thenReturn(loc);
        when(locationService.countPlantsInLocation(1L, 1L)).thenReturn(0L);
        doNothing().when(locationService).deleteEmptyLocation(1L, 1L);

        // act + assert
        mockMvc.perform(delete("/api/v1/locations/1")
                        .header("X-User-Id", "1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void should_delete_location_with_target() throws Exception {
        // arrange — location has plants, target provided to move them
        Location loc = mockLocation(1L, 1L, "Room with plants", "🌱");

        when(locationService.getUserLocationOrThrow(1L, 1L)).thenReturn(loc);
        when(locationService.countPlantsInLocation(1L, 1L)).thenReturn(3L);
        doNothing().when(locationService).deleteLocation(1L, 1L, 2L);

        // act + assert
        mockMvc.perform(delete("/api/v1/locations/1?targetLocationId=2")
                        .header("X-User-Id", "1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void should_return_400_when_location_not_empty_and_no_target_provided() throws Exception {
        // arrange — location has plants but no targetLocationId in request
        Location loc = mockLocation(1L, 1L, "Busy Room", "🌿");

        when(locationService.getUserLocationOrThrow(1L, 1L)).thenReturn(loc);
        when(locationService.countPlantsInLocation(1L, 1L)).thenReturn(2L);

        // act — DELETE without targetLocationId while location has plants
        mockMvc.perform(delete("/api/v1/locations/1")
                        .header("X-User-Id", "1"))
                .andExpect(status().isBadRequest())
                // controller returns code "LOCATION_NOT_EMPTY" for this path
                .andExpect(jsonPath("$.error.code").value("LOCATION_NOT_EMPTY"));
    }

    @Test
    void should_return_404_when_location_belongs_to_other_user() throws Exception {
        // Cross-user access returns 404 (not found for this user); 403 is not produced by LocationService.
        // LocationService.getUserLocationOrThrow is userId-scoped: it throws IllegalArgumentException when
        // the location is not found for the given userId. LocationController.getLocationOrThrow converts
        // that IllegalArgumentException into EntityNotFoundException, which ApiExceptionHandler maps to 404.
        // arrange
        when(locationService.getUserLocationOrThrow(1L, 7L))
                .thenThrow(new IllegalArgumentException("Комната не найдена"));

        // act + assert
        mockMvc.perform(get("/api/v1/locations/7")
                        .header("X-User-Id", "1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }
}
