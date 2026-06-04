package com.plantcare.core.repository;

import com.plantcare.core.domain.UserDevice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserDeviceRepository extends JpaRepository<UserDevice, Long> {

    /** Ищет устройство по паре (user_id, push_token) для idempotent-upsert. */
    Optional<UserDevice> findByUserIdAndPushToken(Long userId, String pushToken);

    /** Находит устройство пользователя по его id для авторизованного удаления. */
    Optional<UserDevice> findByIdAndUserId(Long id, Long userId);
}
