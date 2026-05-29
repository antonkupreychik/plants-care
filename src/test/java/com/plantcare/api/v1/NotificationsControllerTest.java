package com.plantcare.api.v1;

import com.plantcare.api.ApiExceptionHandler;
import com.plantcare.api.CurrentUserProvider;
import com.plantcare.core.domain.Notification;
import com.plantcare.core.domain.NotificationType;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.service.NotificationService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} для {@link NotificationsController} (issue #183): JSON-форма ленты
 * (lowercase type, UTC ISO-8601, plantId present/absent), пагинация и валидация
 * limit/offset, 204 на POST .../read и 404 при {@link EntityNotFoundException}.
 *
 * <p>Зеркалит {@link ShoppingControllerTest}: фильтры выключены, {@link ApiExceptionHandler}
 * импортируется, {@link CurrentUserProvider} застаблен на user id = 1, сервис — {@code @MockitoBean}.
 */
@WebMvcTest(NotificationsController.class)
@Import(ApiExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class NotificationsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationService notificationService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @BeforeEach
    void stubCurrentUser() {
        when(currentUserProvider.currentUserId()).thenReturn(1L);
    }

    // ------------------------------------------------------------------ helpers

    private Notification mockNotification(long id, NotificationType type, String title, String body,
                                          LocalDateTime createdAt, LocalDateTime readAt, Plant plant) {
        Notification n = mock(Notification.class);
        when(n.getId()).thenReturn(id);
        when(n.getType()).thenReturn(type);
        when(n.getTitle()).thenReturn(title);
        when(n.getBody()).thenReturn(body);
        when(n.getCreatedAt()).thenReturn(createdAt);
        when(n.getReadAt()).thenReturn(readAt);
        when(n.getPlant()).thenReturn(plant);
        return n;
    }

    // ------------------------------------------------------------------ GET feed

    @Test
    void should_return_feed_with_lowercase_type_utc_dates_and_unread_count() throws Exception {
        // arrange
        Plant plant = mock(Plant.class);
        when(plant.getId()).thenReturn(42L);

        Notification withPlant = mockNotification(
                10L, NotificationType.CARE, "Полить", "Пора полить",
                LocalDateTime.of(2026, 5, 22, 10, 0, 0),
                LocalDateTime.of(2026, 5, 22, 12, 0, 0), plant);

        when(notificationService.list(1L, 0, 20)).thenReturn(List.of(withPlant));
        when(notificationService.unreadCount(1L)).thenReturn(3L);

        // act + assert
        mockMvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(3))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(10))
                // enum CARE → JSON "care" (lowercase)
                .andExpect(jsonPath("$.items[0].type").value("care"))
                .andExpect(jsonPath("$.items[0].title").value("Полить"))
                .andExpect(jsonPath("$.items[0].plantId").value(42))
                // UTC ISO-8601 с нулевым offset
                .andExpect(jsonPath("$.items[0].createdAt").value("2026-05-22T10:00:00Z"))
                .andExpect(jsonPath("$.items[0].readAt").value("2026-05-22T12:00:00Z"));
    }

    @Test
    void should_omit_plant_id_and_read_at_when_not_set() throws Exception {
        // arrange — без растения и непрочитано (readAt = null, plant = null)
        Notification plain = mockNotification(
                11L, NotificationType.SYSTEM, "Системное", "Текст",
                LocalDateTime.of(2026, 5, 22, 9, 0, 0), null, null);

        when(notificationService.list(1L, 0, 20)).thenReturn(List.of(plain));
        when(notificationService.unreadCount(1L)).thenReturn(1L);

        // act + assert
        mockMvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].type").value("system"))
                .andExpect(jsonPath("$.items[0].plantId").doesNotExist())
                .andExpect(jsonPath("$.items[0].readAt").doesNotExist());
    }

    @Test
    void should_return_empty_feed_when_user_has_no_notifications() throws Exception {
        // arrange
        when(notificationService.list(1L, 0, 20)).thenReturn(List.of());
        when(notificationService.unreadCount(1L)).thenReturn(0L);

        // act + assert
        mockMvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.unreadCount").value(0));
    }

    // ------------------------------------------------------------------ pagination params

    @Test
    void should_pass_explicit_in_range_offset_and_limit_to_service() throws Exception {
        // arrange — значения в допустимом диапазоне проходят насквозь без clamp
        when(notificationService.list(eq(1L), anyInt(), anyInt())).thenReturn(List.of());
        when(notificationService.unreadCount(1L)).thenReturn(0L);

        // act
        mockMvc.perform(get("/api/v1/notifications").param("offset", "5").param("limit", "50"))
                .andExpect(status().isOk());

        // assert
        ArgumentCaptor<Integer> offset = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(notificationService).list(eq(1L), offset.capture(), limit.capture());
        assertThat(offset.getValue()).isEqualTo(5);
        assertThat(limit.getValue()).isEqualTo(50);
    }

    @Test
    void should_use_defaults_offset_0_limit_20_when_params_absent() throws Exception {
        // arrange — defaultValue из сгенерированного API: offset=0, limit=20
        when(notificationService.list(1L, 0, 20)).thenReturn(List.of());
        when(notificationService.unreadCount(1L)).thenReturn(0L);

        // act + assert
        mockMvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isOk());

        verify(notificationService).list(1L, 0, 20);
    }

    @Test
    void should_reject_limit_above_100_with_400() throws Exception {
        // Сгенерированный API помечен @Max(100) на limit (@Validated на интерфейсе),
        // поэтому limit=999 отсекается bean-валидацией ДО хендлера → 400 VALIDATION_ERROR.
        // Это и есть реальный контракт; controller-clamp — недостижимая страховка для out-of-range.
        mockMvc.perform(get("/api/v1/notifications").param("limit", "999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        verify(notificationService, never()).list(anyLong(), anyInt(), anyInt());
    }

    @Test
    void should_reject_negative_offset_with_400() throws Exception {
        // @Min(0) на offset → offset=-5 отсекается bean-валидацией → 400.
        mockMvc.perform(get("/api/v1/notifications").param("offset", "-5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void should_pass_limit_100_at_upper_boundary_to_service() throws Exception {
        // limit=100 — верхняя допустимая граница, проходит и доходит до сервиса как 100.
        when(notificationService.list(1L, 0, 100)).thenReturn(List.of());
        when(notificationService.unreadCount(1L)).thenReturn(0L);

        mockMvc.perform(get("/api/v1/notifications").param("limit", "100"))
                .andExpect(status().isOk());

        verify(notificationService).list(1L, 0, 100);
    }

    // ------------------------------------------------------------------ POST mark read

    @Test
    void should_return_204_and_delegate_to_service_when_marking_read() throws Exception {
        // arrange
        doNothing().when(notificationService).markRead(1L, 10L);

        // act + assert
        mockMvc.perform(post("/api/v1/notifications/10/read"))
                .andExpect(status().isNoContent());

        verify(notificationService).markRead(1L, 10L);
    }

    @Test
    void should_return_404_when_marking_unknown_or_foreign_notification() throws Exception {
        // arrange — сервис кидает EntityNotFoundException (несуществующее или чужое уведомление)
        doThrow(new EntityNotFoundException("Notification not found: 999"))
                .when(notificationService).markRead(1L, 999L);

        // act + assert
        mockMvc.perform(post("/api/v1/notifications/999/read"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }
}
