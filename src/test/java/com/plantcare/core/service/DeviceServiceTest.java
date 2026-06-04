package com.plantcare.core.service;

import com.plantcare.core.domain.DevicePlatform;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.UserDevice;
import com.plantcare.core.repository.UserDeviceRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Юнит-тест {@link DeviceService} (issue #175) — проверяет idempotent-upsert
 * и hard-delete с проверкой владельца.
 */
@ExtendWith(MockitoExtension.class)
class DeviceServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-06-04T08:00:00Z");

    @Mock
    private UserDeviceRepository userDeviceRepository;

    @Mock
    private Clock clock;

    @InjectMocks
    private DeviceService deviceService;

    // ------------------------------------------------------------------ register

    @Test
    void should_create_new_device_when_token_not_registered() {
        // arrange
        User user = mock(User.class);
        when(user.getId()).thenReturn(1L);
        when(clock.instant()).thenReturn(FIXED_NOW);
        when(userDeviceRepository.findByUserIdAndPushToken(1L, "new-token")).thenReturn(Optional.empty());

        UserDevice saved = new UserDevice(user, DevicePlatform.ANDROID, "new-token", FIXED_NOW);
        when(userDeviceRepository.save(any(UserDevice.class))).thenReturn(saved);

        // act
        UserDevice result = deviceService.register(user, DevicePlatform.ANDROID, "new-token");

        // assert
        assertThat(result.getPushToken()).isEqualTo("new-token");
        assertThat(result.getPlatform()).isEqualTo(DevicePlatform.ANDROID);
        verify(userDeviceRepository).save(any(UserDevice.class));
    }

    @Test
    void should_update_last_seen_when_token_already_registered() {
        // arrange — тот же токен уже есть → idempotent upsert обновляет lastSeenAt
        User user = mock(User.class);
        when(user.getId()).thenReturn(1L);
        when(clock.instant()).thenReturn(FIXED_NOW);

        Instant earlier = Instant.parse("2026-06-01T10:00:00Z");
        UserDevice existing = new UserDevice(user, DevicePlatform.IOS, "existing-token", earlier);
        when(userDeviceRepository.findByUserIdAndPushToken(1L, "existing-token"))
                .thenReturn(Optional.of(existing));

        // act
        UserDevice result = deviceService.register(user, DevicePlatform.IOS, "existing-token");

        // assert — lastSeenAt обновлён, save не вызывался (dirty-tracking Hibernate)
        assertThat(result.getLastSeenAt()).isEqualTo(FIXED_NOW);
        assertThat(result.getCreatedAt()).isEqualTo(earlier);
        verify(userDeviceRepository, never()).save(any());
    }

    // ------------------------------------------------------------------ unregister

    @Test
    void should_delete_device_when_owner_requests_unregister() {
        // arrange
        UserDevice device = mock(UserDevice.class);
        when(userDeviceRepository.findByIdAndUserId(7L, 1L)).thenReturn(Optional.of(device));

        // act
        deviceService.unregister(1L, 7L);

        // assert
        verify(userDeviceRepository).delete(device);
    }

    @Test
    void should_throw_entity_not_found_when_device_not_owned() {
        // arrange — чужое или несуществующее устройство
        when(userDeviceRepository.findByIdAndUserId(999L, 1L)).thenReturn(Optional.empty());

        // act + assert
        assertThatThrownBy(() -> deviceService.unregister(1L, 999L))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("999");
    }
}
