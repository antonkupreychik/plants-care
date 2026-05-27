package com.plantcare.api.v1;

import com.plantcare.api.generated.AuthApi;
import com.plantcare.api.generated.model.AppleAuthRequest;
import com.plantcare.api.generated.model.EmailRequest;
import com.plantcare.api.generated.model.GoogleAuthRequest;
import com.plantcare.api.generated.model.MagicLinkVerifyRequest;
import com.plantcare.api.generated.model.RefreshRequest;
import com.plantcare.api.generated.model.TokenPairResponse;
import com.plantcare.api.auth.exception.RateLimitExceededException;
import com.plantcare.api.auth.ratelimit.MagicLinkRateLimiter;
import com.plantcare.api.auth.service.AuthService;
import com.plantcare.api.auth.service.MagicLinkService;
import com.plantcare.api.auth.service.RefreshService;
import com.plantcare.api.auth.service.TokenPair;
import com.plantcare.api.auth.service.TokenService;
import com.plantcare.api.auth.verifier.AppleTokenVerifier;
import com.plantcare.api.auth.verifier.ExternalIdentity;
import com.plantcare.api.auth.verifier.GoogleTokenVerifier;
import com.plantcare.core.domain.User;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API мобильной аутентификации (issue #88, ADR-011). Тонкий слой:
 * верифицирует вход, дёргает сервисы, маппит результат в {@link TokenPairResponse}.
 *
 * <p>Реализует сгенерированный {@link AuthApi}.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AuthController implements AuthApi {

    private final AppleTokenVerifier appleTokenVerifier;
    private final GoogleTokenVerifier googleTokenVerifier;
    private final AuthService authService;
    private final TokenService tokenService;
    private final RefreshService refreshService;
    private final MagicLinkService magicLinkService;
    private final MagicLinkRateLimiter rateLimiter;
    private final HttpServletRequest httpRequest;

    @Override
    public TokenPairResponse authApple(AppleAuthRequest request) {
        ExternalIdentity identity = appleTokenVerifier.verify(request.getIdentityToken());
        User user = authService.findOrCreateUser(
                identity.email(), identity.emailVerified(),
                AuthService.Provider.APPLE, identity.subject());
        log.info("Apple sign-in: userId={}", user.getId());
        return toResponse(tokenService.issuePair(user));
    }

    @Override
    public TokenPairResponse authGoogle(GoogleAuthRequest request) {
        ExternalIdentity identity = googleTokenVerifier.verify(request.getIdToken());
        // Google-верификатор гарантирует email_verified=true и non-null email.
        User user = authService.findOrCreateUser(
                identity.email(), true,
                AuthService.Provider.GOOGLE, identity.subject());
        log.info("Google sign-in: userId={}", user.getId());
        return toResponse(tokenService.issuePair(user));
    }

    @Override
    public void requestMagicLink(EmailRequest request) {
        String email = request.getEmail().trim().toLowerCase();
        enforceRateLimit("ip:" + httpRequest.getRemoteAddr());
        enforceRateLimit("email:" + email);

        // Всегда 202 (anti-enumeration). Существование адреса не раскрываем.
        magicLinkService.request(email);
    }

    @Override
    public TokenPairResponse verifyMagicLink(MagicLinkVerifyRequest request) {
        return toResponse(magicLinkService.verify(request.getToken()));
    }

    @Override
    public TokenPairResponse refreshTokens(RefreshRequest request) {
        return toResponse(refreshService.rotate(request.getRefreshToken()));
    }

    private void enforceRateLimit(String key) {
        if (rateLimiter.isBlocked(key)) {
            log.warn("Magic link rate-limit triggered");
            throw new RateLimitExceededException(
                    "Too many magic link requests", rateLimiter.getWindowSeconds());
        }
        rateLimiter.record(key);
    }

    private static TokenPairResponse toResponse(TokenPair pair) {
        return new TokenPairResponse(
                pair.accessToken(),
                pair.refreshToken(),
                pair.expiresIn(),
                TokenPair.TOKEN_TYPE);
    }
}
