package com.plantcare.api.auth.service;

/**
 * Пара токенов, выдаваемая при входе/ротации (issue #88). Внутренний носитель,
 * контроллер маппит его в сгенерированный {@code TokenPairResponse}.
 *
 * @param accessToken  короткоживущий access-JWT
 * @param refreshToken долгоживущий refresh-JWT
 * @param expiresIn    TTL access-токена в секундах
 */
public record TokenPair(String accessToken, String refreshToken, long expiresIn) {

    public static final String TOKEN_TYPE = "Bearer";
}
