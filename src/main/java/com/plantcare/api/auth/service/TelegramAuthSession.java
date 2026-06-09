package com.plantcare.api.auth.service;

import java.time.Instant;

/**
 * Issue #318: анонимная короткоживущая сессия входа существующего Telegram-юзера.
 *
 * <p>Создаётся на {@code POST /auth/telegram/start} (ещё без chat_id и кода),
 * дополняется ботом при {@code /start auth_<sessionId>} (chat_id + код) и
 * потребляется на {@code POST /auth/telegram/verify}.
 *
 * <p>Хранится в Redis с TTL = время жизни сессии (родная истечение). Поля
 * {@code telegramChatId}/{@code code} остаются {@code null} до того, как бот
 * привяжет код — verify до привязки трактуется как неверный код.
 *
 * @param sessionId      непредсказуемый идентификатор сессии (часть deep link)
 * @param telegramChatId chat_id, привязанный ботом ИЗ Telegram-update; {@code null} до привязки
 * @param code           6-значный код, сгенерированный ботом; {@code null} до привязки
 * @param attempts       число неуспешных попыток ввода кода
 * @param expiresAt      момент истечения сессии (для информативности; TTL держит Redis)
 */
public record TelegramAuthSession(
        String sessionId,
        Long telegramChatId,
        String code,
        int attempts,
        Instant expiresAt
) {

    /** Сессия с привязанным ботом chat_id и кодом (attempts сбрасывается в 0 на новом коде). */
    public TelegramAuthSession withBoundCode(Long telegramChatId, String code) {
        return new TelegramAuthSession(sessionId, telegramChatId, code, 0, expiresAt);
    }

    /** Сессия с увеличенным счётчиком неуспешных попыток. */
    public TelegramAuthSession withIncrementedAttempts() {
        return new TelegramAuthSession(sessionId, telegramChatId, code, attempts + 1, expiresAt);
    }

    /** {@code true}, если бот ещё не привязал код к сессии. */
    public boolean isUnbound() {
        return code == null || telegramChatId == null;
    }
}
