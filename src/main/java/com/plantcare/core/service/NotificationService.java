package com.plantcare.core.service;

import com.plantcare.core.domain.Notification;
import com.plantcare.core.domain.NotificationType;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.repository.NotificationRepository;
import com.plantcare.core.repository.OffsetLimitPageable;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Персистентный inbox уведомлений пользователя (issue #183).
 *
 * <p>Инфраструктура ленты: запись ({@link #create}), чтение страницы
 * ({@link #list}), счётчик непрочитанных ({@link #unreadCount}) и идемпотентная
 * отметка прочтения ({@link #markRead}). {@code create} пока не вызывается из
 * другого кода — наполнение ленты добавят отдельные issue; write-путь существует
 * заранее намеренно.
 *
 * <p>Время прочтения берётся из инжектируемого {@link Clock} в UTC, не из
 * {@code LocalDateTime.now()} — это критично для тестируемости шедулера/таймзон.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt");

    private final NotificationRepository notificationRepository;
    private final Clock clock;

    /**
     * Создаёт уведомление в ленте пользователя. {@code plant} опционален (deep-link).
     * Текст {@code title}/{@code body} генерируется вызывающим кодом на момент создания
     * и хранится как есть.
     */
    @Transactional
    public Notification create(User user, NotificationType type, String title, String body, Plant plant) {
        Notification saved = notificationRepository.save(
                new Notification(user, type, title, body, plant));

        log.info("Created notification {} type={} for user {}", saved.getId(), type, user.getId());

        return saved;
    }

    /**
     * Страница уведомлений пользователя, новые сверху, с произвольным offset/limit.
     * Границы (clamp limit/offset) валидируются на REST-слое.
     */
    @Transactional(readOnly = true)
    public List<Notification> list(Long userId, int offset, int limit) {
        return notificationRepository.findAllByUserIdOrderByCreatedAtDesc(
                userId, OffsetLimitPageable.of(offset, limit, NEWEST_FIRST));
    }

    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        return notificationRepository.countByUserIdAndReadAtIsNull(userId);
    }

    /**
     * Идемпотентно помечает уведомление прочитанным. Повторный вызов — no-op
     * (момент первого прочтения не перезаписывается). Чужое или несуществующее
     * уведомление — {@link EntityNotFoundException} (маппится в 404 на REST-слое):
     * скоуп по {@code userId} не даёт пометить чужую запись.
     */
    @Transactional
    public void markRead(Long userId, Long notificationId) {
        Notification notification = notificationRepository.findByUserIdAndId(userId, notificationId)
                .orElseThrow(() -> new EntityNotFoundException("Notification not found: " + notificationId));

        notification.markRead(LocalDateTime.now(clock));

        log.info("Marked notification {} read for user {}", notificationId, userId);
    }
}
