package com.plantcare.core.domain;

import com.plantcare.core.domain.enums.DeliveryChannel;
import com.plantcare.core.domain.enums.DeliveryStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Одна попытка доставки уведомления в один канал (issue #95).
 *
 * <p>Пишется из точек резолва отправки: {@code RateLimitedTelegramSender} для
 * Telegram и {@code PushFanOutService} для push. Дашборд
 * {@code /admin/notifications/health} читает эту таблицу агрегатами через
 * JdbcTemplate — сущность нужна только для записи.
 *
 * <p>Как и {@code UserDevice}, не наследует {@code BaseEntity}: там
 * {@code created_at} мапится в {@code LocalDateTime}, а нам нужен {@code Instant}
 * (UTC) — время берём из инжектируемого {@code Clock}, чтобы тесты могли его
 * подменить.
 *
 * <p>{@code userId} хранится примитивным идентификатором, а не {@code @ManyToOne}:
 * запись происходит на воркер-потоках очередей доставки, где тащить JPA-связь
 * (и ловить ленивую инициализацию) незачем — читаем мы эту таблицу только SQL'ем.
 */
@Entity
@Table(name = "notification_delivery_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationDeliveryEvent {

    /** Максимальная длина {@code error_code} в схеме (V55). Длиннее — обрезаем. */
    public static final int ERROR_CODE_MAX_LENGTH = 64;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 16)
    private DeliveryChannel channel;

    /** Получатель; {@code null}, если чат не удалось сопоставить с пользователем. */
    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private DeliveryStatus status;

    /** Код ошибки вида {@code telegram:403} / {@code fcm:UnregisteredDevice}; {@code null} для SENT. */
    @Column(name = "error_code", length = ERROR_CODE_MAX_LENGTH)
    private String errorCode;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    public NotificationDeliveryEvent(
            Instant createdAt,
            DeliveryChannel channel,
            Long userId,
            DeliveryStatus status,
            String errorCode,
            Integer latencyMs
    ) {
        this.createdAt = createdAt;
        this.channel = channel;
        this.userId = userId;
        this.status = status;
        this.errorCode = truncate(errorCode);
        this.latencyMs = latencyMs;
    }

    /**
     * Код ошибки приходит из текста исключения внешнего API — гарантий по длине
     * нет, а колонка ограничена 64 символами. Обрезаем, чтобы падение вставки не
     * ломало доставку.
     */
    private static String truncate(String errorCode) {
        if (errorCode == null || errorCode.length() <= ERROR_CODE_MAX_LENGTH) {
            return errorCode;
        }
        return errorCode.substring(0, ERROR_CODE_MAX_LENGTH);
    }
}
