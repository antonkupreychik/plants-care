package com.plantcare.api.v1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plantcare.api.ApiExceptionHandler;
import com.plantcare.api.CurrentUserProvider;
import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.User;
import com.plantcare.core.service.LocationService;
import com.plantcare.core.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @WebMvcTest for {@link LocationController} — covers list, get, create, update (PATCH),
 * delete, activate, pause, resume, validation, 403/404/409 error paths (issue #85, #250).
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

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    private User currentUser;

    @BeforeEach
    void stubCurrentUser() {
        currentUser = mock(User.class);
        when(currentUser.getId()).thenReturn(1L);
        when(currentUser.getActiveLocation()).thenReturn(null);

        when(currentUserProvider.currentUserId()).thenReturn(1L);
        when(currentUserProvider.currentUser()).thenReturn(currentUser);
        when(userService.getByIdOrThrow(1L)).thenReturn(currentUser);
    }

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
        when(location.getPausedUntil()).thenReturn(null);

        return location;
    }

    // ------------------------------------------------------------------ GET tests

    @Test
    void should_return_locations_list() throws Exception {
        // arrange
        Location loc1 = mockLocation(1L, 1L, "Living Room", "🌿");
        Location loc2 = mockLocation(2L, 1L, "Bedroom", "🛏");

        when(locationService.getUserLocations(1L)).thenReturn(List.of(loc1, loc2));

        // act + assert
        mockMvc.perform(get("/api/v1/locations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Living Room"))
                .andExpect(jsonPath("$[1].name").value("Bedroom"));
    }

    @Test
    void should_return_locations_list_with_active_flag_set_correctly() throws Exception {
        // arrange — location 2 is the active one
        Location loc1 = mockLocation(1L, 1L, "Home", "🏠");
        Location loc2 = mockLocation(2L, 1L, "Office", "💼");
        Location activeLocation = mock(Location.class);
        when(activeLocation.getId()).thenReturn(2L);
        when(currentUser.getActiveLocation()).thenReturn(activeLocation);

        when(locationService.getUserLocations(1L)).thenReturn(List.of(loc1, loc2));

        // act + assert
        mockMvc.perform(get("/api/v1/locations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].isActive").value(false))
                .andExpect(jsonPath("$[1].isActive").value(true));
    }

    @Test
    void should_return_location_when_found() throws Exception {
        // arrange
        Location loc = mockLocation(1L, 1L, "Living Room", "🌿");

        when(locationService.getUserLocationOrThrow(1L, 1L)).thenReturn(loc);

        // act + assert
        mockMvc.perform(get("/api/v1/locations/1"))
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
        mockMvc.perform(get("/api/v1/locations/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void should_return_404_when_location_belongs_to_other_user() throws Exception {
        // arrange
        when(locationService.getUserLocationOrThrow(1L, 7L))
                .thenThrow(new IllegalArgumentException("Комната не найдена"));

        // act + assert
        mockMvc.perform(get("/api/v1/locations/7"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ------------------------------------------------------------------ CREATE tests

    @Test
    void should_create_location() throws Exception {
        // arrange
        Location created = mockLocation(5L, 1L, "Living Room", "🌿");

        when(locationService.createLocation(eq(currentUser), eq("Living Room"), eq("🌿")))
                .thenReturn(created);

        String body = """
                {"name": "Living Room", "emoji": "🌿"}
                """;

        // act + assert
        mockMvc.perform(post("/api/v1/locations")
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
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    // ------------------------------------------------------------------ UPDATE tests

    @Test
    void should_update_location_via_patch() throws Exception {
        // arrange
        Location updated = mockLocation(1L, 1L, "New Name", "🪴");

        when(locationService.updateLocation(eq(1L), eq(1L), eq("New Name"), isNull()))
                .thenReturn(updated);

        String body = """
                {"name": "New Name"}
                """;

        // act + assert — PATCH (not PUT)
        mockMvc.perform(patch("/api/v1/locations/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("New Name"));
    }

    // ------------------------------------------------------------------ DELETE tests

    @Test
    void should_delete_empty_location() throws Exception {
        // arrange — location has no plants
        Location loc = mockLocation(1L, 1L, "Empty Room", "🪴");

        when(locationService.getUserLocationOrThrow(1L, 1L)).thenReturn(loc);
        when(locationService.countPlantsInLocation(1L, 1L)).thenReturn(0L);
        doNothing().when(locationService).deleteEmptyLocation(1L, 1L);

        // act + assert
        mockMvc.perform(delete("/api/v1/locations/1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void should_return_409_when_location_not_empty() throws Exception {
        // arrange — location has plants (issue #250: DELETE returns 409, not 400)
        Location loc = mockLocation(1L, 1L, "Busy Room", "🌿");

        when(locationService.getUserLocationOrThrow(1L, 1L)).thenReturn(loc);
        when(locationService.countPlantsInLocation(1L, 1L)).thenReturn(2L);

        // act — DELETE without plants move = 409 LOCATION_NOT_EMPTY
        mockMvc.perform(delete("/api/v1/locations/1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("LOCATION_NOT_EMPTY"));
    }

    // ------------------------------------------------------------------ ACTIVATE tests

    @Test
    void should_activate_location() throws Exception {
        // arrange
        Location activated = mockLocation(3L, 1L, "Office", "💼");

        when(locationService.setActiveLocation(eq(currentUser), eq(3L))).thenReturn(activated);

        // act + assert
        mockMvc.perform(post("/api/v1/locations/3/activate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(3))
                .andExpect(jsonPath("$.isActive").value(true));
    }

    @Test
    void should_return_404_when_activating_nonexistent_location() throws Exception {
        // arrange
        when(locationService.setActiveLocation(eq(currentUser), eq(99L)))
                .thenThrow(new IllegalArgumentException("Комната не найдена"));

        // act + assert
        mockMvc.perform(post("/api/v1/locations/99/activate"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ------------------------------------------------------------------ PAUSE / RESUME tests

    @Test
    void should_pause_location_for_given_days() throws Exception {
        // arrange
        Location paused = mockLocation(1L, 1L, "Dacha", "🏡");
        Instant pausedUntil = Instant.now().plusSeconds(7 * 24 * 3600);
        when(paused.getPausedUntil()).thenReturn(pausedUntil);

        when(locationService.pauseLocation(eq(currentUser), eq(1L), eq(7))).thenReturn(paused);

        String body = """
                {"days": 7}
                """;

        // act + assert
        mockMvc.perform(post("/api/v1/locations/1/pause")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pausedUntil").isNotEmpty());
    }

    @Test
    void should_return_400_when_pause_days_out_of_range() throws Exception {
        // arrange — days must be 1..180
        String body = """
                {"days": 200}
                """;

        // act + assert
        mockMvc.perform(post("/api/v1/locations/1/pause")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void should_resume_paused_location() throws Exception {
        // arrange
        Location resumed = mockLocation(1L, 1L, "Dacha", "🏡");

        when(locationService.resumeLocation(eq(currentUser), eq(1L))).thenReturn(resumed);

        // act + assert
        mockMvc.perform(post("/api/v1/locations/1/resume"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.pausedUntil").doesNotExist());
    }
}
