package com.plantcare.api.v1;

import com.plantcare.api.CurrentUserProvider;
import com.plantcare.api.generated.NotificationsApi;
import com.plantcare.api.generated.model.NotificationDto;
import com.plantcare.api.generated.model.NotificationsResponse;
import com.plantcare.core.domain.Notification;
import com.plantcare.core.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneOffset;
import java.util.List;

/**
 * REST API для персистентного inbox уведомлений (issue #183, gap G17).
 *
 * <p>Документация и mapping живут в сгенерированном {@link NotificationsApi}
 * (см. openapi.yaml). Хендлер тонкий: делегирование сервису и конвертация
 * entity → DTO. Диапазоны {@code offset}/{@code limit} валидирует bean-validation
 * на сгенерированном интерфейсе ({@code @Min}/{@code @Max}) — выход за границы → 400.
 *
 * <p>Тип уведомления: в БД хранится имя константы в верхнем регистре
 * ({@code CARE}…), в JSON отдаётся нижний ({@code care}…). Имена констант
 * {@code NotificationType} и сгенерированного {@code TypeEnum} совпадают, поэтому
 * маппинг — {@code TypeEnum.valueOf(type.name())}.
 */
@RestController
@RequiredArgsConstructor
public class NotificationsController implements NotificationsApi {

    private final NotificationService notificationService;
    private final CurrentUserProvider currentUserProvider;

    @Override
    public NotificationsResponse listNotifications(Integer offset, Integer limit) {
        Long userId = currentUserProvider.currentUserId();

        List<NotificationDto> items = notificationService.list(userId, offset, limit).stream()
                .map(NotificationsController::toDto)
                .toList();
        long unreadCount = notificationService.unreadCount(userId);

        return new NotificationsResponse(items, (int) unreadCount);
    }

    @Override
    public void markNotificationRead(Long id) {
        notificationService.markRead(currentUserProvider.currentUserId(), id);
    }

    private static NotificationDto toDto(Notification notification) {
        NotificationDto dto = new NotificationDto()
                .id(notification.getId())
                .type(NotificationDto.TypeEnum.valueOf(notification.getType().name()))
                .title(notification.getTitle())
                .body(notification.getBody())
                .createdAt(notification.getCreatedAt().atOffset(ZoneOffset.UTC));

        if (notification.getPlant() != null) {
            dto.plantId(notification.getPlant().getId());
        }
        if (notification.getReadAt() != null) {
            dto.readAt(notification.getReadAt().atOffset(ZoneOffset.UTC));
        }
        return dto;
    }
}
