package com.plantcare.api.auth.verifier;

/**
 * Результат верификации внешнего id-token провайдера (Apple/Google), issue #88.
 *
 * @param subject       стабильный {@code sub} провайдера
 * @param email         email из токена (может быть {@code null}, если провайдер не отдал)
 * @param emailVerified подтверждён ли email провайдером
 */
public record ExternalIdentity(String subject, String email, boolean emailVerified) {
}
