package com.plantcare.api.auth.verifier;

/**
 * Верификатор Google id-token (issue #88). Интерфейс выделен, чтобы в тестах
 * подменять JWKS-проверку моком.
 */
public interface GoogleTokenVerifier {

    /**
     * Проверяет подпись по Google JWKS, {@code iss}/{@code aud}/{@code exp},
     * требует {@code email_verified = true} и возвращает identity. Бросает
     * {@link com.plantcare.api.auth.exception.AuthTokenException} при невалидном токене.
     */
    ExternalIdentity verify(String idToken);
}
