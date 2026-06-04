package com.plantcare.core.service;

import com.plantcare.core.domain.DevicePlatform;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.UserDevice;
import com.plantcare.core.repository.UserDeviceRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Сервис регистрации и отвязки мобильных устройств (issue #175, ADR-014).
 *
 * <p>Регистрация — idempotent upsert по паре {@code (user_id, push_token)}:
 * если устройство уже зарегистрировано, обновляем {@code last_seen_at};
 * иначе создаём новую запись.
 *
 * <p>Отвязка — hard-delete своей записи. Попытка удалить чужое или
 * несуществующее устройство → {@link EntityNotFoundException} (HTTP 404).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceService {

    private final UserDeviceRepository userDeviceRepository;
    private final Clock clock;

    /**
     * Регистрирует устройство. Если пара {@code (userId, pushToken)} уже существует,
     * только обновляет {@code last_seen_at} (idempotent).
     *
     * @return сохранённая или обновлённая сущность
     */
    @Transactional
    public UserDevice register(User user, DevicePlatform platform, String pushToken) {
        Instant now = Instant.now(clock);

        return userDeviceRepository.findByUserIdAndPushToken(user.getId(), pushToken)
                .map(device -> {
                    device.touch(now);
                    log.info("Updated last_seen_at for device {} user={}", device.getId(), user.getId());
                    return device;
                })
                .orElseGet(() -> {
                    UserDevice device = userDeviceRepository.save(
                            new UserDevice(user, platform, pushToken, now));
                    log.info("Registered device {} platform={} user={}", device.getId(), platform, user.getId());
                    return device;
                });
    }

    /**
     * Удаляет устройство пользователя по id. Чужое или несуществующее — 404.
     */
    @Transactional
    public void unregister(Long userId, Long deviceId) {
        UserDevice device = userDeviceRepository.findByIdAndUserId(deviceId, userId)
                .orElseThrow(() -> new EntityNotFoundException("Device not found: " + deviceId));

        userDeviceRepository.delete(device);
        log.info("Unregistered device {} for user {}", deviceId, userId);
    }
}
