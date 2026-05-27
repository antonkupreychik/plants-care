package com.plantcare.api.v1;

import com.plantcare.api.auth.exception.AuthTokenException;
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
import com.plantcare.api.ApiExceptionHandler;
import com.plantcare.core.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Слайс-тест {@link AuthController} (issue #88, ADR-011).
 *
 * <p>Внешние верификаторы Apple/Google замоканы (AC: «тесты с моками верификации
 * Apple/Google JWKS») — в сеть не ходим. Проверяем HTTP-контракт: 200 + tokenPair,
 * 401 с машиночитаемым кодом, 202 anti-enumeration, 429 rate-limit.
 */
@WebMvcTest(AuthController.class)
@Import(ApiExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AppleTokenVerifier appleTokenVerifier;

    @MockitoBean
    private GoogleTokenVerifier googleTokenVerifier;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private RefreshService refreshService;

    @MockitoBean
    private MagicLinkService magicLinkService;

    @MockitoBean
    private MagicLinkRateLimiter rateLimiter;

    private static User userMock(long id) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(id);
        return user;
    }

    private static TokenPair pair() {
        return new TokenPair("access-jwt", "refresh-jwt", 3600);
    }

    // ===================== POST /auth/apple =====================

    @Test
    void should_return_200_with_token_pair_when_apple_token_valid() throws Exception {
        // arrange
        when(appleTokenVerifier.verify("apple-id-token"))
                .thenReturn(new ExternalIdentity("apple-sub", "a@example.com", true));
        User user = userMock(1L);
        when(authService.findOrCreateUser(eq("a@example.com"), eq(true), eq(AuthService.Provider.APPLE), eq("apple-sub")))
                .thenReturn(user);
        when(tokenService.issuePair(user)).thenReturn(pair());

        // act + assert
        mockMvc.perform(post("/api/v1/auth/apple")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identityToken\": \"apple-id-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-jwt"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-jwt"))
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andExpect(jsonPath("$.tokenType").value("Bearer"));
    }

    @Test
    void should_return_401_with_token_invalid_when_apple_token_rejected() throws Exception {
        // arrange — верификатор отвергает токен
        when(appleTokenVerifier.verify(anyString()))
                .thenThrow(AuthTokenException.invalid("Apple token signature invalid"));

        // act + assert
        mockMvc.perform(post("/api/v1/auth/apple")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identityToken\": \"bad-token\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_INVALID"));
    }

    @Test
    void should_return_401_with_token_expired_when_apple_token_expired() throws Exception {
        // arrange
        when(appleTokenVerifier.verify(anyString()))
                .thenThrow(AuthTokenException.expired("Apple token expired"));

        // act + assert — понятный код TOKEN_EXPIRED в формате ApiErrorResponse (AC)
        mockMvc.perform(post("/api/v1/auth/apple")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identityToken\": \"expired-token\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_EXPIRED"));
    }

    @Test
    void should_return_401_with_email_required_when_apple_identity_unverified_and_subject_unknown() throws Exception {
        // arrange — Apple-верификатор отдал identity с unverified email и неизвестным subject;
        // AuthService отказывает с EMAIL_REQUIRED (issue #88, BLOCKING-#2). В сеть не ходим.
        when(appleTokenVerifier.verify(anyString()))
                .thenReturn(new ExternalIdentity("unknown-apple-sub", "a@example.com", false));
        when(authService.findOrCreateUser(eq("a@example.com"), eq(false), eq(AuthService.Provider.APPLE), eq("unknown-apple-sub")))
                .thenThrow(AuthTokenException.emailRequired(
                        "Provider did not return a verified email and subject is unknown"));

        // act + assert — 401 с машиночитаемым кодом EMAIL_REQUIRED в формате ApiErrorResponse
        mockMvc.perform(post("/api/v1/auth/apple")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identityToken\": \"unverified-token\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("EMAIL_REQUIRED"));

        // не выпускаем токены, если линковка невозможна
        verify(tokenService, never()).issuePair(any());
    }

    @Test
    void should_return_401_with_email_required_when_apple_identity_has_null_email_and_subject_unknown() throws Exception {
        // arrange — Apple private-relay: email = null, subject ранее не привязан
        when(appleTokenVerifier.verify(anyString()))
                .thenReturn(new ExternalIdentity("brand-new-sub", null, false));
        when(authService.findOrCreateUser(eq(null), eq(false), eq(AuthService.Provider.APPLE), eq("brand-new-sub")))
                .thenThrow(AuthTokenException.emailRequired(
                        "Provider did not return a verified email and subject is unknown"));

        // act + assert
        mockMvc.perform(post("/api/v1/auth/apple")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identityToken\": \"no-email-token\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("EMAIL_REQUIRED"));

        verify(tokenService, never()).issuePair(any());
    }

    // ===================== POST /auth/google =====================

    @Test
    void should_return_200_with_token_pair_when_google_token_valid() throws Exception {
        // arrange
        when(googleTokenVerifier.verify("google-id-token"))
                .thenReturn(new ExternalIdentity("google-sub", "g@example.com", true));
        User user = userMock(2L);
        when(authService.findOrCreateUser(eq("g@example.com"), eq(true), eq(AuthService.Provider.GOOGLE), eq("google-sub")))
                .thenReturn(user);
        when(tokenService.issuePair(user)).thenReturn(pair());

        // act + assert
        mockMvc.perform(post("/api/v1/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\": \"google-id-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-jwt"));
    }

    @Test
    void should_return_401_when_google_email_not_verified() throws Exception {
        // arrange — верификатор Google отказывает при email_verified=false
        when(googleTokenVerifier.verify(anyString()))
                .thenThrow(AuthTokenException.invalid("Google email is not verified"));

        // act + assert
        mockMvc.perform(post("/api/v1/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\": \"unverified-email-token\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_INVALID"));
    }

    // ===================== POST /auth/email/request (anti-enumeration + rate limit) =====================

    @Test
    void should_return_202_even_for_unknown_email_when_requesting_magic_link() throws Exception {
        // arrange — не блокируем, не раскрываем существование адреса
        when(rateLimiter.isBlocked(anyString())).thenReturn(false);

        // act + assert
        mockMvc.perform(post("/api/v1/auth/email/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"who-knows@example.com\"}"))
                .andExpect(status().isAccepted());

        verify(magicLinkService).request("who-knows@example.com");
    }

    @Test
    void should_return_400_when_email_is_malformed() throws Exception {
        // act + assert — Bean Validation (@Email) → 400
        mockMvc.perform(post("/api/v1/auth/email/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void should_return_429_with_retry_after_when_rate_limit_exceeded() throws Exception {
        // arrange — лимитер сигналит «заблокировано», контроллер бросает 429
        when(rateLimiter.isBlocked(anyString())).thenReturn(true);
        when(rateLimiter.getWindowSeconds()).thenReturn(300L);

        // act + assert
        mockMvc.perform(post("/api/v1/auth/email/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"spammer@example.com\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "300"))
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));

        // не должны звать сервис, если лимит превышен
        verify(magicLinkService, never()).request(anyString());
    }

    // ===================== POST /auth/email/verify =====================

    @Test
    void should_return_200_when_magic_link_token_valid() throws Exception {
        // arrange
        when(magicLinkService.verify("magic-token")).thenReturn(pair());

        // act + assert
        mockMvc.perform(post("/api/v1/auth/email/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\": \"magic-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").value("refresh-jwt"));
    }

    @Test
    void should_return_401_when_magic_link_token_consumed_or_expired() throws Exception {
        // arrange
        when(magicLinkService.verify(anyString()))
                .thenThrow(AuthTokenException.invalid("Magic link is invalid, expired or already used"));

        // act + assert
        mockMvc.perform(post("/api/v1/auth/email/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\": \"used-token\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_INVALID"));
    }

    // ===================== POST /auth/refresh =====================

    @Test
    void should_return_200_with_new_pair_when_refresh_valid() throws Exception {
        // arrange
        when(refreshService.rotate("refresh-jwt")).thenReturn(new TokenPair("a2", "r2", 3600));

        // act + assert
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\": \"refresh-jwt\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("a2"))
                .andExpect(jsonPath("$.refreshToken").value("r2"));
    }

    @Test
    void should_return_401_with_token_revoked_when_refresh_reused() throws Exception {
        // arrange — повторный refresh → TOKEN_REVOKED (AC: rotation-инвалидация)
        when(refreshService.rotate(anyString()))
                .thenThrow(AuthTokenException.revoked("Refresh token has already been used"));

        // act + assert
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\": \"reused-refresh\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_REVOKED"));
    }
}
