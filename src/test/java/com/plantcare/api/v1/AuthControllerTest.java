package com.plantcare.api.v1;

import com.plantcare.api.auth.exception.AuthTokenException;
import com.plantcare.api.auth.exception.GuestConvertException;
import com.plantcare.api.auth.exception.RateLimitExceededException;
import com.plantcare.api.auth.ratelimit.GuestRateLimiter;
import com.plantcare.api.auth.ratelimit.MagicLinkRateLimiter;
import com.plantcare.api.auth.service.AuthService;
import com.plantcare.api.auth.service.GuestAuthService;
import com.plantcare.api.auth.service.LogoutService;
import com.plantcare.api.auth.service.MagicLinkService;
import com.plantcare.api.auth.service.RefreshService;
import com.plantcare.api.auth.service.TokenPair;
import com.plantcare.api.auth.service.TokenService;
import com.plantcare.api.auth.verifier.AppleTokenVerifier;
import com.plantcare.api.auth.verifier.ExternalIdentity;
import com.plantcare.api.auth.verifier.GoogleTokenVerifier;
import com.plantcare.api.ApiExceptionHandler;
import com.plantcare.api.CurrentUserProvider;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    private LogoutService logoutService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @MockitoBean
    private MagicLinkRateLimiter rateLimiter;

    @MockitoBean
    private GuestAuthService guestAuthService;

    @MockitoBean
    private GuestRateLimiter guestRateLimiter;

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

    // ===================== POST /auth/logout (issue #178) =====================

    @Test
    void should_return_204_and_delegate_to_service_when_logout_called() throws Exception {
        // arrange — текущий юзер резолвится из контекста, сервис отзывает токен
        when(currentUserProvider.currentUserId()).thenReturn(42L);

        // act + assert — 204 No Content, тело пустое
        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\": \"some-refresh\"}"))
                .andExpect(status().isNoContent());

        // отзыв делегирован сервису с парой (currentUserId, предъявленный токен)
        verify(logoutService).logout(42L, "some-refresh");
    }

    @Test
    void should_return_204_when_logout_called_twice_with_same_token() throws Exception {
        // arrange — идемпотентность: сервис не бросает на повторный logout
        when(currentUserProvider.currentUserId()).thenReturn(7L);

        // act + assert — оба вызова 204
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/v1/auth/logout")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"refreshToken\": \"repeat-refresh\"}"))
                    .andExpect(status().isNoContent());
        }

        verify(logoutService, times(2)).logout(7L, "repeat-refresh");
    }

    @Test
    void should_return_400_when_logout_body_missing_refresh_token() throws Exception {
        // act + assert — refreshToken обязателен (@NotBlank в сгенерированной модели) → 400
        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        verify(logoutService, never()).logout(any(), anyString());
    }

    // ===================== POST /auth/logout-all (issue #178) =====================

    @Test
    void should_return_204_and_bump_epoch_when_logout_all_called() throws Exception {
        // arrange
        when(currentUserProvider.currentUserId()).thenReturn(99L);

        // act + assert — 204, делегирование с currentUserId
        mockMvc.perform(post("/api/v1/auth/logout-all"))
                .andExpect(status().isNoContent());

        verify(logoutService).logoutAll(99L);
    }

    // ===================== POST /auth/guest (issue #227) =====================

    @Test
    void should_return_200_with_token_pair_and_is_new_user_true_when_new_guest_created() throws Exception {
        // arrange
        when(guestRateLimiter.isBlocked(anyString())).thenReturn(false);
        var result = new GuestAuthService.GuestLoginResult(pair(), true);
        when(guestAuthService.loginOrRestore("550e8400-e29b-41d4-a716-446655440000")).thenReturn(result);

        // act + assert
        mockMvc.perform(post("/api/v1/auth/guest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\": \"550e8400-e29b-41d4-a716-446655440000\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-jwt"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-jwt"))
                .andExpect(jsonPath("$.isNewUser").value(true));

        verify(guestRateLimiter).recordNew(anyString());
    }

    @Test
    void should_return_200_with_is_new_user_false_when_existing_device_id() throws Exception {
        // arrange
        when(guestRateLimiter.isBlocked(anyString())).thenReturn(false);
        var result = new GuestAuthService.GuestLoginResult(pair(), false);
        when(guestAuthService.loginOrRestore("550e8400-e29b-41d4-a716-446655440000")).thenReturn(result);

        // act + assert
        mockMvc.perform(post("/api/v1/auth/guest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\": \"550e8400-e29b-41d4-a716-446655440000\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isNewUser").value(false));

        // restore не фиксируется в rate-limiter
        verify(guestRateLimiter, never()).recordNew(anyString());
    }

    @Test
    void should_return_429_when_guest_rate_limit_exceeded() throws Exception {
        // arrange — IP заблокирован
        when(guestRateLimiter.isBlocked(anyString())).thenReturn(true);
        when(guestRateLimiter.getWindowSeconds()).thenReturn(3600L);

        // act + assert
        mockMvc.perform(post("/api/v1/auth/guest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\": \"550e8400-e29b-41d4-a716-446655440000\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));

        verify(guestAuthService, never()).loginOrRestore(anyString());
    }

    @Test
    void should_return_400_when_device_id_is_invalid_uuid() throws Exception {
        // act + assert — невалидный UUID4 → Bean Validation → 400
        mockMvc.perform(post("/api/v1/auth/guest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\": \"not-a-uuid\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    // ===================== POST /auth/guest/convert (issue #227) =====================

    @Test
    void should_return_200_with_email_sent_status_when_converting_via_email() throws Exception {
        // arrange
        User guest = userMock(10L);
        when(guest.isGuest()).thenReturn(true);
        when(currentUserProvider.currentUser()).thenReturn(guest);

        // act + assert
        mockMvc.perform(post("/api/v1/auth/guest/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\": \"EMAIL\", \"email\": \"new@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EMAIL_SENT"));

        verify(guestAuthService).convertViaEmail(guest, "new@example.com");
    }

    @Test
    void should_return_403_when_converting_non_guest_account() throws Exception {
        // arrange — токен реального юзера
        User real = userMock(20L);
        when(currentUserProvider.currentUser()).thenReturn(real);
        doThrow(GuestConvertException.notAGuest("User is not a guest"))
                .when(guestAuthService).convertViaEmail(any(), anyString());

        // act + assert
        mockMvc.perform(post("/api/v1/auth/guest/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\": \"EMAIL\", \"email\": \"other@example.com\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_A_GUEST"));
    }

    @Test
    void should_return_409_when_identity_already_taken_during_convert() throws Exception {
        // arrange — email уже занят другим аккаунтом
        User guest = userMock(30L);
        when(currentUserProvider.currentUser()).thenReturn(guest);
        doThrow(GuestConvertException.identityAlreadyTaken("Email already taken"))
                .when(guestAuthService).convertViaEmail(any(), anyString());

        // act + assert
        mockMvc.perform(post("/api/v1/auth/guest/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\": \"EMAIL\", \"email\": \"taken@example.com\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDENTITY_ALREADY_TAKEN"));
    }

    @Test
    void should_return_400_when_email_missing_for_email_provider() throws Exception {
        // arrange
        User guest = userMock(40L);
        when(currentUserProvider.currentUser()).thenReturn(guest);

        // act + assert — email обязателен для provider=EMAIL
        mockMvc.perform(post("/api/v1/auth/guest/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\": \"EMAIL\"}"))
                .andExpect(status().isBadRequest());
    }
}
