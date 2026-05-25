package com.plantcare.bot.config;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Конфигурация очереди исходящих сообщений Telegram (issue #29).
 *
 * <p>Telegram Bot API ограничивает массовую отправку до ~30 сообщений в секунду.
 * {@code permitsPerSecond} держим ниже лимита (запас), чтобы случайные всплески
 * не привели к 429. Очередь ограничена {@code queueCapacity}: при переполнении
 * сообщение дропается с warn-логом, чтобы не блокировать поток шедулера.
 */
@Getter
@RequiredArgsConstructor
@ConfigurationProperties(prefix = "telegram.rate-limit")
public class TelegramRateLimitProperties {

    /** Сколько отправок в секунду разрешает token bucket. Запас под лимит Telegram (30/sec). */
    private final int permitsPerSecond;

    /** Максимальная глубина очереди. При переполнении сообщение дропается. */
    private final int queueCapacity;

    /** Сколько раз повторять отправку при 429 перед тем, как признать неудачу. */
    private final int maxRetries;

    /** Fallback retry-after (секунды), если 429 не содержит распарсиваемого значения. */
    private final long defaultRetryAfterSeconds;

    /**
     * Верхняя граница (секунды) на распарсенный {@code retry-after} из 429.
     * Один ответ с огромным значением (например, 99999) усыпил бы единственный
     * воркер-поток на часы и застопорил всю остальную очередь — поэтому капаем.
     */
    private final long maxRetryAfterSeconds;
}
