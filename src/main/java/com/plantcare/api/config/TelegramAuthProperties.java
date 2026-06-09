package com.plantcare.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Issue #318: настройки входа существующих Telegram-юзеров через бот-код.
 *
 * <p>Анонимная короткоживущая сессия входа: клиент стартует её через
 * {@code POST /api/v1/auth/telegram/start}, бот по deep link присылает 6-значный
 * код, клиент подтверждает его через {@code POST /api/v1/auth/telegram/verify}.
 *
 * <p>Имя бота ({@code botUsername}) берётся из env ({@code TELEGRAM_BOT_USERNAME})
 * и используется только для построения {@code deepLink} — на безопасность не влияет
 * (chat_id привязывается ботом из самого Telegram-update, не из этого имени).
 */
@ConfigurationProperties(prefix = "plantcare.auth.telegram")
public record TelegramAuthProperties(

        /** Username бота без {@code @} для построения {@code t.me/<bot>?start=...}. */
        String botUsername,

        /** Длина одноразового кода. Контракт фиксирует 6. */
        int codeLength,

        /** TTL сессии входа (и кода). ~5 минут. */
        Duration sessionTtl,

        /**
         * Сколько секунд клиент должен подождать перед повторным {@code /start}
         * (resend). Гейтит только клиент — сервер новый {@code /start} не блокирует
         * по этому значению (за абьюз отвечает rate-limit).
         */
        long resendAfterSec,

        /** Максимум попыток ввода кода в рамках одной сессии до её гашения. */
        int maxAttempts

) {

    public TelegramAuthProperties {
        if (codeLength <= 0) codeLength = 6;
        if (sessionTtl == null || sessionTtl.isZero() || sessionTtl.isNegative()) {
            sessionTtl = Duration.ofMinutes(5);
        }
        if (resendAfterSec <= 0) resendAfterSec = 60;
        if (maxAttempts <= 0) maxAttempts = 5;
    }
}
