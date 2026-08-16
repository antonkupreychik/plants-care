package com.plantcare.api.v1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plantcare.api.ApiExceptionHandler;
import com.plantcare.api.CurrentUserProvider;
import com.plantcare.api.RoomNotEmptyException;
import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.Room;
import com.plantcare.core.domain.User;
import com.plantcare.core.service.RoomService;
import com.plantcare.core.service.UserService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @WebMvcTest for {@link RoomController} — covers list, create, update (PATCH),
 * delete, validation, 404/409 error paths (issue #283).
 */
@WebMvcTest(RoomController.class)
@Import(ApiExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class RoomControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private RoomService roomService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    private User currentUser;

    @BeforeEach
    void stubCurrentUser() {
        currentUser = mock(User.class);
        when(currentUser.getId()).thenReturn(1L);

        when(currentUserProvider.currentUserId()).thenReturn(1L);
        when(currentUserProvider.currentUser()).thenReturn(currentUser);
        when(userService.getByIdOrThrow(1L)).thenReturn(currentUser);
    }

    // ---------- helpers ----------

    private Room mockRoom(long id, long locationId, String name) {
        Location location = mock(Location.class);
        when(location.getId()).thenReturn(locationId);

        Room room = mock(Room.class);
        when(room.getId()).thenReturn(id);
        when(room.getName()).thenReturn(name);
        when(room.getLocation()).thenReturn(location);
        when(room.getDisplayOrder()).thenReturn(0);
        when(room.getCreatedAt()).thenReturn(null);
        return room;
    }

    // ---------- GET /api/v1/locations/{locationId}/rooms ----------

    @Test
    void should_return_rooms_list_when_location_exists() throws Exception {
        // arrange
        Room r1 = mockRoom(1L, 10L, "Подоконник");
        Room r2 = mockRoom(2L, 10L, "Угол");

        when(roomService.getRoomsInLocation(1L, 10L)).thenReturn(List.of(r1, r2));

        // act + assert
        mockMvc.perform(get("/api/v1/locations/10/rooms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Подоконник"))
                .andExpect(jsonPath("$[1].name").value("Угол"));
    }

    @Test
    void should_return_empty_list_when_no_rooms_in_location() throws Exception {
        // arrange
        when(roomService.getRoomsInLocation(1L, 10L)).thenReturn(List.of());

        // act + assert
        mockMvc.perform(get("/api/v1/locations/10/rooms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void should_return_404_when_location_not_found_on_list() throws Exception {
        // arrange
        when(roomService.getRoomsInLocation(1L, 99L))
                .thenThrow(new EntityNotFoundException("Location not found: 99"));

        // act + assert
        mockMvc.perform(get("/api/v1/locations/99/rooms"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ---------- POST /api/v1/locations/{locationId}/rooms ----------

    @Test
    void should_create_room_and_return_201() throws Exception {
        // arrange
        Room created = mockRoom(5L, 10L, "Кухня");

        when(roomService.createRoom(eq(currentUser), eq(10L), eq("Кухня"), isNull()))
                .thenReturn(created);

        String body = """
                {"name": "Кухня"}
                """;

        // act + assert
        mockMvc.perform(post("/api/v1/locations/10/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.name").value("Кухня"))
                .andExpect(jsonPath("$.locationId").value(10));
    }

    @Test
    void should_create_room_with_display_order() throws Exception {
        // arrange
        Room created = mockRoom(6L, 10L, "Спальня");

        when(roomService.createRoom(eq(currentUser), eq(10L), eq("Спальня"), eq(2)))
                .thenReturn(created);

        String body = """
                {"name": "Спальня", "displayOrder": 2}
                """;

        // act + assert
        mockMvc.perform(post("/api/v1/locations/10/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(6));
    }

    @Test
    void should_return_400_when_room_name_blank_on_create() throws Exception {
        // arrange
        String body = """
                {"name": ""}
                """;

        // act + assert
        mockMvc.perform(post("/api/v1/locations/10/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void should_return_400_when_room_name_missing_on_create() throws Exception {
        // arrange
        String body = "{}";

        // act + assert
        mockMvc.perform(post("/api/v1/locations/10/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void should_return_400_when_name_duplicate_on_create() throws Exception {
        // arrange
        when(roomService.createRoom(any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Комната с таким названием уже существует в этой локации"));

        String body = """
                {"name": "Кухня"}
                """;

        // act + assert
        mockMvc.perform(post("/api/v1/locations/10/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
    }

    @Test
    void should_return_404_when_location_not_found_on_create() throws Exception {
        // arrange
        when(roomService.createRoom(any(), any(), any(), any()))
                .thenThrow(new EntityNotFoundException("Location not found: 99"));

        String body = """
                {"name": "Кухня"}
                """;

        // act + assert
        mockMvc.perform(post("/api/v1/locations/99/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ---------- PATCH /api/v1/rooms/{id} ----------

    @Test
    void should_update_room_name_via_patch() throws Exception {
        // arrange
        Room updated = mockRoom(1L, 10L, "Новое название");

        when(roomService.updateRoom(eq(1L), eq(1L), eq("Новое название"), isNull()))
                .thenReturn(updated);

        String body = """
                {"name": "Новое название"}
                """;

        // act + assert
        mockMvc.perform(patch("/api/v1/rooms/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Новое название"));
    }

    @Test
    void should_update_display_order_via_patch() throws Exception {
        // arrange
        Room updated = mockRoom(1L, 10L, "Кухня");

        when(roomService.updateRoom(eq(1L), eq(1L), isNull(), eq(5)))
                .thenReturn(updated);

        String body = """
                {"displayOrder": 5}
                """;

        // act + assert
        mockMvc.perform(patch("/api/v1/rooms/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    @Test
    void should_return_404_when_room_not_found_on_update() throws Exception {
        // arrange
        when(roomService.updateRoom(eq(1L), eq(99L), any(), any()))
                .thenThrow(new EntityNotFoundException("Room not found: 99"));

        String body = """
                {"name": "Новое название"}
                """;

        // act + assert
        mockMvc.perform(patch("/api/v1/rooms/99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ---------- DELETE /api/v1/rooms/{id} ----------

    @Test
    void should_delete_empty_room_and_return_204() throws Exception {
        // arrange
        doNothing().when(roomService).deleteRoom(1L, 1L);

        // act + assert
        mockMvc.perform(delete("/api/v1/rooms/1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void should_return_409_when_room_not_empty_on_delete() throws Exception {
        // arrange
        doThrow(new RoomNotEmptyException("Комната содержит 2 активных растений. Перенесите их перед удалением."))
                .when(roomService).deleteRoom(1L, 1L);

        // act + assert
        mockMvc.perform(delete("/api/v1/rooms/1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ROOM_NOT_EMPTY"));
    }

    @Test
    void should_return_404_when_room_not_found_on_delete() throws Exception {
        // arrange
        doThrow(new EntityNotFoundException("Room not found: 99"))
                .when(roomService).deleteRoom(1L, 99L);

        // act + assert
        mockMvc.perform(delete("/api/v1/rooms/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }
}
