package com.plantcare.core.repository;

import com.plantcare.core.domain.Notification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /**
     * Лента уведомлений пользователя, новые сверху, с offset/limit пагинацией.
     *
     * <p>{@code plant} подтягивается через {@link EntityGraph}, потому что
     * {@code NotificationsController.toDto} читает {@code plant.id} вне транзакции
     * (OSIV=false) — иначе {@code LazyInitializationException}. Сортировка и
     * сдвиг приходят через {@link Pageable} (см. сервис).
     */
    @EntityGraph(attributePaths = "plant")
    List<Notification> findAllByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    long countByUserIdAndReadAtIsNull(Long userId);

    Optional<Notification> findByUserIdAndId(Long userId, Long id);
}
