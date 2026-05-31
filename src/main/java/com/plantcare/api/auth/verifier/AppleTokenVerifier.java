package com.plantcare.api.auth.verifier;

/**
 * Верификатор Sign in with Apple identity-token (issue #88). Интерфейс выделен,
 * чтобы в тестах подменять JWKS-проверку моком.
 */
public interface AppleTokenVerifier {

    /**
     * Проверяет подпись по Apple JWKS, {@code iss}/{@code aud}/{@code exp} и
     * возвращает identity. Бросает
     * {@link com.plantcare.api.auth.exception.AuthTokenException} при невалидном токене.
     */
    ExternalIdentity verify(String identityToken);
}
