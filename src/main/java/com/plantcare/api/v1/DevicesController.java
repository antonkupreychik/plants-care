package com.plantcare.api.v1;

import com.plantcare.api.CurrentUserProvider;
import com.plantcare.api.generated.DevicesApi;
import com.plantcare.api.generated.model.DeviceDto;
import com.plantcare.api.generated.model.DeviceRegisterRequest;
import com.plantcare.core.domain.DevicePlatform;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.UserDevice;
import com.plantcare.core.service.DeviceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneOffset;

/**
 * REST API для регистрации и отвязки устройств пользователя (issue #175, ADR-014).
 *
 * <p>Документация и маппинг живут в сгенерированном {@link DevicesApi}
 * (см. openapi.yaml → resources/devices.yaml). Хендлер тонкий: делегирует
 * {@link DeviceService} и конвертирует entity → DTO.
 *
 * <p>POST идемпотентен: повторный вызов с тем же {@code pushToken} обновляет
 * {@code lastSeenAt} и снова возвращает 201.
 */
@RestController
@RequiredArgsConstructor
public class DevicesController implements DevicesApi {

    private final DeviceService deviceService;
    private final CurrentUserProvider currentUserProvider;

    @Override
    public DeviceDto registerDevice(DeviceRegisterRequest request) {
        User user = currentUserProvider.currentUser();
        DevicePlatform platform = DevicePlatform.valueOf(request.getPlatform().name());

        UserDevice device = deviceService.register(user, platform, request.getPushToken());
        return toDto(device);
    }

    @Override
    public void unregisterDevice(Long id) {
        deviceService.unregister(currentUserProvider.currentUserId(), id);
    }

    private static DeviceDto toDto(UserDevice device) {
        return new DeviceDto()
                .id(device.getId())
                .platform(DeviceDto.PlatformEnum.valueOf(device.getPlatform().name()))
                .pushToken(device.getPushToken())
                .createdAt(device.getCreatedAt().atOffset(ZoneOffset.UTC))
                .lastSeenAt(device.getLastSeenAt().atOffset(ZoneOffset.UTC));
    }
}
