package com.plantcare.api.v1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plantcare.api.ApiExceptionHandler;
import com.plantcare.api.CurrentUserProvider;
import com.plantcare.core.domain.DevicePlatform;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.UserDevice;
import com.plantcare.core.service.DeviceService;
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

import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} для {@link DevicesController} (issue #175).
 *
 * <p>POST /api/v1/devices: 201 + DeviceDto в теле; идемпотентность; валидация входа.
 * DELETE /api/v1/devices/{id}: 204 при успехе; 404 при чужом/несуществующем устройстве.
 */
@WebMvcTest(DevicesController.class)
@Import(ApiExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class DevicesControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private DeviceService deviceService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    private User mockUser;

    @BeforeEach
    void stubCurrentUser() {
        mockUser = mock(User.class);
        when(mockUser.getId()).thenReturn(1L);
        when(currentUserProvider.currentUserId()).thenReturn(1L);
        when(currentUserProvider.currentUser()).thenReturn(mockUser);
    }

    // ------------------------------------------------------------------ helpers

    private UserDevice mockDevice(Long id, DevicePlatform platform, String token) {
        UserDevice device = mock(UserDevice.class);
        when(device.getId()).thenReturn(id);
        when(device.getPlatform()).thenReturn(platform);
        when(device.getPushToken()).thenReturn(token);
        when(device.getCreatedAt()).thenReturn(Instant.parse("2026-06-01T10:00:00Z"));
        when(device.getLastSeenAt()).thenReturn(Instant.parse("2026-06-04T08:00:00Z"));
        return device;
    }

    // ------------------------------------------------------------------ POST /api/v1/devices

    @Test
    void should_return_201_with_device_dto_when_registering_new_device() throws Exception {
        // arrange
        UserDevice device = mockDevice(7L, DevicePlatform.ANDROID, "fZd9kQ...");
        when(deviceService.register(eq(mockUser), eq(DevicePlatform.ANDROID), eq("fZd9kQ...")))
                .thenReturn(device);

        String body = objectMapper.writeValueAsString(Map.of("platform", "ANDROID", "pushToken", "fZd9kQ..."));

        // act + assert
        mockMvc.perform(post("/api/v1/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.platform").value("ANDROID"))
                .andExpect(jsonPath("$.pushToken").value("fZd9kQ..."))
                .andExpect(jsonPath("$.createdAt").value("2026-06-01T10:00:00Z"))
                .andExpect(jsonPath("$.lastSeenAt").value("2026-06-04T08:00:00Z"));
    }

    @Test
    void should_return_201_on_idempotent_re_registration() throws Exception {
        // arrange — сервис возвращает существующий device (upsert обновил lastSeenAt)
        UserDevice existing = mockDevice(7L, DevicePlatform.IOS, "ios-token-xyz");
        when(deviceService.register(eq(mockUser), eq(DevicePlatform.IOS), eq("ios-token-xyz")))
                .thenReturn(existing);

        String body = objectMapper.writeValueAsString(Map.of("platform", "IOS", "pushToken", "ios-token-xyz"));

        // act + assert — повторный вызов → всё равно 201
        mockMvc.perform(post("/api/v1/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(7));

        verify(deviceService).register(mockUser, DevicePlatform.IOS, "ios-token-xyz");
    }

    @Test
    void should_reject_missing_platform_with_400() throws Exception {
        // arrange — поле platform обязательно
        String body = objectMapper.writeValueAsString(Map.of("pushToken", "some-token"));

        // act + assert
        mockMvc.perform(post("/api/v1/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void should_reject_missing_push_token_with_400() throws Exception {
        // arrange — поле pushToken обязательно
        String body = objectMapper.writeValueAsString(Map.of("platform", "ANDROID"));

        // act + assert
        mockMvc.perform(post("/api/v1/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void should_reject_unknown_platform_with_422() throws Exception {
        // arrange — невалидное значение enum → Jackson бросает IAE → 422
        String body = objectMapper.writeValueAsString(Map.of("platform", "WINDOWS", "pushToken", "tok"));

        // act + assert
        mockMvc.perform(post("/api/v1/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity());
    }

    // ------------------------------------------------------------------ DELETE /api/v1/devices/{id}

    @Test
    void should_return_204_when_unregistering_own_device() throws Exception {
        // arrange
        doNothing().when(deviceService).unregister(1L, 7L);

        // act + assert
        mockMvc.perform(delete("/api/v1/devices/7"))
                .andExpect(status().isNoContent());

        verify(deviceService).unregister(1L, 7L);
    }

    @Test
    void should_return_404_when_unregistering_unknown_or_foreign_device() throws Exception {
        // arrange — сервис кидает EntityNotFoundException (чужое или несуществующее)
        doThrow(new EntityNotFoundException("Device not found: 999"))
                .when(deviceService).unregister(1L, 999L);

        // act + assert
        mockMvc.perform(delete("/api/v1/devices/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }
}
