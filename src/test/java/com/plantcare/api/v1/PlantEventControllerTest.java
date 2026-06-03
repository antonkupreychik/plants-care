package com.plantcare.api.v1;

import com.plantcare.api.ApiExceptionHandler;
import com.plantcare.api.CurrentUserProvider;
import com.plantcare.core.domain.PlantEvent;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.PlantEventType;
import com.plantcare.core.service.PlantEventService;
import com.plantcare.core.service.PlantEventService.AddResult;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WebMvcTest для {@link PlantEventController} — issue #220.
 *
 * <p>Покрывает HTTP-контракт GET/POST /api/v1/plants/{id}/events: пагинацию,
 * 201 на создание, 404 для чужого/архивного растения, 409 на дедуп и 422 на
 * неизвестный {@code eventType}.
 */
@WebMvcTest(PlantEventController.class)
@Import(ApiExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class PlantEventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PlantEventService plantEventService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    private static final long USER_ID = 1L;
    private static final long PLANT_ID = 42L;

    @BeforeEach
    void stubCurrentUser() {
        User user = mock(User.class);
        when(user.getId()).thenReturn(USER_ID);
        when(currentUserProvider.currentUser()).thenReturn(user);
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/plants/{id}/events
    // -------------------------------------------------------------------------

    @Test
    void should_return_paged_events_with_defaults_when_no_query_params() throws Exception {
        // arrange
        PlantEvent e1 = event(7L, PlantEventType.TRANSPLANT,
                LocalDateTime.of(2026, 5, 10, 14, 30));
        PlantEvent e2 = event(3L, PlantEventType.PRUNING,
                LocalDateTime.of(2026, 3, 1, 10, 0));
        Page<PlantEvent> page = new PageImpl<>(List.of(e1, e2), PageRequest.of(0, 5), 5);
        when(plantEventService.getEventsPage(any(User.class), eq(PLANT_ID), eq(0), eq(5)))
                .thenReturn(page);

        // act & assert
        mockMvc.perform(get("/api/v1/plants/{id}/events", PLANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.limit").value(5))
                .andExpect(jsonPath("$.offset").value(0))
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items[0].id").value(7))
                .andExpect(jsonPath("$.items[0].eventType").value("TRANSPLANT"))
                .andExpect(jsonPath("$.items[0].eventDate").value("2026-05-10T14:30:00Z"))
                .andExpect(jsonPath("$.items[0].comment").doesNotExist())
                .andExpect(jsonPath("$.items[1].eventType").value("PRUNING"));

        verify(plantEventService).getEventsPage(any(User.class), eq(PLANT_ID), eq(0), eq(5));
    }

    @Test
    void should_pass_explicit_limit_and_offset_to_service() throws Exception {
        // arrange
        Page<PlantEvent> page = new PageImpl<>(List.of(), PageRequest.of(0, 2), 0);
        when(plantEventService.getEventsPage(any(User.class), eq(PLANT_ID), eq(4), eq(2)))
                .thenReturn(page);

        // act & assert
        mockMvc.perform(get("/api/v1/plants/{id}/events", PLANT_ID)
                        .queryParam("limit", "2")
                        .queryParam("offset", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(2))
                .andExpect(jsonPath("$.offset").value(4));

        verify(plantEventService).getEventsPage(any(User.class), eq(PLANT_ID), eq(4), eq(2));
    }

    @Test
    void should_return_400_when_limit_out_of_range() throws Exception {
        // act & assert — @Min/@Max в сгенерированном API отсекают до контроллера
        mockMvc.perform(get("/api/v1/plants/{id}/events", PLANT_ID)
                        .queryParam("limit", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void should_return_404_for_get_when_plant_not_owned() throws Exception {
        // arrange
        when(plantEventService.getEventsPage(any(User.class), eq(999L), eq(0), eq(5)))
                .thenThrow(new EntityNotFoundException("Plant not found"));

        // act & assert
        mockMvc.perform(get("/api/v1/plants/{id}/events", 999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // -------------------------------------------------------------------------
    // POST /api/v1/plants/{id}/events
    // -------------------------------------------------------------------------

    @Test
    void should_create_event_and_return_201() throws Exception {
        // arrange
        PlantEvent created = event(8L, PlantEventType.TRANSPLANT,
                LocalDateTime.of(2026, 6, 3, 12, 0));
        when(plantEventService.addEvent(any(User.class), eq(PLANT_ID), eq(PlantEventType.TRANSPLANT)))
                .thenReturn(AddResult.created(created));

        // act & assert
        mockMvc.perform(post("/api/v1/plants/{id}/events", PLANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"TRANSPLANT\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(8))
                .andExpect(jsonPath("$.eventType").value("TRANSPLANT"))
                .andExpect(jsonPath("$.eventDate").value("2026-06-03T12:00:00Z"))
                .andExpect(jsonPath("$.comment").doesNotExist());

        verify(plantEventService).addEvent(any(User.class), eq(PLANT_ID), eq(PlantEventType.TRANSPLANT));
    }

    @Test
    void should_return_409_when_duplicate_within_dedup_window() throws Exception {
        // arrange
        PlantEvent last = event(8L, PlantEventType.TRANSPLANT,
                LocalDateTime.of(2026, 6, 3, 12, 0));
        when(plantEventService.addEvent(any(User.class), eq(PLANT_ID), eq(PlantEventType.TRANSPLANT)))
                .thenReturn(AddResult.duplicate(last));

        // act & assert
        mockMvc.perform(post("/api/v1/plants/{id}/events", PLANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"TRANSPLANT\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));
    }

    @Test
    void should_return_404_for_post_when_plant_not_owned() throws Exception {
        // arrange
        when(plantEventService.addEvent(any(User.class), eq(999L), eq(PlantEventType.PRUNING)))
                .thenReturn(AddResult.notFound());

        // act & assert
        mockMvc.perform(post("/api/v1/plants/{id}/events", 999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"PRUNING\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void should_return_422_when_unknown_event_type() throws Exception {
        // act & assert — недопустимое значение enum отсекается десериализацией
        mockMvc.perform(post("/api/v1/plants/{id}/events", PLANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"FLYING\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private static PlantEvent event(Long id, PlantEventType type, LocalDateTime date) {
        PlantEvent e = PlantEvent.builder()
                .eventType(type)
                .eventDate(date)
                .build();
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }
}
